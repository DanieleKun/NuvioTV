package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarRelease
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarReleasePolicyTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 9, 11)
    private val window = CalendarReleasePolicy.window(today)

    @Test
    fun `window spans six months back and twelve months ahead`() {
        assertEquals(LocalDate.of(2026, 3, 11), window.start)
        assertEquals(LocalDate.of(2027, 9, 11), window.endInclusive)
    }

    @Test
    fun `extractReleases keeps only dated regular episodes inside the window`() {
        val meta = meta(
            type = ContentType.SERIES,
            videos = listOf(
                video(id = "old", season = 1, episode = 1, released = "2019-01-01"),
                video(id = "special", season = 0, episode = 1, released = "2026-09-01"),
                video(id = "undated", season = 2, episode = 1, released = null),
                video(id = "in", season = 2, episode = 2, released = "2026-09-20T00:00:00.000Z"),
                video(id = "far", season = 2, episode = 3, released = "2028-01-01")
            )
        )

        val releases = CalendarReleasePolicy.extractReleases(meta, window, utc)

        assertEquals(listOf("in"), releases.map { it.videoId })
        assertEquals(LocalDate.of(2026, 9, 20), releases.single().date)
    }

    @Test
    fun `extractReleases collapses an episode an addon lists twice`() {
        val meta = meta(
            type = ContentType.SERIES,
            videos = listOf(
                video(id = "dup", season = 1, episode = 1, released = "2026-09-20"),
                video(id = "dup", season = 1, episode = 1, released = "2026-09-20")
            )
        )

        assertEquals(1, CalendarReleasePolicy.extractReleases(meta, window, utc).size)
    }

    @Test
    fun `extractReleases uses the top-level date for movies and drops year-only values`() {
        val movie = meta(type = ContentType.MOVIE, released = "2026-12-25T00:00:00.000Z")
        val yearOnly = meta(type = ContentType.MOVIE, released = "2024")

        assertEquals(LocalDate.of(2026, 12, 25), CalendarReleasePolicy.extractReleases(movie, window, utc).single().date)
        assertTrue(CalendarReleasePolicy.extractReleases(yearOnly, window, utc).isEmpty())
    }

    @Test
    fun `mergeMovieReleases prefers the viewer region, falls back to US, keeps addon date when TMDB has nothing`() {
        val addon = listOf(release("2026-10-01"))
        val dates = listOf(
            CalendarReleasePolicy.TmdbReleaseDate("US", type = 3, date = LocalDate.of(2026, 9, 25)),
            CalendarReleasePolicy.TmdbReleaseDate("US", type = 4, date = LocalDate.of(2026, 11, 20)),
            CalendarReleasePolicy.TmdbReleaseDate("IT", type = 4, date = LocalDate.of(2026, 12, 1)),
            CalendarReleasePolicy.TmdbReleaseDate("IT", type = 4, date = LocalDate.of(2026, 12, 5)),
            CalendarReleasePolicy.TmdbReleaseDate("IT", type = 1, date = LocalDate.of(2026, 8, 1))
        )

        val italy = CalendarReleasePolicy.mergeMovieReleases(addon, dates, "it", window)
        assertEquals(listOf(CalendarReleaseKind.DIGITAL to LocalDate.of(2026, 12, 1)), italy.map { it.kind to it.date })

        val france = CalendarReleasePolicy.mergeMovieReleases(addon, dates, "FR", window)
        assertEquals(listOf(CalendarReleaseKind.THEATRICAL, CalendarReleaseKind.DIGITAL), france.map { it.kind })

        assertEquals(addon, CalendarReleasePolicy.mergeMovieReleases(addon, emptyList(), "IT", window))
    }

    @Test
    fun `stale re-queues movies without release kinds only when required and not too often`() {
        val now = 10_000_000L
        val movie = CalendarSource(id = "m1", type = "movie", name = "m1", poster = null, addonBaseUrl = null, origin = CalendarEventOrigin.LIBRARY)
        val fresh = entry("movie:m1", expiresAtMs = now + 1, releases = emptyList()).copy(resolvedAtMs = now - CalendarReleasePolicy.FAILED_TTL_MS - 1)
        val entries = mapOf(fresh.sourceKey to fresh)

        assertTrue(CalendarReleasePolicy.stale(listOf(movie), entries, now, today).isEmpty())
        assertEquals(listOf("m1"), CalendarReleasePolicy.stale(listOf(movie), entries, now, today, requireReleaseKinds = true).map { it.id })

        val justResolved = mapOf(fresh.sourceKey to fresh.copy(resolvedAtMs = now))
        assertTrue(CalendarReleasePolicy.stale(listOf(movie), justResolved, now, today, requireReleaseKinds = true).isEmpty())
    }

    @Test
    fun `ttl grows as a title goes quiet`() {
        val active = listOf(release("2026-09-18"))
        val recent = listOf(release("2026-08-20"))
        val dormant = listOf(release("2026-04-01"))

        assertEquals(CalendarReleasePolicy.ACTIVE_TTL_MS, ttl(CalendarResolveOutcome.RESOLVED, active))
        assertEquals(CalendarReleasePolicy.RECENT_TTL_MS, ttl(CalendarResolveOutcome.RESOLVED, recent))
        assertEquals(CalendarReleasePolicy.DORMANT_TTL_MS, ttl(CalendarResolveOutcome.RESOLVED, dormant))
        assertEquals(CalendarReleasePolicy.UNRESOLVABLE_TTL_MS, ttl(CalendarResolveOutcome.NO_DATES, emptyList()))
        assertEquals(CalendarReleasePolicy.UNRESOLVABLE_TTL_MS, ttl(CalendarResolveOutcome.NOT_FOUND, emptyList()))
        assertEquals(CalendarReleasePolicy.FAILED_TTL_MS, ttl(CalendarResolveOutcome.FAILED, emptyList()))
    }

    @Test
    fun `expiresAt jitters within fifteen percent of the ttl`() {
        val ttl = 1_000_000L
        val random = Random(42)

        repeat(200) {
            val expiresAt = CalendarReleasePolicy.expiresAt(nowMs = 0L, ttlMs = ttl, random = random)
            assertTrue(expiresAt in 850_000L..1_150_000L)
        }
    }

    @Test
    fun `stale lists missing watched titles first, then missing library titles by recency, then expired by next release`() {
        val now = 1_000_000L
        val watchedMissing = source("w1", CalendarEventOrigin.WATCHING, recencyMs = 1L)
        val libraryMissingOld = source("l1", CalendarEventOrigin.LIBRARY, recencyMs = 10L)
        val libraryMissingNew = source("l2", CalendarEventOrigin.LIBRARY, recencyMs = 20L)
        val expiredSoon = source("e1", CalendarEventOrigin.LIBRARY)
        val expiredLater = source("e2", CalendarEventOrigin.LIBRARY)
        val expiredNoUpcoming = source("e3", CalendarEventOrigin.LIBRARY)
        val fresh = source("f1", CalendarEventOrigin.LIBRARY)

        val entries = mapOf(
            expiredSoon.key to entry(expiredSoon.key, expiresAtMs = now - 1, releases = listOf(release("2026-09-12"))),
            expiredLater.key to entry(expiredLater.key, expiresAtMs = now - 1, releases = listOf(release("2026-10-01"))),
            expiredNoUpcoming.key to entry(expiredNoUpcoming.key, expiresAtMs = now - 1, releases = listOf(release("2026-05-01"))),
            fresh.key to entry(fresh.key, expiresAtMs = now + 1, releases = emptyList())
        )
        val sources = listOf(fresh, expiredNoUpcoming, libraryMissingOld, expiredLater, watchedMissing, expiredSoon, libraryMissingNew)

        val stale = CalendarReleasePolicy.stale(sources, entries, nowMs = now, today = today)

        assertEquals(listOf("w1", "l2", "l1", "e1", "e2", "e3"), stale.map { it.id })
    }

    private fun ttl(outcome: CalendarResolveOutcome, releases: List<CalendarRelease>) =
        CalendarReleasePolicy.ttlMs(outcome, releases, today)

    private fun release(date: String) = CalendarRelease(date = LocalDate.parse(date))

    private fun source(id: String, origin: CalendarEventOrigin, recencyMs: Long = 0L) = CalendarSource(
        id = id,
        type = "series",
        name = id,
        poster = null,
        addonBaseUrl = null,
        origin = origin,
        recencyMs = recencyMs
    )

    private fun entry(sourceKey: String, expiresAtMs: Long, releases: List<CalendarRelease>) = CalendarReleaseEntry(
        sourceKey = sourceKey,
        title = sourceKey,
        poster = null,
        posterShape = PosterShape.POSTER,
        releases = releases,
        outcome = CalendarResolveOutcome.RESOLVED,
        resolvedAtMs = 0L,
        expiresAtMs = expiresAtMs
    )

    private fun meta(
        type: ContentType,
        released: String? = null,
        videos: List<Video> = emptyList()
    ) = Meta(
        id = "tt1",
        type = type,
        name = "Title",
        poster = null,
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
        videos = videos,
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        released = released
    )

    private fun video(id: String, season: Int, episode: Int, released: String?) = Video(
        id = id,
        title = "Ep $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )
}
