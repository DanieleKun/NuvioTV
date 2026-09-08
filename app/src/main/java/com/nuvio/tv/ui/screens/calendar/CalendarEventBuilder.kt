package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.Meta
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Weeks rendered by the month grid; six rows always cover any month for any first-day-of-week. */
internal const val CALENDAR_WEEK_ROWS = 6
internal const val DAYS_PER_WEEK = 7

/**
 * Turns a calendar source plus its full [Meta] into calendar events.
 *
 * - Series: one event per regular episode (season > 0) with a parseable release date.
 * - Movies, or series without dated episodes: a single event on [Meta.released] when it is a full
 *   date. Year-only values (e.g. "2024") cannot be placed on a day and produce no event.
 */
internal fun buildCalendarEvents(
    source: CalendarSource,
    meta: Meta,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<CalendarEvent> {
    val title = meta.name.ifBlank { source.name }
    val poster = meta.poster ?: source.poster
    val posterShape = meta.posterShape

    val episodeEvents = meta.videos
        .filter { video -> (video.season ?: 0) > 0 && video.episode != null }
        .mapNotNull { video ->
            val date = parseEpisodeReleaseLocalDate(video.released, zoneId) ?: return@mapNotNull null
            CalendarEvent(
                key = "${source.key}:${video.id}:${video.season}:${video.episode}",
                itemId = source.id,
                itemType = source.type,
                addonBaseUrl = source.addonBaseUrl,
                title = title,
                poster = poster,
                posterShape = posterShape,
                date = date,
                origin = source.origin,
                season = video.season,
                episode = video.episode,
                episodeTitle = video.title.takeIf { it.isNotBlank() }
            )
        }
    if (episodeEvents.isNotEmpty()) return episodeEvents

    val releaseDate = parseEpisodeReleaseLocalDate(meta.released, zoneId) ?: return emptyList()
    return listOf(
        CalendarEvent(
            key = source.key,
            itemId = source.id,
            itemType = source.type,
            addonBaseUrl = source.addonBaseUrl,
            title = title,
            poster = poster,
            posterShape = posterShape,
            date = releaseDate,
            origin = source.origin
        )
    )
}

/** Groups events by day, sorted by date, then episode order, then title. */
internal fun groupEventsByDate(events: Collection<CalendarEvent>): Map<LocalDate, List<CalendarEvent>> =
    events
        .sortedWith(
            compareBy<CalendarEvent> { it.date }
                .thenBy { it.title.lowercase() }
                .thenBy { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
        .groupBy { it.date }

/**
 * Builds the 6x7 grid of days for [month], starting weeks on [firstDayOfWeek].
 * Cells outside the month are `null` so the UI can render them as blanks.
 */
internal fun buildMonthGrid(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek
): List<List<LocalDate?>> {
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + DAYS_PER_WEEK) % DAYS_PER_WEEK
    val totalCells = CALENDAR_WEEK_ROWS * DAYS_PER_WEEK
    val daysInMonth = month.lengthOfMonth()

    return (0 until totalCells)
        .map { index ->
            val dayOfMonth = index - leadingBlanks + 1
            if (dayOfMonth in 1..daysInMonth) month.atDay(dayOfMonth) else null
        }
        .chunked(DAYS_PER_WEEK)
}

/** Ordered weekday headers matching the grid columns. */
internal fun weekdayOrder(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_PER_WEEK).map { firstDayOfWeek.plus(it.toLong()) }

/**
 * Picks the day to pre-select when [month] becomes visible: today if it falls in the month,
 * otherwise the first day with events, otherwise the first of the month.
 */
internal fun defaultSelectedDate(
    month: YearMonth,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>
): LocalDate {
    if (YearMonth.from(today) == month) return today
    return eventsByDate.keys
        .filter { YearMonth.from(it) == month }
        .minOrNull()
        ?: month.atDay(1)
}
