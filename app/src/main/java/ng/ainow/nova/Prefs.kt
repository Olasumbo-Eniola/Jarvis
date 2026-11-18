package ng.ainow.nova

import android.content.Context

/** Small settings store. Everything stays on the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) { sp.edit().putString("lang", v).apply() }

    var wakeName: String
        get() = sp.getString("wakeName", "Nova") ?: "Nova"
        set(v) { sp.edit().putString("wakeName", v.trim().ifEmpty { "Nova" }).apply() }

    var aiKey: String
        get() = sp.getString("aiKey", "") ?: ""
        set(v) { sp.edit().putString("aiKey", v.trim()).apply() }

    /** The user must agree before button names or icon pictures are sent to Gemini. */
    var aiConsent: Boolean
        get() = sp.getBoolean("aiConsent", false)
        set(v) { sp.edit().putBoolean("aiConsent", v).apply() }

    var confirmAll: Boolean
        get() = sp.getBoolean("confirmAll", false)
        set(v) { sp.edit().putBoolean("confirmAll", v).apply() }

    var requireName: Boolean
        get() = sp.getBoolean("requireName", true)
        set(v) { sp.edit().putBoolean("requireName", v).apply() }

    var quietEars: Boolean
        get() = sp.getBoolean("quietEars", true)
        set(v) { sp.edit().putBoolean("quietEars", v).apply() }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }

    val aiAllowed: Boolean get() = aiKey.isNotBlank() && aiConsent
}
