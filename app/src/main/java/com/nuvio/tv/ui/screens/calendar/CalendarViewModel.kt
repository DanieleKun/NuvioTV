package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_CONCURRENT_META_REQUESTS = 4

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val metaRepository: MetaRepository,
    private val watchProgressRepository: WatchProgressRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    /** Resolved events per source key; survives re-emissions so nothing is refetched. */
    private val eventsBySource = ConcurrentHashMap<String, List<CalendarEvent>>()

    init {
        viewModelScope.launch {
            combine(libraryRepository.libraryItems, watchingSeriesSources()) { library, watching ->
                val librarySources = library.map(CalendarSource::fromLibrary)
                val libraryKeys = librarySources.map { it.key }.toSet()
                (librarySources + watching.filter { it.key !in libraryKeys }).distinctBy { it.key }
            }
                .distinctUntilChanged()
                .collectLatest { sources -> loadEvents(sources) }
        }
        observeWatchedStateForSelectedDay()
    }

    fun showNextMonth() = shiftMonth(1)

    fun showPreviousMonth() = shiftMonth(-1)

    fun showToday() {
        _uiState.update { current ->
            val today = LocalDate.now()
            current.copy(today = today, visibleMonth = YearMonth.from(today), selectedDate = today)
        }
    }

    /** Re-reads the system date (e.g. after the app resumed the next morning) without moving the selection. */
    fun refreshToday() {
        val today = LocalDate.now()
        _uiState.update { current ->
            if (current.today == today) current else current.copy(today = today)
        }
    }

    fun selectDate(date: LocalDate) {
        _uiState.update { current ->
            if (current.selectedDate == date) current else current.copy(selectedDate = date)
        }
    }

    /** Jumps to the first day after the current selection that has a release, changing month if needed. */
    fun jumpToNextRelease() {
        _uiState.update { current ->
            val next = current.nextReleaseAfter(current.selectedDate) ?: return@update current
            current.copy(visibleMonth = YearMonth.from(next), selectedDate = next)
        }
    }

    private fun shiftMonth(months: Long) {
        _uiState.update { current ->
            val month = current.visibleMonth.plusMonths(months)
            current.copy(
                visibleMonth = month,
                selectedDate = defaultSelectedDate(month, current.today, current.eventsByDate)
            )
        }
    }

    /** Series the user is actively watching (in progress or awaiting the next episode). */
    private fun watchingSeriesSources(): Flow<List<CalendarSource>> =
        combine(
            watchProgressRepository.continueWatching,
            watchProgressRepository.observeNextUpSeeds()
        ) { inProgress, nextUpSeeds ->
            (inProgress + nextUpSeeds)
                .filter { ContentType.fromString(it.contentType) == ContentType.SERIES }
                .distinctBy { it.contentId }
                .map(CalendarSource::fromWatchProgress)
        }

    /** Keeps [CalendarUiState.watchedEventKeys] in sync with the releases of the selected day only. */
    private fun observeWatchedStateForSelectedDay() {
        viewModelScope.launch {
            _uiState
                .map { it.selectedDateEvents }
                .distinctUntilChanged()
                .flatMapLatest { events -> watchedKeysFlow(events) }
                .distinctUntilChanged()
                .collect { watched ->
                    _uiState.update { current ->
                        if (current.watchedEventKeys == watched) current else current.copy(watchedEventKeys = watched)
                    }
                }
        }
    }

    private fun watchedKeysFlow(events: List<CalendarEvent>): Flow<Set<String>> {
        if (events.isEmpty()) return flowOf(emptySet())
        val flows = events.map { event ->
            watchProgressRepository
                .isWatched(contentId = event.itemId, season = event.season, episode = event.episode)
                .map { watched -> event.key to watched }
        }
        return combine(flows) { pairs -> pairs.filter { it.second }.map { it.first }.toSet() }
    }

    private suspend fun loadEvents(sources: List<CalendarSource>) {
        val activeKeys = sources.map { it.key }.toSet()
        eventsBySource.keys.retainAll(activeKeys)
        val pending = sources.filter { !eventsBySource.containsKey(it.key) }

        publishState(
            isLibraryEmpty = sources.isEmpty(),
            isLoading = pending.isNotEmpty(),
            totalCount = sources.size
        )
        if (pending.isEmpty()) return

        val semaphore = Semaphore(MAX_CONCURRENT_META_REQUESTS)
        coroutineScope {
            pending.map { source ->
                async {
                    semaphore.withPermit {
                        eventsBySource[source.key] = resolveEvents(source)
                        publishState(
                            isLibraryEmpty = false,
                            isLoading = eventsBySource.size < sources.size,
                            totalCount = sources.size
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun publishState(isLibraryEmpty: Boolean, isLoading: Boolean, totalCount: Int) {
        val eventsByDate = groupEventsByDate(eventsBySource.values.flatten())
        _uiState.update { current ->
            current.copy(
                eventsByDate = eventsByDate,
                isLibraryEmpty = isLibraryEmpty,
                isLoading = isLoading,
                loadedCount = eventsBySource.size,
                totalCount = totalCount
            )
        }
    }

    private suspend fun resolveEvents(source: CalendarSource): List<CalendarEvent> {
        val meta = try {
            fetchMeta(source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return meta?.let { buildCalendarEvents(source, it) } ?: emptyList()
    }

    /**
     * Prefers the addon the item was saved from (exact match, no sentinel errors), then falls
     * back to every installed addon. Cached metas short-circuit without a network call.
     */
    private suspend fun fetchMeta(source: CalendarSource): Meta? {
        metaRepository.getCachedMeta(source.type, source.id)?.let { return it }

        val sourceAddon = source.addonBaseUrl?.takeIf { it.isNotBlank() }
        if (sourceAddon != null) {
            val fromSource = metaRepository.getMeta(sourceAddon, source.type, source.id)
                .first { it !is NetworkResult.Loading }
            if (fromSource is NetworkResult.Success) return fromSource.data
        }

        val fromAny = metaRepository.getMetaFromAllAddons(source.type, source.id)
            .first { it !is NetworkResult.Loading }
        return (fromAny as? NetworkResult.Success)?.data
    }
}
