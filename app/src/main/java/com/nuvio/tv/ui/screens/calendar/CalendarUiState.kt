package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import java.time.LocalDate
import java.time.YearMonth

/** Where a calendar item comes from; drives the small caption under the card. */
enum class CalendarEventOrigin {
    /** Saved in the library (any list). */
    LIBRARY,
    /** Not saved, but the user is watching it (Continue Watching / Next Up). */
    WATCHING
}

/** Minimal description of a title whose release dates should appear on the calendar. */
@Immutable
data class CalendarSource(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val addonBaseUrl: String?,
    val origin: CalendarEventOrigin
) {
    val key: String
        get() = "$type:$id"

    companion object {
        fun fromLibrary(entry: LibraryEntry) = CalendarSource(
            id = entry.id,
            type = entry.type,
            name = entry.name,
            poster = entry.poster,
            addonBaseUrl = entry.addonBaseUrl,
            origin = CalendarEventOrigin.LIBRARY
        )

        fun fromWatchProgress(progress: WatchProgress) = CalendarSource(
            id = progress.contentId,
            type = progress.contentType,
            name = progress.name,
            poster = progress.poster,
            addonBaseUrl = progress.addonBaseUrl,
            origin = CalendarEventOrigin.WATCHING
        )
    }
}

/**
 * A single release shown on the calendar: either a movie release or one episode of a series.
 */
@Immutable
data class CalendarEvent(
    /** Stable unique key, e.g. `series:tt123:tt123:2:5`. */
    val key: String,
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String?,
    /** Movie or series title. */
    val title: String,
    val poster: String?,
    val posterShape: PosterShape,
    val date: LocalDate,
    val origin: CalendarEventOrigin,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null
) {
    val isEpisode: Boolean
        get() = season != null && episode != null

    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = itemId,
        type = ContentType.fromString(itemType),
        rawType = itemType,
        name = title,
        poster = poster,
        posterShape = posterShape,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}

@Immutable
data class CalendarUiState(
    val today: LocalDate = LocalDate.now(),
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val eventsByDate: Map<LocalDate, List<CalendarEvent>> = emptyMap(),
    /** Keys of [CalendarEvent]s on the selected day that the user has already watched. */
    val watchedEventKeys: Set<String> = emptySet(),
    val isLibraryEmpty: Boolean = false,
    val isLoading: Boolean = true,
    val loadedCount: Int = 0,
    val totalCount: Int = 0
) {
    fun eventsOn(date: LocalDate): List<CalendarEvent> = eventsByDate[date].orEmpty()

    val selectedDateEvents: List<CalendarEvent>
        get() = eventsOn(selectedDate)

    /** First day strictly after [date] that has at least one release, if any. */
    fun nextReleaseAfter(date: LocalDate): LocalDate? =
        eventsByDate.keys.filter { it.isAfter(date) }.minOrNull()
}
