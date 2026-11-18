package ng.ainow.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Keeps listening in the background. Version 0.1 uses Android's own speech recogniser and
 * looks for the wake name in the text. Planned: replace this with an on-device wake-word
 * model (openWakeWord) so it waits using almost no battery.
 */
class VoiceService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private var rec: SpeechRecognizer? = null
    private var listening = false
    private var sttFallback = false
    private var muted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startInForeground()
        speaker = Speaker(this) { maybeListen() }
        brain = Brain(this, prefs) { text -> speaker.speak(text, brain.pack.tts) }
        brain.onSettled = { maybeListen() }
        brain.onQuit = {
            main.postDelayed({ stopSelf() }, 1500)
        }
        running = true
        quiet(true)
        maybeListen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
        speaker.shutdown()
        quiet(false)
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "nova")
            .setContentTitle("Nova is listening")
            .setContentText("Say \"" + prefs.wakeName + "\" then a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    /** Silences the system sounds that the recogniser makes each time it starts. */
    private fun quiet(on: Boolean) {
        if (!prefs.quietEars && on) return
        if (on == muted) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        for (s in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            try {
                am.adjustStreamVolume(s, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        muted = on
    }

    private fun maybeListen() {
        if (!running || listening || speaker.busy || brain.busy) return
        listen()
    }

    private fun listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speaker.speak("Speech recognition is not available on this phone.", "en-US")
            main.postDelayed({ stopSelf() }, 3000)
            return
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        val tag = if (sttFallback) "en-NG" else brain.pack.stt
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        try {
            rec?.startListening(i)
        } catch (e: Exception) {
            listening = false
            resetRecognizer()
            retryIn(1000)
        }
    }

    private fun resetRecognizer() {
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
    }

    private fun retryIn(ms: Long) {
        main.postDelayed({ maybeListen() }, ms)
    }

    // Seen at the start of a new language: if the phone has no recogniser for it, fall back to English.
    private fun languageMissing() {
        if (!sttFallback && brain.pack.stt != "en-NG") {
            sttFallback = true
            speaker.speak(
                "Your phone cannot listen in " + brain.pack.name + " yet. I will listen in English.",
                "en-NG"
            )
        }
        retryIn(300)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            when (error) {
                9 -> stopSelf()                          // permissions missing
                12, 13 -> languageMissing()              // language not supported / not available
                5, 8 -> { resetRecognizer(); retryIn(800) } // client error / recogniser busy
                2, 4 -> retryIn(2000)                    // network / server
                else -> retryIn(150)                     // 6 = silence, 7 = no match: just listen again
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            if (list.isNotEmpty()) brain.heard(list)
            maybeListen()
        }
    }
}
