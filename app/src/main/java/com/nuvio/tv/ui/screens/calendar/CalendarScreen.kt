package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay

private const val DAY_PANEL_WIDTH_FRACTION = 0.4f
private const val FOCUS_RETRY_ATTEMPTS = 4
private const val FOCUS_RETRY_DELAY_MS = 32L

/**
 * Monthly calendar of releases for everything saved in the library.
 *
 * Left: 7-column month grid with stacked mini posters per day. Right: the focused day's releases
 * as poster cards; OK on a day jumps into that panel, LEFT/BACK from the panel returns to the day.
 * Clicking a card opens the detail screen (episode pre-focused for series).
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (
        itemId: String,
        itemType: String,
        addonBaseUrl: String?,
        season: Int?,
        episode: Int?
    ) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val selectedDayFocusRequester = remember { FocusRequester() }
    val panelFocusRequester = remember { FocusRequester() }
    var isPanelFocused by remember { mutableStateOf(false) }
    // Bumped whenever the selection moves programmatically (e.g. "next release") so focus follows it.
    var dayFocusRequestId by remember { mutableIntStateOf(0) }

    // "Today" can change while the app sits in the background overnight.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }

    // Land the D-pad on the selected day when the screen opens, the month changes or a jump happens.
    LaunchedEffect(uiState.visibleMonth, dayFocusRequestId) {
        repeat(FOCUS_RETRY_ATTEMPTS) {
            if (runCatching { selectedDayFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RETRY_DELAY_MS)
        }
    }

    // BACK while browsing the day's releases returns to the calendar instead of leaving the screen.
    BackHandler(enabled = isPanelFocused) {
        runCatching { selectedDayFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .dpadRepeatThrottle()
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = NuvioTheme.spacing.xl,
                bottom = NuvioTheme.spacing.xl
            )
    ) {
        CalendarHeader(uiState = uiState, showBuiltInHeader = showBuiltInHeader)

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        if (uiState.isLibraryEmpty) {
            EmptyScreenState(
                title = stringResource(R.string.calendar_empty_library_title),
                subtitle = stringResource(R.string.calendar_empty_library_subtitle),
                icon = Icons.Default.CalendarMonth
            )
            return@Column
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f - DAY_PANEL_WIDTH_FRACTION)
                    .fillMaxHeight()
            ) {
                MonthNavigator(
                    visibleMonth = uiState.visibleMonth,
                    locale = locale,
                    onPreviousMonth = viewModel::showPreviousMonth,
                    onNextMonth = viewModel::showNextMonth,
                    onToday = viewModel::showToday
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                CalendarMonthGrid(
                    uiState = uiState,
                    firstDayOfWeek = firstDayOfWeek,
                    locale = locale,
                    selectedDayFocusRequester = selectedDayFocusRequester,
                    onDayFocused = viewModel::selectDate,
                    onDayClick = { runCatching { panelFocusRequester.requestFocus() } },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.xxl))

            CalendarDayPanel(
                date = uiState.selectedDate,
                today = uiState.today,
                events = uiState.selectedDateEvents,
                watchedEventKeys = uiState.watchedEventKeys,
                hasNextRelease = uiState.nextReleaseAfter(uiState.selectedDate) != null,
                locale = locale,
                firstCardFocusRequester = panelFocusRequester,
                exitLeftFocusRequester = selectedDayFocusRequester,
                onFocusChanged = { isPanelFocused = it },
                onEventClick = { event ->
                    onNavigateToDetail(
                        event.itemId,
                        event.itemType,
                        event.addonBaseUrl,
                        event.season,
                        event.episode
                    )
                },
                onJumpToNextRelease = {
                    viewModel.jumpToNextRelease()
                    dayFocusRequestId++
                },
                modifier = Modifier
                    .weight(DAY_PANEL_WIDTH_FRACTION)
                    .fillMaxHeight()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarHeader(uiState: CalendarUiState, showBuiltInHeader: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.calendar_title),
            style = MaterialTheme.typography.headlineMedium,
            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (uiState.isLoading) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
            }
            Text(
                text = if (uiState.isLoading) {
                    stringResource(R.string.calendar_loading_progress, uiState.loadedCount, uiState.totalCount)
                } else {
                    stringResource(R.string.calendar_subtitle_library)
                }.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextTertiary,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MonthNavigator(
    visibleMonth: YearMonth,
    locale: Locale,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit
) {
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val monthLabel = remember(visibleMonth, monthFormatter) {
        visibleMonth.format(monthFormatter).replaceFirstChar { it.titlecase(locale) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = stringResource(R.string.calendar_previous_month),
            onClick = onPreviousMonth
        )
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NuvioTheme.spacing.md)
        )
        CalendarIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.calendar_next_month),
            onClick = onNextMonth
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        CalendarPillButton(text = stringResource(R.string.calendar_today), onClick = onToday)
    }
}
