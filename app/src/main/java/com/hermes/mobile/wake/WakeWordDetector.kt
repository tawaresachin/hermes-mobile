package com.hermes.mobile.wake

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * Streaming "Hey Hermes" detector — a direct port of pyopen-wakeword's
 * OpenWakeWordFeatures.process_streaming + OpenWakeWord.process_streaming.
 *
 * All buffers are bounded and allocated once; processFrame performs no heap
 * allocations. Interpreters are created once and released by [close].
 */
class WakeWordDetector(
    assets: AssetManager,
    onFire: () -> Unit = {},
    sensitivity: Float = WakeWordStateMachine.DEFAULT_SENSITIVITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_CHUNK = 1_280 // 80 ms
        private const val MEL_SAMPLES = 1_760
        private const val NUM_MELS = 32
        private const val MAX_MELS = 970 // 10 s * 97 mel rows/s
        private const val EMB_FEATURES = 76
        private const val EMB_STEP = 8
        private const val MAX_EMB = 80 // 10 s * 8 windows/s
        private const val WW_FEATURES = 96
        private const val WW_INPUT_WINDOWS = 16
        private const val MAX_SAMPLES = 10 * SAMPLE_RATE
        private const val AUTOFILL_SAMPLES = 8 * SAMPLE_RATE
    }

    /** Direct native-order TFLite buffer plus a reusable float view. */
    private class TensorIo(elements: Int) {
        val bytes: ByteBuffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())
        private val floats: FloatBuffer = bytes.asFloatBuffer()

        fun write(src: FloatArray) {
            bytes.clear()
            floats.clear()
            floats.put(src)
        }

        fun read(dst: FloatArray) {
            bytes.clear()
            floats.clear()
            floats.get(dst)
        }
    }

    private val state = WakeWordStateMachine(sensitivity)
    private val fire: () -> Unit = onFire

    private val mel: Interpreter
    private val emb: Interpreter
    private val ww: Interpreter

    private val melIn: TensorIo
    private val melOut: TensorIo
    private val embIn: TensorIo
    private val embOut: TensorIo
    private val wwIn: TensorIo
    private val wwOut: TensorIo

    private val melFramesPerRun: Int
    private val audio = FloatArray(MAX_SAMPLES)
    private val melInput = FloatArray(MEL_SAMPLES)
    private val melOutput: FloatArray
    private val mels = FloatArray(MAX_MELS * NUM_MELS)
    private val embInput = FloatArray(EMB_FEATURES * NUM_MELS)
    private val embOutput = FloatArray(WW_FEATURES)
    private val wwEmbeddings = FloatArray(MAX_EMB * WW_FEATURES)
    private val wwInput = FloatArray(WW_INPUT_WINDOWS * WW_FEATURES)
    private val wwOutput = FloatArray(1)

    private var newAudioSamples = AUTOFILL_SAMPLES
    private var newMels = 0
    private var newEmbeddings = 0

    /** Score from the most recent inference window. */
    var lastScore: Float = 0f
        private set

    /** Highest score since the last [reset]. */
    var maxScore: Float = 0f
        private set

    init {
        val options = Interpreter.Options().setNumThreads(1).setUseNNAPI(false)
        mel = Interpreter(loadModel(assets, "wakeword/melspectrogram.tflite"), options)
        emb = Interpreter(loadModel(assets, "wakeword/embedding_model.tflite"), options)
        ww = Interpreter(loadModel(assets, "wakeword/hey_hermes.tflite"), options)

        val melShape = mel.getOutputTensor(0).shape()
        require(melShape.size == 4 && melShape[0] == 1 && melShape[1] == 1 && melShape[3] == NUM_MELS) {
            "Unexpected melspec output shape: ${melShape.contentToString()}"
        }
        melFramesPerRun = melShape[2]
        melOutput = FloatArray(melShape[0] * melShape[1] * melShape[2] * melShape[3])
        melIn = TensorIo(1 * MEL_SAMPLES)
        melOut = TensorIo(melOutput.size)

        require(emb.getInputTensor(0).shape().contentEquals(intArrayOf(1, EMB_FEATURES, NUM_MELS, 1))) {
            "Unexpected embedding input shape: ${emb.getInputTensor(0).shape().contentToString()}"
        }
        require(emb.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 1, 1, WW_FEATURES))) {
            "Unexpected embedding output shape: ${emb.getOutputTensor(0).shape().contentToString()}"
        }
        require(ww.getInputTensor(0).shape().contentEquals(intArrayOf(1, WW_INPUT_WINDOWS, WW_FEATURES))) {
            "Unexpected classifier input shape: ${ww.getInputTensor(0).shape().contentToString()}"
        }
        require(ww.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 1))) {
            "Unexpected classifier output shape: ${ww.getOutputTensor(0).shape().contentToString()}"
        }
        embIn = TensorIo(EMB_FEATURES * NUM_MELS)
        embOut = TensorIo(WW_FEATURES)
        wwIn = TensorIo(WW_INPUT_WINDOWS * WW_FEATURES)
        wwOut = TensorIo(1)
    }

    /** Feed one 16 kHz mono PCM16 frame (1280 samples / 80 ms). */
    fun processFrame(frame: ShortArray): Boolean {
        require(frame.size == SAMPLES_PER_CHUNK) { "frame must be $SAMPLES_PER_CHUNK samples" }

        // Shift the audio window left, append the new frame at the end.
        System.arraycopy(audio, frame.size, audio, 0, audio.size - frame.size)
        var i = 0
        while (i < frame.size) {
            audio[audio.size - frame.size + i] = frame[i].toFloat()
            i++
        }
        newAudioSamples = min(MAX_SAMPLES, newAudioSamples + frame.size)

        var fired = false
        while (newAudioSamples >= MEL_SAMPLES) {
            System.arraycopy(audio, MAX_SAMPLES - newAudioSamples, melInput, 0, MEL_SAMPLES)
            newAudioSamples = max(0, newAudioSamples - SAMPLES_PER_CHUNK)

            melIn.write(melInput)
            mel.run(melIn.bytes, melOut.bytes)
            melOut.read(melOutput)

            // openWakeWord: (mels / 10) + 2 before the embedding model.
            i = 0
            while (i < melOutput.size) {
                melOutput[i] = melOutput[i] / 10f + 2f
                i++
            }

            val melShift = melFramesPerRun * NUM_MELS
            System.arraycopy(mels, melShift, mels, 0, mels.size - melShift)
            System.arraycopy(melOutput, 0, mels, mels.size - melShift, melShift)
            newMels = min(MAX_MELS, newMels + melFramesPerRun)

            while (newMels >= EMB_FEATURES) {
                Arrays.fill(embInput, 0f)
                System.arraycopy(mels, (MAX_MELS - newMels) * NUM_MELS, embInput, 0, EMB_FEATURES * NUM_MELS)
                newMels = max(0, newMels - EMB_STEP)

                embIn.write(embInput)
                emb.run(embIn.bytes, embOut.bytes)
                embOut.read(embOutput)

                // Embedding output is one 96-dim window per call.
                System.arraycopy(wwEmbeddings, WW_FEATURES, wwEmbeddings, 0, wwEmbeddings.size - WW_FEATURES)
                System.arraycopy(embOutput, 0, wwEmbeddings, wwEmbeddings.size - WW_FEATURES, WW_FEATURES)
                newEmbeddings = min(MAX_EMB, newEmbeddings + 1)

                while (newEmbeddings >= WW_INPUT_WINDOWS) {
                    System.arraycopy(
                        wwEmbeddings,
                        (MAX_EMB - newEmbeddings) * WW_FEATURES,
                        wwInput,
                        0,
                        WW_INPUT_WINDOWS * WW_FEATURES,
                    )
                    newEmbeddings = max(0, newEmbeddings - 1)

                    wwIn.write(wwInput)
                    ww.run(wwIn.bytes, wwOut.bytes)
                    wwOut.read(wwOutput)

                    val score = wwOutput[0]
                    lastScore = score
                    if (score > maxScore) maxScore = score
                    if (state.processScore(score, clock())) {
                        fired = true
                        fire()
                    }
                }
            }
        }
        return fired
    }

    /** Clears feature buffers, scores and confirmation state between sessions. */
    fun reset() {
        Arrays.fill(audio, 0f)
        Arrays.fill(mels, 0f)
        Arrays.fill(wwEmbeddings, 0f)
        newAudioSamples = AUTOFILL_SAMPLES
        newMels = 0
        newEmbeddings = 0
        lastScore = 0f
        maxScore = 0f
        state.reset()
    }

    fun close() {
        ww.close()
        emb.close()
        mel.close()
    }

    private fun loadModel(assets: AssetManager, path: String): ByteBuffer =
        assets.open(path).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
}
