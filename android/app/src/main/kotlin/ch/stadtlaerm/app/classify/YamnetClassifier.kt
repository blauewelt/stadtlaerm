package ch.stadtlaerm.app.classify

import android.content.Context
import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import ch.stadtlaerm.dsp.classify.SoundClassifier
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YAMNet (MediaPipe float32 build) via LiteRT.
 * Verified model I/O: input `waveform_binary` float32 [15600] (0.975 s at 16 kHz, range [-1, 1]),
 * output float32 [1, 521] AudioSet scores.
 */
class YamnetClassifier(context: Context, override val labels: List<String>) : SoundClassifier {
    override val inputSampleRate = ClassifierPreprocessor.YAMNET_SAMPLE_RATE
    override val inputLength = ClassifierPreprocessor.YAMNET_INPUT_SAMPLES

    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputLength * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(521) }

    init {
        val fd = context.assets.openFd("yamnet.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        require(inShape.fold(1) { a, b -> a * b } == inputLength) { "unexpected input shape ${inShape.toList()}" }
        require(outShape.toList() == listOf(1, 521)) { "unexpected output shape ${outShape.toList()}" }
        require(labels.size == 521) { "expected 521 labels, got ${labels.size}" }
    }

    override fun classify(window: FloatArray): FloatArray {
        require(window.size == inputLength)
        input.rewind()
        input.asFloatBuffer().put(window)
        input.rewind()
        interpreter.run(input, output)
        // Clear the model input copy of the audio window right away (privacy hygiene).
        input.rewind()
        repeat(inputLength) { input.putFloat(0f) }
        return output[0].copyOf()
    }

    override fun close() = interpreter.close()
}
