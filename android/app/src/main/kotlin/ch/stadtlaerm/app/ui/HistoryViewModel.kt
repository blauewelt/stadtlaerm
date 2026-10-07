package ch.stadtlaerm.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.app.data.Mappers.toEvent
import ch.stadtlaerm.app.data.Mappers.toRecord
import ch.stadtlaerm.chart.ChartData
import ch.stadtlaerm.chart.RangeMode
import ch.stadtlaerm.chart.TimeWindow
import ch.stadtlaerm.chart.Windows
import ch.stadtlaerm.dsp.NightSummarizer
import ch.stadtlaerm.dsp.NightSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * State of the «Nächte» screen: the chart window, its data (Room flows, re-queried on navigation;
 * the previous window's data stays visible until the new one arrives) and the night list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.measurements
    val zone: ZoneId = ZoneId.systemDefault()
    val windows = Windows(zone)

    val settings: StateFlow<AppSettings> = c.settings.state

    private val _window = MutableStateFlow<TimeWindow?>(null)
    val window: StateFlow<TimeWindow?> = _window

    private val floor = settings.map { it.eventMinLevelDb }.distinctUntilChanged()

    /** Night summaries, newest first; events below the current floor are not counted. */
    val nights: StateFlow<List<NightSummary>?> =
        combine(repo.minutesFlow(), repo.eventsFlow(), floor) { m, e, f ->
            NightSummarizer.summarize(m.map { it.toRecord() }, e.map { it.toEvent() }, zone, eventMinLevelDb = f)
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Data of the current window. Keeps its last value while a new window loads. */
    val data: StateFlow<ChartData?> = _window.filterNotNull().flatMapLatest { w ->
        combine(
            repo.minutesBetween(w.startMs, w.endMs),
            repo.eventsBetween(w.startMs, w.endMs),
            repo.lastValidEndBefore(w.startMs),
            repo.firstValidStartAfter(w.endMs),
        ) { m, e, prev, next ->
            ChartData(w, m.map { it.toRecord() }, e.map { it.toEvent() }, System.currentTimeMillis(), prev, next)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Opening window: the running night, else the most recent night with data.
        viewModelScope.launch {
            val list = nights.filterNotNull().first()
            if (_window.value == null) _window.value = windows.defaultWindow(System.currentTimeMillis(), list.firstOrNull()?.nightOf)
        }
    }

    fun setMode(mode: RangeMode) {
        val w = _window.value ?: return
        _window.value = windows.switchMode(w, mode, System.currentTimeMillis())
    }

    fun shift(delta: Int) {
        val w = _window.value ?: return
        if (delta > 0 && !windows.canGoNext(w, System.currentTimeMillis())) return
        _window.value = windows.shift(w, delta)
    }

    fun showNight(night: LocalDate) {
        _window.value = windows.of(RangeMode.NIGHT, night)
    }

    fun setHighlight(category: String) {
        c.settings.update { it.copy(chartHighlightCategory = category) }
    }
}
