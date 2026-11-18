package ng.ainow.nova

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech wrapper. Falls back to a Nigerian/US English voice if a language has no voice installed. */
class Speaker(ctx: Context, private val onIdle: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<Pair<String, String>>()
    private var active = 0
    private val main = Handler(Looper.getMainLooper())

    val busy: Boolean get() = active > 0 || waiting.isNotEmpty()

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                val t = tts
                if (ready && t != null) {
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { finished() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { finished() }
                        override fun onError(utteranceId: String?, errorCode: Int) { finished() }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { finished() }
                    })
                    val queued = ArrayList(waiting)
                    waiting.clear()
                    for ((text, lang) in queued) doSpeak(text, lang)
                }
                if (!busy) onIdle()
            }
        }
    }

    private fun finished() {
        main.post {
            active = maxOf(0, active - 1)
            if (active == 0 && waiting.isEmpty()) onIdle()
        }
    }

    fun speak(text: String, langTag: String) {
        if (text.isBlank()) return
        if (!ready) {
            waiting.add(text to langTag)
            return
        }
        doSpeak(text, langTag)
    }

    private fun doSpeak(text: String, langTag: String) {
        val t = tts ?: return
        setLang(t, langTag)
        for (chunk in chunk(text, 800)) {
            active++
            val r = t.speak(chunk, TextToSpeech.QUEUE_ADD, null, "nova-" + System.nanoTime())
            if (r != TextToSpeech.SUCCESS) active = maxOf(0, active - 1)
        }
    }

    private fun setLang(t: TextToSpeech, tag: String) {
        val r = t.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            val r2 = t.setLanguage(Locale("en", "NG"))
            if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.US)
            }
        }
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
