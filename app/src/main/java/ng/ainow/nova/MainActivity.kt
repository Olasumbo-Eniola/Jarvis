package ng.ainow.nova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup screen. Everything is a plain labelled control so Jieshuo/TalkBack read it well. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var langBtn: Button
    private lateinit var nameField: EditText
    private var askedOnce = false
    private lateinit var keyField: EditText
    private lateinit var sensField: EditText
    private lateinit var consent: CheckBox
    private lateinit var confirmAll: CheckBox
    private lateinit var quiet: CheckBox
    private lateinit var startBtn: Button
    private lateinit var codes: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        codes = Packs.codes(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        col.setPadding(pad, pad, pad, pad)

        fun label(text: String): TextView {
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 16f
            return tv
        }
        fun button(text: String, click: () -> Unit): Button {
            val b = Button(this)
            b.text = text
            b.setOnClickListener { click() }
            return b
        }

        status = label("")
        col.addView(status)

        col.addView(button("Step 1: turn on Jarvis in accessibility settings") { openAccessibility() })
        col.addView(button("Step 2: allow microphone, contacts and notifications") { askPermissions() })

        langBtn = button("") {
            val cur = codes.indexOf(prefs.lang).coerceAtLeast(0)
            prefs.lang = codes[(cur + 1) % codes.size]
            refresh()
        }
        col.addView(langBtn)

        col.addView(label("Wake phrase: Hey Jarvis. Say it clearly, wait for the beep, then give your command. Best in a quiet place, with the phone close to your mouth."))
        col.addView(label("Wake sensitivity: a number between 0.02 and 0.9. Lower means easier to wake. Try 0.08 if it does not react."))
        sensField = EditText(this)
        sensField.setText(prefs.wakeThreshold.toString())
        sensField.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        sensField.contentDescription = "Wake sensitivity"
        col.addView(sensField)

        col.addView(label("Name Jarvis also answers to inside a sentence (used if the wake model cannot start)"))
        nameField = EditText(this)
        nameField.setText(prefs.wakeName)
        nameField.contentDescription = "Name to call the assistant by"
        col.addView(nameField)

        col.addView(label("Gemini API key (optional, only for chat and unusual sentences)"))
        keyField = EditText(this)
        keyField.setText(prefs.aiKey)
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        keyField.contentDescription = "Gemini API key"
        col.addView(keyField)

        consent = CheckBox(this)
        consent.text = "Allow sending button names and icon pictures to Google Gemini"
        consent.isChecked = prefs.aiConsent
        col.addView(consent)

        col.addView(label("Safety: Jarvis always asks \"are you sure?\" before pressing a button that sends, posts, submits, pays, buys or deletes something, like sending a message or submitting a form. Opening apps and moving around never asks."))
        confirmAll = CheckBox(this)
        confirmAll.text = "Ask before every tap, even simple ones like opening a chat or a menu"
        confirmAll.isChecked = prefs.confirmAll
        col.addView(confirmAll)

        quiet = CheckBox(this)
        quiet.text = "Mute system beeps while listening"
        quiet.isChecked = prefs.quietEars
        col.addView(quiet)

        col.addView(button("Save settings") { save(); status.text = "Saved." })
        col.addView(button("Test AI") { testAi() })

        startBtn = button("") {
            save()
            if (VoiceService.running) {
                prefs.userStopped = true
                stopService(Intent(this, VoiceService::class.java))
            } else {
                prefs.userStopped = false
                startForegroundService(Intent(this, VoiceService::class.java))
            }
            status.postDelayed({ refresh() }, 600)
        }
        col.addView(startBtn)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        autoStart()
        refresh()
    }

    /**
     * Nobody should have to press Start. Opening the app asks for the microphone once, then
     * Jarvis starts by itself and speaks the welcome. Only an explicit Stop keeps it off.
     */
    private fun autoStart() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            if (askedOnce) return
            askedOnce = true
            askPermissions()
            return
        }
        if (!VoiceService.running && !prefs.userStopped) {
            startForegroundService(Intent(this, VoiceService::class.java))
            status.postDelayed({ refresh() }, 800)
        }
    }

    private fun wantedPerms(): List<String> {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms
    }

    /** Tells the user (and the screen reader) what just happened. */
    private fun announce(msg: String) {
        status.text = msg
        status.announceForAccessibility(msg)
    }

    /**
     * Opens the accessibility list. Some phones (Tecno, Infinix, Xiaomi and others) do not
     * handle the standard screen or block it, so fall back to the main Settings app instead of
     * silently doing nothing.
     */
    private fun openAccessibility() {
        val tries = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            try {
                startActivity(i)
                if (i.action == Settings.ACTION_SETTINGS) {
                    announce("Opened Settings. Go to Accessibility, then Installed apps or Downloaded services, then Jarvis.")
                }
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
        announce("Could not open Settings. Open the Settings app yourself, then Accessibility, then Jarvis.")
    }

    /**
     * Asks only for what is still missing. Android silently ignores the request once the user
     * has said "Don't allow" twice, so in that case open Jarvis's App info page instead.
     */
    private fun askPermissions() {
        val missing = wantedPerms().filter { !has(it) }
        if (missing.isEmpty()) {
            announce("Microphone, contacts and notifications are already allowed.")
            return
        }
        val sp = getSharedPreferences("nova", MODE_PRIVATE)
        val blocked = missing.any { sp.getBoolean("asked_$it", false) && !shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            openAppInfo()
            return
        }
        sp.edit().apply { missing.forEach { putBoolean("asked_$it", true) } }.apply()
        requestPermissions(missing.toTypedArray(), 1)
    }

    private fun openAppInfo() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
            announce("Android will not ask again. In App info, open Permissions and allow Microphone, Contacts and Notifications.")
        } catch (e: Exception) {
            announce("Open Settings, then Apps, then Jarvis, then Permissions, and allow Microphone, Contacts and Notifications.")
        }
    }

    private fun save() {
        prefs.wakeName = nameField.text.toString()
        prefs.aiKey = keyField.text.toString()
        sensField.text.toString().toFloatOrNull()?.let { prefs.wakeThreshold = it }
        prefs.aiConsent = consent.isChecked
        prefs.confirmAll = confirmAll.isChecked
        prefs.quietEars = quiet.isChecked
    }

    private fun testAi() {
        save()
        status.text = "Testing AI..."
        AiClient(prefs).diagnose { report ->
            status.text = report
            status.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(packageName + "/")
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val pack = Packs.load(this, prefs.lang)
        langBtn.text = "Language: " + pack.name + ". Tap to change."
        startBtn.text = if (VoiceService.running) "Stop Jarvis" else "Start Jarvis"
        status.text = "Accessibility: " + (if (serviceOn()) "on" else "off") +
            ". Microphone: " + (if (has(Manifest.permission.RECORD_AUDIO)) "allowed" else "not allowed") +
            ". Contacts: " + (if (has(Manifest.permission.READ_CONTACTS)) "allowed" else "not allowed") +
            ". Jarvis: " + (if (VoiceService.running) "running, " + VoiceService.modeName + ". " + VoiceService.lastWake + ". " + VoiceService.debug else "stopped") + "."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (has(Manifest.permission.RECORD_AUDIO) && !VoiceService.running && !prefs.userStopped) {
            startForegroundService(Intent(this, VoiceService::class.java))
        }
        status.postDelayed({ refresh() }, 800)
    }
}
