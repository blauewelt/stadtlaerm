package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-check of the Kotlin classifier input pipeline against the Python/LiteRT reference.
 *
 * Runs only when STADTLAERM_PREPROC_DIR is set (see tools/verify_yamnet.py): reads a 48 kHz
 * float32 test signal, pushes it through the real [MeasurementEngine] (decimator + ring buffer),
 * takes the latest classifier window exactly like the app does and writes it (raw and
 * normalised) back for the Python script to compare and to run through yamnet.tflite.
 */
class ClassifierPipelineExportTest {

    private fun readF32(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / 4) { bb.getFloat() }
    }

    private fun writeF32(f: File, x: FloatArray) {
        val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { bb.putFloat(it) }
        f.writeBytes(bb.array())
    }

    @Test
    fun exportWindowForPythonReference() {
        val dir = System.getProperty("stadtlaerm.preprocDir")
        assumeTrue("STADTLAERM_PREPROC_DIR not set; skipping Python cross-check export", dir != null)
        val d = File(dir!!)
        val input = readF32(File(d, "input48k.f32"))
        val engine = MeasurementEngine(EngineConfig(), 0L, TestSignals.mapper(), object : MeasurementEngine.Listener {})
        TestSignals.feed(engine, input, block = 4800)
        val window = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
        val end = engine.copyClassifierWindow(window)
        assertTrue(end > 0)
        assertEquals(input.size.toLong(), end)
        writeF32(File(d, "kotlin_window_raw.f32"), window)
        val gain = ClassifierPreprocessor.normalize(window)
        writeF32(File(d, "kotlin_window_norm.f32"), window)
        File(d, "kotlin_meta.json").writeText("{\"endSample\": $end, \"gainDb\": $gain, \"length\": ${window.size}}")
    }
}
