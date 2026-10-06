package ch.stadtlaerm.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.stadtlaerm.app.R
import ch.stadtlaerm.app.audio.AudioCapture
import ch.stadtlaerm.app.audio.AudioSourceSelector
import ch.stadtlaerm.app.classify.YamnetClassifier
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.ui.MainActivity
import ch.stadtlaerm.dsp.EngineConfig
import ch.stadtlaerm.dsp.LafTick
import ch.stadtlaerm.dsp.MeasurementEngine
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.SecondResult
import ch.stadtlaerm.dsp.classify.CategoryDecision
import ch.stadtlaerm.dsp.classify.ClassifierFrame
import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import ch.stadtlaerm.dsp.classify.LabelScore
import ch.stadtlaerm.dsp.classify.SoundClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live values shown on the Measure screen. Only aggregates; never audio. */
data class LiveState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val startedAtMs: Long = 0,
    val audioSource: String? = null,
    val encoding: String? = null,
    val effects: List<String> = emptyList(),
    val calibrated: Boolean = false,
    val calibrationId: Long? = null,
    val calibrationOffsetDb: Double? = null,
    val lafDb: Double? = null,
    val laeq60sDb: Double? = null,
    val backgroundDb: Double? = null,
    val dominantCategory: String? = null,
    val dominantScore: Float = 0f,
    val topLabels: List<LabelScore> = emptyList(),
    val lastMinute: MinuteRecord? = null,
    val classifierEnabled: Boolean = false,
    val error: String? = null,
)

/**
 * Foreground service (type "microphone") that runs the measurement so it continues overnight with
 * the screen off. Holds a partial wake lock while measuring (configurable): see README → Battery.
 */
class MeasurementService : Service() {

