package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventBuilderTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun `builds one event per regular episode with a parseable date`() {
        val entry = libraryEntry(type = "series")
        val meta = meta(
            type = ContentType.SERIES,
            videos = listOf(
                video(id = "v1", season = 1, episode = 1, released = "2026-09-03T00:00:00.000Z"),
                video(id = "v2", season = 1, episode = 2, released = "2026-09-10"),
                video(id = "v3", season = 1, episode = 3, released = null)
            )
        )

        val events = buildCalendarEvents(entry, meta, utc)

        assertEquals(2, events.size)
        assertEquals(LocalDate.of(2026, 9, 3), events[0].date)
        assertEquals(LocalDate.of(2026, 9, 10), events[1].date)
        assertTrue(events.all { it.isEpisode && it.itemId == "tt1" && it.itemType == "series" })
        assertEquals("Ep 2", events[1].episodeTitle)
    }

    @Test
    fun `skips specials in season zero`() {
        val entry = libraryEntry(type = "series")
        val meta = meta(
            type = ContentType.SERIES,
            videos = listOf(
                video(id = "s0", season = 0, episode = 1, released = "2026-09-01"),
                video(id = "s1", season = 1, episode = 1, released = "2026-09-02")
            )
        )

        val events = buildCalendarEvents(entry, meta, utc)

        assertEquals(listOf(LocalDate.of(2026, 9, 2)), events.map { it.date })
    }

    @Test
    fun `movie uses top-level released date and is not an episode`() {
        val entry = libraryEntry(type = "movie")
        val meta = meta(type = ContentType.MOVIE, released = "2026-12-25T00:00:00.000Z")

        val events = buildCalendarEvents(entry, meta, utc)

        assertEquals(1, events.size)
        assertEquals(LocalDate.of(2026, 12, 25), events.single().date)
        assertTrue(!events.single().isEpisode)
        assertNull(events.single().season)
    }

    @Test
    fun `returns empty when released is only a year`() {
        val entry = libraryEntry(type = "movie")
        val meta = meta(type = ContentType.MOVIE, released = "2024")

        assertTrue(buildCalendarEvents(entry, meta, utc).isEmpty())
    }

    @Test
    fun `falls back to entry name and poster when meta lacks them`() {
        val entry = libraryEntry(type = "movie", name = "Fallback", poster = "p.jpg")
        val meta = meta(type = ContentType.MOVIE, name = "", poster = null, released = "2026-01-01")

        val event = buildCalendarEvents(entry, meta, utc).single()

        assertEquals("Fallback", event.title)
        assertEquals("p.jpg", event.poster)
    }

    @Test
    fun `groupEventsByDate orders by date then title then episode`() {
        val base = buildCalendarEvents(
            libraryEntry(type = "series", name = "Zeta"),
            meta(
                type = ContentType.SERIES,
                name = "Zeta",
                videos = listOf(
                    video(id = "b", season = 1, episode = 2, released = "2026-09-05"),
                    video(id = "a", season = 1, episode = 1, released = "2026-09-05")
                )
            ),
            utc
        ) + buildCalendarEvents(
            libraryEntry(id = "tt2", type = "movie", name = "Alpha"),
            meta(id = "tt2", type = ContentType.MOVIE, name = "Alpha", released = "2026-09-05"),
            utc
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
    fun `events carry the origin of their source`() {
        val meta = meta(type = ContentType.MOVIE, released = "2026-01-01")

        val fromLibrary = buildCalendarEvents(libraryEntry(type = "movie"), meta, utc).single()
        val fromWatching = buildCalendarEvents(
            CalendarSource.fromWatchProgress(watchProgress(contentId = "tt1", contentType = "movie")),
            meta,
            utc
        ).single()

        assertEquals(CalendarEventOrigin.LIBRARY, fromLibrary.origin)
        assertEquals(CalendarEventOrigin.WATCHING, fromWatching.origin)
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

    private fun libraryEntry(
        id: String = "tt1",
        type: String,
        name: String = "Title",
        poster: String? = "poster.jpg"
    ) = CalendarSource.fromLibrary(libraryEntryModel(id, type, name, poster))

    private fun libraryEntryModel(
        id: String,
        type: String,
        name: String,
        poster: String?
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

    private fun meta(
        id: String = "tt1",
        type: ContentType,
        name: String = "Title",
        poster: String? = "meta-poster.jpg",
        released: String? = null,
        videos: List<Video> = emptyList()
    ) = Meta(
        id = id,
        type = type,
        name = name,
        poster = poster,
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
