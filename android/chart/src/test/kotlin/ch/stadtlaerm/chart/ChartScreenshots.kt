package ch.stadtlaerm.chart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.File
import java.time.LocalDate

/**
 * JVM renders of the history screen (Paparazzi, no device): `./gradlew :chart:testDebugUnitTest`.
 * PNGs go to the directory in STADTLAERM_SCREENSHOT_DIR (default chart/build/screenshots). They are
 * for looking at, not golden images: nothing is compared.
 *
 * Renders with the owner's real data run only when STADTLAERM_REAL_DATA is set.
 */
class ChartScreenshots {
    /**
     * Writes each snapshot as <name>.png. java.awt is not on the Android unit-test compile
     * classpath (android.jar hides it), so the frame handler and ImageIO are reached reflectively.
     */
    private class PngWriter(private val dir: File) : SnapshotHandler {
        init { dir.mkdirs() }
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
            val file = File(dir, "${snapshot.name ?: snapshot.testName.methodName}.png")
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val write = imageIo.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, method, args ->
                if (method.name == "handle") write.invoke(null, args!![0], "png", file)
                null
            } as SnapshotHandler.FrameHandler
        }
        override fun close() {}
    }

    @get:Rule
    val paparazzi = Paparazzi(
        // Pixel 7-like: 412 × 915 dp at 2.625 (420 dpi).
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1082, screenHeight = 2402, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots")),
        useDeviceResolution = true,
    )

    private val zone = SyntheticData.zone

    private object NoActions : HistoryActions {
        override fun onMode(mode: RangeMode) {}
        override fun onShift(delta: Int) {}
        override fun onHighlight(category: String) {}
        override fun onSelect(selection: Selection?) {}
    }

    @Composable
    private fun Screen(data: ChartData, dark: Boolean, highlight: String = "loud_vehicle", selection: Selection? = null, floor: Double = 30.0) {
        StadtlaermTheme(dark = dark) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Nächte", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
                HistorySection(
                    window = data.window, data = data, zone = zone, highlight = highlight, eventFloorDb = floor,
                    canGoNext = Windows(zone).canGoNext(data.window, data.nowMs), selection = selection, actions = NoActions,
                )
            }
        }
    }

    private fun snap(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) { content() }

    @Test fun nachtSynthetic() {
        val d = SyntheticData.fridayNight()
        snap("nacht_synth_light") { Screen(d, dark = false) }
    }

    @Test fun nachtSyntheticDark() {
        val d = SyntheticData.fridayNight()
        snap("nacht_synth_dark") { Screen(d, dark = true) }
    }

    @Test fun tagSynthetic() {
        snap("tag_synth_light") { Screen(SyntheticData.dayData(), dark = false) }
    }

    @Test fun wocheSynthetic() {
        snap("woche_synth_light") { Screen(SyntheticData.weekData(), dark = false) }
    }

    @Test fun wocheSyntheticDark() {
        snap("woche_synth_dark") { Screen(SyntheticData.weekData(), dark = true) }
    }

    @Test fun empty() {
        snap("empty_light") { Screen(SyntheticData.emptyNight(), dark = false) }
    }

    @Test fun eventTooltip() {
        val d = SyntheticData.fridayNight()
        val loudest = d.events.maxBy { it.lafMaxDb }
        snap("tooltip_event_light") { Screen(d, dark = false, selection = Selection.Event(loudest, highlighted = true)) }
    }

    @Test fun minuteTooltipNearRightEdge() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.minutePoint(d.minutes.last { it.startEpochMs < d.window.endMs - 10 * 60_000 })
        snap("tooltip_minute_dark") { Screen(d, dark = true, selection = Selection.Point(m), highlight = "voices") }
    }

    // ---- The owner's first real night (skipped unless STADTLAERM_REAL_DATA is set) ------------

    private fun real(mode: RangeMode, date: LocalDate): ChartData {
        val dir = CsvFixtures.realDataDir()
        assumeTrue("STADTLAERM_REAL_DATA not set", dir != null)
        val w = Windows(zone).of(mode, date)
        return CsvFixtures.realData(dir!!, w, nowMs = SyntheticData.ms(LocalDate.of(2026, 10, 7).atTime(9, 0)))
    }

    @Test fun realNight() {
        val d = real(RangeMode.NIGHT, LocalDate.of(2026, 10, 6))
        snap("real_nacht_light") { Screen(d, dark = false) }
    }

    @Test fun realNightDark() {
        val d = real(RangeMode.NIGHT, LocalDate.of(2026, 10, 6))
        snap("real_nacht_dark") { Screen(d, dark = true) }
    }

    @Test fun realDay() {
        val d = real(RangeMode.DAY, LocalDate.of(2026, 10, 6))
        snap("real_tag_6_light") { Screen(d, dark = false, highlight = "voices") }
    }

    @Test fun realWeek() {
        val d = real(RangeMode.WEEK, LocalDate.of(2026, 10, 6))
        snap("real_woche_dark") { Screen(d, dark = true) }
    }
}
