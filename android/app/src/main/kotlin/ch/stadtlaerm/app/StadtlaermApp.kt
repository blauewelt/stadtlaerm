package ch.stadtlaerm.app

import android.app.Application
import android.content.Context
import ch.stadtlaerm.app.data.AppDatabase
import ch.stadtlaerm.app.data.CalibrationRepository
import ch.stadtlaerm.app.data.MeasurementRepository
import ch.stadtlaerm.app.data.Recalibrator
import ch.stadtlaerm.app.data.SettingsStore
import ch.stadtlaerm.app.service.LiveState
import ch.stadtlaerm.app.update.UpdateReminderStore
import ch.stadtlaerm.app.upload.UploadModule
import ch.stadtlaerm.dsp.classify.CategoryMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class StadtlaermApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // «Messwerte teilen»: keeps the upload schedule in line with the setting and uploads after
        // a measurement stops. Does nothing (no network) while sharing is off, and nothing at all
        // in the Labor build.
        // Started off the main thread (the token is read through the Android Keystore).
        if (BuildConfig.UPLOAD_AVAILABLE) {
            container.appScope.launch { container.upload.start(container.appScope, container.live, container.recalibrator) }
        }
    }
}

/** Minimal manual dependency container. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val calibrations: CalibrationRepository by lazy { CalibrationRepository(db.calibrations()) }
    val measurements: MeasurementRepository by lazy { MeasurementRepository(db.measurements()) }
    val updateReminder: UpdateReminderStore by lazy { UpdateReminderStore(appContext) }
    /** For work that must outlive a screen (re-evaluating old measurements). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recalibrator: Recalibrator by lazy { Recalibrator(db, appScope) }
    val live = MutableStateFlow(LiveState())
    /** The opt-in upload «Messwerte teilen» (server/DESIGN.md §4); the app's only network use. */
    val upload: UploadModule by lazy { UploadModule(appContext, db, calibrations) }
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
