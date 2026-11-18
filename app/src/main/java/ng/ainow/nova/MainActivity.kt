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
    private lateinit var keyField: EditText
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

        col.addView(button("Step 1: turn on Nova in accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Step 2: allow microphone, contacts and notifications") {
            val perms = ArrayList<String>()
            perms.add(Manifest.permission.RECORD_AUDIO)
            perms.add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        })

        langBtn = button("") {
            val cur = codes.indexOf(prefs.lang).coerceAtLeast(0)
            prefs.lang = codes[(cur + 1) % codes.size]
            refresh()
        }
        col.addView(langBtn)

        col.addView(label("Name to call me by"))
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

        confirmAll = CheckBox(this)
        confirmAll.text = "Ask \"are you sure?\" before every tap"
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
                stopService(Intent(this, VoiceService::class.java))
            } else {
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
        refresh()
    }

    private fun save() {
        prefs.wakeName = nameField.text.toString()
        prefs.aiKey = keyField.text.toString()
        prefs.aiConsent = consent.isChecked
        prefs.confirmAll = confirmAll.isChecked
        prefs.quietEars = quiet.isChecked
    }

    private fun testAi() {
        save()
        status.text = "Testing AI..."
        AiClient(prefs).ask("", "Reply with the single word: ok") { r ->
            status.text = if (r.ok) "AI works. Reply: " + r.text.take(40)
            else "AI failed: " + r.error
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
        startBtn.text = if (VoiceService.running) "Stop Nova" else "Start Nova"
        status.text = "Accessibility: " + (if (serviceOn()) "on" else "off") +
            ". Microphone: " + (if (has(Manifest.permission.RECORD_AUDIO)) "allowed" else "not allowed") +
            ". Contacts: " + (if (has(Manifest.permission.READ_CONTACTS)) "allowed" else "not allowed") +
            ". Nova: " + (if (VoiceService.running) "listening" else "stopped") + "."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
