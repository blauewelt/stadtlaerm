package ch.stadtlaerm.app

import android.app.Application
import android.content.Context
import ch.stadtlaerm.app.data.AppDatabase
import ch.stadtlaerm.app.data.CalibrationRepository
import ch.stadtlaerm.app.data.MeasurementRepository
import ch.stadtlaerm.app.data.SettingsStore
import ch.stadtlaerm.app.service.LiveState
import ch.stadtlaerm.dsp.classify.CategoryMapper
import kotlinx.coroutines.flow.MutableStateFlow

class StadtlaermApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Minimal manual dependency container. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val calibrations: CalibrationRepository by lazy { CalibrationRepository(db.calibrations()) }
    val measurements: MeasurementRepository by lazy { MeasurementRepository(db.measurements()) }
    val live = MutableStateFlow(LiveState())
    /** True while the calibration screen is using the microphone (blocks starting a measurement). */
    val calibrationActive = MutableStateFlow(false)

    val labels: List<String> by lazy {
        CategoryMapper.parseLabels(appContext.assets.open("yamnet_labels.txt").bufferedReader().readText())
    }

    /** Category mapping; constructing it validates every name against the label file. */
    val categoryMapper: CategoryMapper by lazy {
        CategoryMapper.fromJson(appContext.assets.open("categories.json").bufferedReader().readText(), labels)
    }
}

val Context.container: AppContainer get() = (applicationContext as StadtlaermApp).container
