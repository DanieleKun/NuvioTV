package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.domain.model.CalendarAnimeDetection
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarRelease
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventBuilderTest {

    @Test
    fun `builds one event per persisted episode release`() {
        val source = librarySource(type = "series")
        val entry = entry(
            releases = listOf(
                release(date = "2026-09-03", videoId = "v1", season = 1, episode = 1),
                release(date = "2026-09-10", videoId = "v2", season = 1, episode = 2, episodeTitle = "Ep 2")
            )
        )

        val events = buildCalendarEvents(source, entry)

        assertEquals(2, events.size)
        assertEquals(LocalDate.of(2026, 9, 3), events[0].date)
        assertEquals("series:tt1:v2:1:2", events[1].key)
        assertTrue(events.all { it.isEpisode && it.itemId == "tt1" && it.itemType == "series" })
        assertEquals("Ep 2", events[1].episodeTitle)
    }

    @Test
    fun `movie release is a single non-episode event keyed by the source`() {
        val source = librarySource(type = "movie")
        val entry = entry(releases = listOf(release(date = "2026-12-25")))

        val event = buildCalendarEvents(source, entry).single()

        assertEquals("movie:tt1", event.key)
        assertFalse(event.isEpisode)
        assertNull(event.season)
    }

    @Test
    fun `falls back to source name and poster when the entry lacks them`() {
        val source = librarySource(type = "movie", name = "Fallback", poster = "p.jpg")
        val entry = entry(title = "", poster = null, releases = listOf(release(date = "2026-01-01")))

        val event = buildCalendarEvents(source, entry).single()

        assertEquals("Fallback", event.title)
        assertEquals("p.jpg", event.poster)
    }

    @Test
    fun `events carry the origin of their current source, not of the cached entry`() {
        val entry = entry(releases = listOf(release(date = "2026-01-01")))

        val fromLibrary = buildCalendarEvents(librarySource(type = "movie"), entry).single()
        val fromWatching = buildCalendarEvents(
            CalendarSource.fromWatchProgress(watchProgress(contentId = "tt1", contentType = "movie")),
            entry
        ).single()

        assertEquals(CalendarEventOrigin.LIBRARY, fromLibrary.origin)
        assertEquals(CalendarEventOrigin.WATCHING, fromWatching.origin)
    }

    @Test
    fun `groupEventsByDate orders by date then title then episode`() {
        val base = buildCalendarEvents(
            librarySource(type = "series", name = "Zeta"),
            entry(
                title = "Zeta",
                releases = listOf(
                    release(date = "2026-09-05", videoId = "b", season = 1, episode = 2),
                    release(date = "2026-09-05", videoId = "a", season = 1, episode = 1)
                )
            )
        ) + buildCalendarEvents(
            librarySource(id = "tt2", type = "movie", name = "Alpha"),
            entry(sourceKey = "movie:tt2", title = "Alpha", releases = listOf(release(date = "2026-09-05")))
        )

        val grouped = groupEventsByDate(base)

        val day = grouped.getValue(LocalDate.of(2026, 9, 5))
        assertEquals(listOf("Alpha", "Zeta", "Zeta"), day.map { it.title })
        assertEquals(listOf(null, 1, 2), day.map { it.episode })
    }

    @Test
    fun `buildMonthGrid pads leading blanks for a Monday-first week`() {
        // September 2026 starts on a Tuesday.
        val grid = buildMonthGrid(YearMonth.of(2026, 9), DayOfWeek.MONDAY)

        assertEquals(CALENDAR_WEEK_ROWS, grid.size)
        assertTrue(grid.all { it.size == DAYS_PER_WEEK })
        assertNull(grid[0][0])
        assertEquals(LocalDate.of(2026, 9, 1), grid[0][1])
        assertEquals(LocalDate.of(2026, 9, 30), grid[4][2])
        assertTrue(grid[5].all { it == null })
    }

    @Test
    fun `buildMonthGrid pads leading blanks for a Sunday-first week`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 9), DayOfWeek.SUNDAY)

        assertNull(grid[0][0])
        assertNull(grid[0][1])
        assertEquals(LocalDate.of(2026, 9, 1), grid[0][2])
    }

    @Test
    fun `weekdayOrder starts at the configured first day`() {
        assertEquals(DayOfWeek.SUNDAY, weekdayOrder(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekdayOrder(DayOfWeek.SUNDAY).last())
        assertEquals(DayOfWeek.MONDAY, weekdayOrder(DayOfWeek.MONDAY).first())
    }

    @Test
    fun `defaultSelectedDate prefers today, then first event day, then first of month`() {
        val today = LocalDate.of(2026, 9, 7)
        val octoberEvents = mapOf(LocalDate.of(2026, 10, 14) to emptyList<CalendarEvent>())

        assertEquals(today, defaultSelectedDate(YearMonth.of(2026, 9), today, octoberEvents))
        assertEquals(LocalDate.of(2026, 10, 14), defaultSelectedDate(YearMonth.of(2026, 10), today, octoberEvents))
        assertEquals(LocalDate.of(2026, 11, 1), defaultSelectedDate(YearMonth.of(2026, 11), today, octoberEvents))
    }

    @Test
    fun `agendaAnchorDate lands on the selected day, else the next release day, else the last one`() {
        val days = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 18))

        assertEquals(LocalDate.of(2026, 9, 11), agendaAnchorDate(days, LocalDate.of(2026, 9, 11)))
        // Today (12th) has nothing: the D-pad goes to the next release, not the top of the month.
        assertEquals(LocalDate.of(2026, 9, 18), agendaAnchorDate(days, LocalDate.of(2026, 9, 12)))
        assertEquals(LocalDate.of(2026, 9, 18), agendaAnchorDate(days, LocalDate.of(2026, 9, 25)))
        assertNull(agendaAnchorDate(emptyList(), LocalDate.of(2026, 9, 12)))
    }

    @Test
    fun `agendaDays keeps an empty today in the timeline of its own month only`() {
        val days = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 18))
        val today = LocalDate.of(2026, 9, 12)

        assertEquals(
            listOf(LocalDate.of(2026, 9, 4), today, LocalDate.of(2026, 9, 18)),
            agendaDays(days, today, YearMonth.of(2026, 9))
        )
        assertEquals(days, agendaDays(days, today, YearMonth.of(2026, 10)))
        val withToday = listOf(LocalDate.of(2026, 9, 12))
        assertEquals(withToday, agendaDays(withToday, today, YearMonth.of(2026, 9)))
    }

    @Test
    fun `anime filter keeps titles flagged by the source or by the resolved entry`() {
        val fromTracker = buildCalendarEvents(
            CalendarSource.fromLibrary(libraryEntry(id = "kitsu:1", type = "series")),
            entry(sourceKey = "series:kitsu:1", releases = listOf(release(date = "2026-09-05", videoId = "v", season = 1, episode = 1)))
        )
        val fromMeta = buildCalendarEvents(
            librarySource(id = "tt9", type = "series"),
            entry(sourceKey = "series:tt9", isAnime = true, releases = listOf(release(date = "2026-09-06", videoId = "v", season = 1, episode = 1)))
        )
        val plain = buildCalendarEvents(librarySource(type = "movie"), entry(releases = listOf(release(date = "2026-09-07"))))

        val filtered = filterCalendarEvents(fromTracker + fromMeta + plain, CalendarFilter.ANIME)

        assertEquals(listOf("series:kitsu:1:v:1:1", "series:tt9:v:1:1"), filtered.map { it.key })
        assertTrue(filtered.all { it.isAnime })
    }

    @Test
    fun `anime detection uses tracker ids, anime genres and Japanese animation`() {
        assertTrue(CalendarAnimeDetection.matches(id = "mal:123", genres = emptyList()))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Anime")))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), language = "ja"))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), language = "jpn"))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), country = "Japan"))
        assertFalse(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), language = "en", country = "Japan"))
        assertFalse(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation")))
        assertFalse(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Drama"), language = "ja"))
    }

    @Test
    fun `anime detection checks every country in a co-production list`() {
        // Cinemeta-style metadata often joins countries as "Japan, United States".
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), country = "Japan, United States"))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), country = "United States, Japan"))
        assertTrue(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), country = "United States / Japan"))
        assertFalse(CalendarAnimeDetection.matches(id = "tt1", genres = listOf("Animation"), country = "United States, France"))
    }

    @Test
    fun `watching and library filters split by origin and tracking provider`() {
        val watching = buildCalendarEvents(
            CalendarSource.fromWatchProgress(watchProgress(contentId = "tt1")),
            entry(sourceKey = "series:tt1", releases = listOf(release(date = "2026-09-05", videoId = "v", season = 1, episode = 1)))
        )
        val nuvioLibrary = buildCalendarEvents(
            librarySource(id = "tt2", type = "movie"),
            entry(sourceKey = "movie:tt2", releases = listOf(release(date = "2026-09-06")))
        )
        val traktLibrary = buildCalendarEvents(
            CalendarSource.fromLibrary(libraryEntry(id = "tt3", type = "movie").copy(trackingProviderId = "trakt")),
            entry(sourceKey = "movie:tt3", releases = listOf(release(date = "2026-09-07")))
        )
        val simklLibrary = buildCalendarEvents(
            CalendarSource.fromLibrary(libraryEntry(id = "tt4", type = "movie").copy(trackingProviderId = "simkl")),
            entry(sourceKey = "movie:tt4", releases = listOf(release(date = "2026-09-08")))
        )
        val all = watching + nuvioLibrary + traktLibrary + simklLibrary

        assertEquals(watching.map { it.key }, filterCalendarEvents(all, CalendarFilter.WATCHING).map { it.key })
        assertEquals(nuvioLibrary.map { it.key }, filterCalendarEvents(all, CalendarFilter.LIBRARY_NUVIO).map { it.key })
        assertEquals(traktLibrary.map { it.key }, filterCalendarEvents(all, CalendarFilter.LIBRARY_TRAKT).map { it.key })
        assertEquals(simklLibrary.map { it.key }, filterCalendarEvents(all, CalendarFilter.LIBRARY_SIMKL).map { it.key })
    }

    @Test
    fun `a title saved to the library and being watched matches both the Watching and the library filter`() {
        // The origin alone can't express this: dedup in the repository always picks LIBRARY when a
        // title is both saved and in Continue Watching, so `isWatching` must be tracked separately.
        val savedAndWatched = buildCalendarEvents(
            CalendarSource.fromLibrary(libraryEntry(id = "tt9", type = "series")).copy(isWatching = true),
            entry(sourceKey = "series:tt9", releases = listOf(release(date = "2026-09-05", videoId = "v", season = 1, episode = 1)))
        )
        val libraryOnly = buildCalendarEvents(
            librarySource(id = "tt10", type = "series"),
            entry(sourceKey = "series:tt10", releases = listOf(release(date = "2026-09-06", videoId = "v", season = 1, episode = 1)))
        )
        val all = savedAndWatched + libraryOnly

        assertEquals(savedAndWatched.map { it.key }, filterCalendarEvents(all, CalendarFilter.WATCHING).map { it.key })
        assertEquals(all.map { it.key }, filterCalendarEvents(all, CalendarFilter.LIBRARY_NUVIO).map { it.key })
    }

    @Test
    fun `library entries tagged anime by the tracker become anime sources`() {
        val tagged = CalendarSource.fromLibrary(libraryEntry(id = "tt5", type = "series").copy(mediaCategory = "anime"))
        val untagged = CalendarSource.fromLibrary(libraryEntry(id = "tt6", type = "series"))

        assertTrue(tagged.isAnime)
        assertFalse(untagged.isAnime)
    }

    @Test
    fun `nextReleaseAfter skips the given day and returns the first later one`() {
        val state = CalendarUiState(
            eventsByDate = mapOf(
                LocalDate.of(2026, 9, 7) to emptyList<CalendarEvent>(),
                LocalDate.of(2026, 9, 14) to emptyList(),
                LocalDate.of(2026, 10, 2) to emptyList()
            )
        )

        assertEquals(LocalDate.of(2026, 9, 14), state.nextReleaseAfter(LocalDate.of(2026, 9, 7)))
        assertEquals(LocalDate.of(2026, 10, 2), state.nextReleaseAfter(LocalDate.of(2026, 9, 14)))
        assertNull(state.nextReleaseAfter(LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun `month navigation is clamped to the tracked window`() {
        val state = CalendarUiState(
            visibleMonth = YearMonth.of(2026, 9),
            earliestMonth = YearMonth.of(2026, 9),
            latestMonth = YearMonth.of(2026, 10)
        )

        assertFalse(state.canShowPreviousMonth)
        assertTrue(state.canShowNextMonth)
        assertFalse(state.copy(visibleMonth = YearMonth.of(2026, 10)).canShowNextMonth)
    }

    private fun release(
        date: String,
        videoId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null
    ) = CalendarRelease(
        date = LocalDate.parse(date),
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle
    )

    private fun entry(
        sourceKey: String = "series:tt1",
        title: String = "Title",
        poster: String? = "meta-poster.jpg",
        isAnime: Boolean = false,
        releases: List<CalendarRelease>
    ) = CalendarReleaseEntry(
        sourceKey = sourceKey,
        title = title,
        poster = poster,
        posterShape = PosterShape.POSTER,
        releases = releases,
        outcome = CalendarResolveOutcome.RESOLVED,
        resolvedAtMs = 0L,
        expiresAtMs = Long.MAX_VALUE,
        isAnime = isAnime
    )

    private fun watchProgress(contentId: String) = watchProgress(contentId, contentType = "series")

    private fun watchProgress(contentId: String, contentType: String) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "Watching title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = 100L,
        duration = 1000L,
        lastWatched = 0L
    )

    private fun librarySource(
        id: String = "tt1",
        type: String,
        name: String = "Title",
        poster: String? = "poster.jpg"
    ) = CalendarSource.fromLibrary(libraryEntry(id = id, type = type, name = name, poster = poster))

    private fun libraryEntry(
        id: String,
        type: String,
        name: String = "Title",
        poster: String? = "poster.jpg"
    ) = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = "https://addon.example"
    )
}