    companion object {
        const val ACTION_START = "ch.stadtlaerm.app.START"
        const val ACTION_STOP = "ch.stadtlaerm.app.STOP"
        private const val CHANNEL = "measurement"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeasurementService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeasurementService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: AudioCapture? = null
    private var engine: MeasurementEngine? = null
    private var classifier: SoundClassifier? = null
    private var inference: ExecutorService? = null
    private val inferenceBusy = AtomicBoolean(false)
    private val frames = ConcurrentLinkedQueue<ClassifierFrame>()
    private var classifierWindow = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationMs = 0L
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMeasurement(); return START_NOT_STICKY }
            else -> startMeasurement()
        }
        return START_NOT_STICKY
    }

    private fun startMeasurement() {
        val live = container.live
        if (live.value.running || live.value.starting) return
        createChannel()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Messung startet …"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            live.value = LiveState(error = "Vordergrunddienst nicht erlaubt: ${e.message}")
            stopSelf(); return
        }
        if (!hasMic) {
            live.value = LiveState(error = "Mikrofon-Berechtigung fehlt")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(); return
        }
        live.value = LiveState(starting = true)
        stopping = false
        scope.launch {
            try {
                val c = container
                val settings = c.settings.state.value
                val source = AudioSourceSelector.select(this@MeasurementService)
                val cal = c.calibrations.active(source)
                val mapper = if (settings.classifierEnabled) c.categoryMapper else null
                if (settings.classifierEnabled) {
                    classifier = YamnetClassifier(this@MeasurementService, c.labels)
                    inference = Executors.newSingleThreadExecutor { r -> Thread(r, "stadtlaerm-classifier") }
                }
                val config = EngineConfig(
                    calibrationOffsetDb = cal.offsetDb,
                    calibrated = cal.calibrated,
                    calibrationId = cal.id,
                    audioSource = source,
                    eventThresholdDb = settings.eventThresholdDb,
                    classifierEnabled = settings.classifierEnabled,
                    classifierIntervalSeconds = settings.classifierIntervalSeconds.toDouble(),
                    zone = ZoneId.systemDefault(),
                )
                val eng = MeasurementEngine(config, System.currentTimeMillis(), mapper, EngineListener())
                engine = eng
                if (settings.wakeLock) acquireWakeLock()
                val cap = AudioCapture(this@MeasurementService, ::onBlock) { msg ->
                    container.live.value = container.live.value.copy(error = "Aufnahmefehler: $msg")
                    // Called on the capture thread: stop from another thread so join() can complete.
                    scope.launch { stopMeasurement() }
                }
                val info = cap.start(source)
                capture = cap
                live.value = LiveState(
                    running = true, startedAtMs = System.currentTimeMillis(), audioSource = info.source,
                    encoding = info.encoding, effects = info.effects, calibrated = cal.calibrated,
                    calibrationId = cal.id, calibrationOffsetDb = cal.offsetDb,
                    classifierEnabled = settings.classifierEnabled,
                )
            } catch (e: Exception) {
                live.value = LiveState(error = "Start fehlgeschlagen: ${e.message}")
                stopMeasurement()
            }
        }
    }

    /** Runs on the capture thread for every 125 ms block. */
    private fun onBlock(samples: FloatArray, count: Int) {
        val eng = engine ?: return
        eng.process(samples, count)
        while (true) eng.onClassifierFrame(frames.poll() ?: break)
        val cls = classifier ?: return
        val exec = inference ?: return
        if (eng.classifierDue() && inferenceBusy.compareAndSet(false, true)) {
            val end = eng.copyClassifierWindow(classifierWindow)
            if (end < 0) { inferenceBusy.set(false); return }
            val window = classifierWindow
            val normalize = container.settings.state.value.classifierNormalize
            exec.execute {
                try {
                    if (normalize) ClassifierPreprocessor.normalize(window)
                    val scores = cls.classify(window)
                    frames.add(ClassifierFrame(end, eng.classifierWindowLength48k, scores))
                } catch (_: Exception) {
                    // A failed inference only loses one classification frame.
                } finally {
                    window.fill(0f)
                    inferenceBusy.set(false)
                }
            }
        }
    }

    private inner class EngineListener : MeasurementEngine.Listener {
        override fun onTick(tick: LafTick) {
            val l = container.live
            l.value = l.value.copy(lafDb = tick.lafDb)
        }

        override fun onSecond(second: SecondResult) {
            val l = container.live
            l.value = l.value.copy(
                laeq60sDb = second.laeqRunning60sDb,
                backgroundDb = second.backgroundDb.takeUnless { it.isNaN() },
            )
            val now = System.currentTimeMillis()
            if (now - lastNotificationMs >= 2000) {
                lastNotificationMs = now
                val text = String.format(Locale.GERMANY, "LAeq (1 min): %.1f dB(A)%s", second.laeqRunning60sDb,
                    if (l.value.calibrated) "" else " · unkalibriert")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }

        override fun onMinute(minute: MinuteRecord) {
            val l = container.live
            l.value = l.value.copy(lastMinute = minute)
            scope.launch { container.measurements.insert(minute) }
        }

        override fun onEvent(event: NoiseEvent) {
            scope.launch { container.measurements.insert(event) }
        }

        override fun onClassification(endEpochMs: Long, decision: CategoryDecision, top: List<LabelScore>) {
            val l = container.live
            l.value = l.value.copy(
                dominantCategory = decision.dominant, dominantScore = decision.dominantScore, topLabels = top,
            )
        }
    }

    @Synchronized
    private fun stopMeasurement() {
        if (stopping) return
        stopping = true
        capture?.stop()
        capture = null
        // The capture thread has ended: finish the engine (partial minute, open event) here.
        engine?.let { eng ->
            while (true) eng.onClassifierFrame(frames.poll() ?: break)
            eng.stop()
        }
        engine = null
        inference?.shutdown()
        inference = null
        classifier?.let { c -> scope.launch { try { c.close() } catch (_: Exception) {} } }
        classifier = null
        frames.clear()
        classifierWindow = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
        releaseWakeLock()
        val err = container.live.value.error
        container.live.value = LiveState(error = err, lastMinute = container.live.value.lastMinute)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopMeasurement()
        // Let pending DB inserts finish; the scope is cancelled after a grace period.
        scope.launch { kotlinx.coroutines.delay(5000); scope.cancel() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stadtlaerm::Measurement").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // safety timeout; released when the measurement stops
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_level)
            .setContentTitle("Stadtlärm misst")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
