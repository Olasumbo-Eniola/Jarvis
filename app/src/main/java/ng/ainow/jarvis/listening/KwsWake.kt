package ng.ainow.jarvis.listening

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Wake word by keyword spotting (sherpa-onnx, English GigaSpeech model, Apache-2.0, in assets/kws).
 * Unlike the "Hey Jarvis" model, this is a tiny speech recogniser that listens for any word in
 * assets/kws/keywords.txt, so it wakes on both "Jarvis" and "Hey Jarvis".
 *
 * To add or change a wake word, add a line to keywords.txt. Each word must be spelled in the
 * model's BPE pieces (e.g. JARVIS = "▁JA R VI S"); make them with the model's bpe.model and
 * sentencepiece, or sherpa-onnx's text2token tool.
 *
 * [onWake] is called on the main thread with the keyword heard (e.g. "JARVIS").
 */
class KwsWake(ctx: Context, private val onWake: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val spotter: KeywordSpotter

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    init {
        val config = KeywordSpotterConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = "kws/encoder.int8.onnx"
                    decoder = "kws/decoder.int8.onnx"
                    joiner = "kws/joiner.int8.onnx"
                }
                tokens = "kws/tokens.txt"
                modelType = "zipformer2"
                numThreads = 1
                provider = "cpu"
            }
            keywordsFile = "kws/keywords.txt"
            // Defaults from sherpa-onnx. Lower threshold = easier to wake (and more false alarms).
            keywordsScore = 1.5f
            keywordsThreshold = 0.25f
        }
        spotter = KeywordSpotter(ctx.assets, config)
    }

    /** Opens the microphone and starts listening. Throws if the microphone can't be opened. */
    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before calling this
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 2 * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("microphone could not be opened")
        }
        recorder = rec
        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "jarvis-kws").also { it.start() }
    }

    private fun loop(rec: AudioRecord) {
        val stream = spotter.createStream("")
        val pcm = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                for (i in 0 until n) samples[i] = pcm[i] / 32768f
                stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), SAMPLE_RATE)
                while (running && spotter.isReady(stream)) spotter.decode(stream)
                val word = spotter.getResult(stream).keyword
                if (word.isNotBlank()) {
                    spotter.reset(stream) // start fresh so one "Jarvis" fires once
                    main.post { if (running) onWake(word) }
                }
            }
        } catch (e: Throwable) {
            // Mic or model error: stop quietly; the service falls back on the next start.
        } finally {
            stream.release()
        }
    }

    /** Stops listening and frees the microphone, so the speech recogniser can use it. */
    fun stop() {
        running = false
        try { recorder?.stop() } catch (e: Throwable) {}
        try { thread?.join(500) } catch (e: Throwable) {}
        try { recorder?.release() } catch (e: Throwable) {}
        recorder = null
        thread = null
    }

    fun release() {
        stop()
        try { spotter.release() } catch (e: Throwable) {}
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 100 ms
    }
}
