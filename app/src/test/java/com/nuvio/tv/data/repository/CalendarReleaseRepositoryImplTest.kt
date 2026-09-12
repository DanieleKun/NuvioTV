package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CalendarReleaseCache
import com.nuvio.tv.domain.model.CalendarRelease
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration of a refresh pass against fakes: what gets resolved, what gets written, and
 * what a failure is allowed to overwrite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReleaseRepositoryImplTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 11)
    private val nowMs = 1_000_000_000_000L

    @Test
    fun `interactive pass resolves every missing source and persists it in batches`() = runTest {
        val library = (1..30).map { libraryEntry("tt$it") }
        val meta = MetaFake(default = { id -> seriesMeta(id, episodeDate = "2026-09-20") })
        val cache = CacheFake()
        val repository = repository(library, meta, cache)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        assertEquals(30, cache.store.size)
        assertTrue(cache.store.values.all { it.outcome == CalendarResolveOutcome.RESOLVED })
        assertEquals(30, meta.fetches.get())
        assertTrue("expected few batched writes, got ${cache.writes}", cache.writes in 1..3)
    }

    @Test
    fun `periodic pass is capped and prefers watched titles`() = runTest {
        val library = (1..60).map { libraryEntry("tt$it") }
        val watching = listOf(watchProgress("ttWatched"))
        val meta = MetaFake(default = { id -> seriesMeta(id, episodeDate = "2026-09-20") })
        val cache = CacheFake()
        val repository = repository(library, meta, cache, watching)

        repository.requestRefresh() // screen hidden -> periodic
        repository.awaitIdle()

        assertEquals(CalendarReleaseRepositoryImpl.PERIODIC_BATCH_LIMIT, cache.store.size)
        assertTrue(cache.store.containsKey("series:ttWatched"))
    }

    @Test
    fun `a title saved to the library and in continue watching keeps LIBRARY origin but is still isWatching`() = runTest {
        // Reproduces the "Stuart Fails to Save the Universe" case: saved AND being watched, so it
        // must satisfy both a library filter and the Watching filter — origin alone can't tell.
        val library = listOf(libraryEntry("ttBoth"))
        val watching = listOf(watchProgress("ttBoth"))
        val meta = MetaFake(default = { null })
        val repository = repository(library, meta, CacheFake(), watching)

        val sources = repository.sources.first()

        val source = sources.single { it.id == "ttBoth" }
        assertEquals(com.nuvio.tv.domain.model.CalendarEventOrigin.LIBRARY, source.origin)
        assertTrue(source.isWatching)
    }

    @Test
    fun `a failed refresh keeps the previously known releases`() = runTest {
        val known = CalendarReleaseEntry(
            sourceKey = "series:tt1",
            title = "Known",
            poster = "known.jpg",
            posterShape = PosterShape.POSTER,
            releases = listOf(CalendarRelease(date = LocalDate.of(2026, 9, 25), videoId = "v1", season = 1, episode = 5)),
            outcome = CalendarResolveOutcome.RESOLVED,
            resolvedAtMs = nowMs - 10,
            expiresAtMs = nowMs - 1 // expired, so it is refreshed
        )
        val cache = CacheFake(initial = mapOf(known.sourceKey to known))
        val meta = MetaFake(default = { null })
        val repository = repository(listOf(libraryEntry("tt1")), meta, cache)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        val refreshed = cache.store.getValue("series:tt1")
        assertEquals(CalendarResolveOutcome.FAILED, refreshed.outcome)
        assertEquals(known.releases, refreshed.releases)
        assertEquals("Known", refreshed.title)
        assertTrue(refreshed.expiresAtMs > known.expiresAtMs)
    }

    @Test
    fun `fresh entries are not fetched again`() = runTest {
        val fresh = CalendarReleaseEntry(
            sourceKey = "series:tt1",
            title = "Fresh",
            poster = null,
            posterShape = PosterShape.POSTER,
            releases = emptyList(),
            outcome = CalendarResolveOutcome.NO_DATES,
            resolvedAtMs = nowMs,
            expiresAtMs = Long.MAX_VALUE
        )
        val cache = CacheFake(initial = mapOf(fresh.sourceKey to fresh))
        val meta = MetaFake(default = { id -> seriesMeta(id, episodeDate = "2026-09-20") })
        val repository = repository(listOf(libraryEntry("tt1"), libraryEntry("tt2")), meta, cache)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        assertEquals(1, meta.fetches.get())
        assertEquals(setOf("series:tt1", "series:tt2"), cache.store.keys)
    }

    @Test
    fun `entries of removed titles are pruned only once expired`() = runTest {
        val expiredObsolete = entry("series:gone1", expiresAtMs = nowMs - 1)
        val freshObsolete = entry("series:gone2", expiresAtMs = Long.MAX_VALUE)
        val cache = CacheFake(initial = mapOf(expiredObsolete.sourceKey to expiredObsolete, freshObsolete.sourceKey to freshObsolete))
        val meta = MetaFake(default = { id -> seriesMeta(id, episodeDate = "2026-09-20") })
        val repository = repository(listOf(libraryEntry("tt1")), meta, cache)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        assertEquals(setOf("series:gone2", "series:tt1"), cache.store.keys)
    }

    @Test
    fun `a pass stops after repeated consecutive failures`() = runTest {
        val library = (1..30).map { libraryEntry("tt$it") }
        val meta = MetaFake(default = { null })
        val cache = CacheFake()
        val repository = repository(library, meta, cache)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        val limit = CalendarReleaseRepositoryImpl.CIRCUIT_BREAKER_FAILURES + CalendarReleaseRepositoryImpl.INTERACTIVE_CONCURRENCY
        assertTrue("resolved ${cache.store.size}, expected at most $limit", cache.store.size <= limit)
        assertTrue(cache.store.size >= CalendarReleaseRepositoryImpl.CIRCUIT_BREAKER_FAILURES)
    }

    @Test
    fun `movies get theatrical and digital dates from TMDB when enabled`() = runTest {
        val kinds = KindsFake(
            isEnabled = true,
            dates = listOf(
                CalendarReleasePolicy.TmdbReleaseDate("IT", type = 3, date = LocalDate.of(2026, 10, 1)),
                CalendarReleasePolicy.TmdbReleaseDate("IT", type = 4, date = LocalDate.of(2026, 12, 15)),
                CalendarReleasePolicy.TmdbReleaseDate("US", type = 4, date = LocalDate.of(2026, 11, 1))
            )
        )
        val meta = MetaFake(default = { id -> movieMeta(id, released = "2026-10-01") })
        val cache = CacheFake()
        val repository = repository(listOf(libraryEntry("tt1", type = "movie")), meta, cache, kinds = kinds)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        val entry = cache.store.getValue("movie:tt1")
        assertTrue(entry.releaseKindsResolved)
        assertEquals(
            listOf(CalendarReleaseKind.THEATRICAL to LocalDate.of(2026, 10, 1), CalendarReleaseKind.DIGITAL to LocalDate.of(2026, 12, 15)),
            entry.releases.map { it.kind to it.date }
        )
    }

    @Test
    fun `TMDB is not asked when disabled and series never trigger it`() = runTest {
        val kinds = KindsFake(isEnabled = false)
        val meta = MetaFake(default = { id -> movieMeta(id, released = "2026-10-01") })
        val cache = CacheFake()
        val repository = repository(listOf(libraryEntry("tt1", type = "movie"), libraryEntry("tt2")), meta, cache, kinds = kinds)

        repository.setScreenVisible(true)
        repository.awaitIdle()

        assertEquals(0, kinds.fetches.get())
        val movie = cache.store.getValue("movie:tt1")
        assertTrue(!movie.releaseKindsResolved)
        assertEquals(listOf<CalendarReleaseKind?>(null), movie.releases.map { it.kind })
    }

    // --- Harness -------------------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.repository(
        library: List<LibraryEntry>,
        meta: MetaFake,
        cache: CacheFake,
        watching: List<WatchProgress> = emptyList(),
        kinds: KindsFake = KindsFake()
    ): CalendarReleaseRepositoryImpl {
        val libraryRepository = mockk<LibraryRepository>()
        every { libraryRepository.libraryItems } returns flowOf(library)
        val watchProgressRepository = mockk<WatchProgressRepository>()
        every { watchProgressRepository.continueWatching } returns flowOf(watching)
        every { watchProgressRepository.observeNextUpSeeds() } returns flowOf(emptyList())
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return CalendarReleaseRepositoryImpl(
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = meta,
            cache = cache,
            profileManager = profileManager,
            releaseKindsSource = kinds,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            today = { today },
            region = { "IT" }
        )
    }

    /** TMDB stand-in: disabled by default; when enabled answers with the configured dates. */
    private class KindsFake(
        override val isEnabled: Boolean = false,
        private val dates: List<CalendarReleasePolicy.TmdbReleaseDate>? = emptyList()
    ) : CalendarMovieReleaseKindsSource {
        override val enabled: Flow<Boolean> = flowOf(isEnabled)
        val fetches = AtomicInteger()
        override suspend fun fetch(source: CalendarSource): List<CalendarReleasePolicy.TmdbReleaseDate>? {
            fetches.incrementAndGet()
            return dates
        }
    }

    /** Returns the meta from [default], or a plain error when it yields null. */
    private class MetaFake(private val default: (String) -> Meta?) : MetaRepository {
        val fetches = AtomicInteger()

        override fun getMeta(addonBaseUrl: String, type: String, id: String): Flow<NetworkResult<Meta>> {
            fetches.incrementAndGet()
            return flowOf(default(id)?.let { NetworkResult.Success(it) } ?: NetworkResult.Error("down"))
        }

        override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?): Flow<NetworkResult<Meta>> =
            flowOf(default(id)?.let { NetworkResult.Success(it) } ?: NetworkResult.Error("down"))

        override fun getMetaFromPrimaryAddon(type: String, id: String): Flow<NetworkResult<Meta>> =
            getMetaFromAllAddons(type, id)

        override fun getCachedMeta(type: String, id: String): Meta? = null

        override fun clearCache() = Unit
    }

    private class CacheFake(initial: Map<String, CalendarReleaseEntry> = emptyMap()) : CalendarReleaseCache {
        val store = LinkedHashMap(initial)
        var writes = 0
        private val flow = MutableStateFlow<Map<String, CalendarReleaseEntry>>(initial)

        override val entries: Flow<Map<String, CalendarReleaseEntry>> = flow

        override suspend fun getEntries(profileId: Int): Map<String, CalendarReleaseEntry> = store.toMap()

        override suspend fun upsertAll(profileId: Int, entries: Collection<CalendarReleaseEntry>) {
            entries.forEach { store[it.sourceKey] = it }
            writes++
            flow.value = store.toMap()
        }

        override suspend fun removeAll(profileId: Int, sourceKeys: Collection<String>) {
            sourceKeys.forEach { store.remove(it) }
            flow.value = store.toMap()
        }

        override suspend fun clear(profileId: Int) {
            store.clear()
            flow.value = emptyMap()
        }
    }

    private fun entry(sourceKey: String, expiresAtMs: Long) = CalendarReleaseEntry(
        sourceKey = sourceKey,
        title = sourceKey,
        poster = null,
        posterShape = PosterShape.POSTER,
        releases = emptyList(),
        outcome = CalendarResolveOutcome.NO_DATES,
        resolvedAtMs = nowMs,
        expiresAtMs = expiresAtMs
    )

    private fun libraryEntry(id: String, type: String = "series") = LibraryEntry(
        id = id,
        type = type,
        name = "Title $id",
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = "https://addon.example"
    )

    private fun watchProgress(contentId: String) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Watching $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = 100L,
        duration = 1000L,
        lastWatched = nowMs
    )

    private fun movieMeta(id: String, released: String) = seriesMeta(id, episodeDate = "").copy(
        type = ContentType.MOVIE,
        videos = emptyList(),
        released = released
    )

    private fun seriesMeta(id: String, episodeDate: String) = Meta(
        id = id,
        type = ContentType.SERIES,
        name = "Meta $id",
        poster = "poster.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = listOf(
            Video(id = "$id:1:1", title = "Ep 1", released = episodeDate, thumbnail = null, season = 1, episode = 1, overview = null)
        ),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )
}
