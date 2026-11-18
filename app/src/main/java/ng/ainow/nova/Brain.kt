package ng.ainow.nova

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Decides what to do with what the user said. Order of preference:
 *   1. rule-based commands from the language pack  (free, instant, offline)
 *   2. on-device OCR for buttons the screen reader cannot name  (free, offline)
 *   3. Gemini, only for chat, strange sentences and naming icons  (needs key + consent)
 * Everything runs on the main thread.
 */
class Brain(
    private val ctx: Context,
    private val prefs: Prefs,
    private val speak: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val ai = AiClient(prefs)
    private val ocr = Ocr()
    private val en: Pack = Packs.load(ctx, "en")
    var pack: Pack = Packs.load(ctx, prefs.lang)
        private set
    private val packs: List<Pack> get() = if (pack.code == "en") listOf(en) else listOf(pack, en)

    var onSettled: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    private var work = 0
    val busy: Boolean get() = work > 0

    private val FOLLOW_MS = 12_000L
    private val ANSWER_MS = 15_000L
    private var convoUntil = 0L
    private var pendingUntil = 0L
    private var pendingYes: (() -> Unit)? = null
    private var pendingChoice: List<() -> Unit>? = null

    private fun begin() { work++ }
    private fun end() {
        work = maxOf(0, work - 1)
        if (work == 0) onSettled?.invoke()
    }

    private fun t(key: String, vararg a: Any): String {
        val s = pack.say[key] ?: en.say[key] ?: key
        return if (a.isEmpty()) s else String.format(s, *a)
    }

    private fun say(key: String, vararg a: Any) = speak(t(key, *a))

    private fun clearPending() {
        pendingYes = null
        pendingChoice = null
    }

    // ------------------------------------------------------------------ hearing

    /** [alts] are the recogniser's alternatives, best first. */
    fun heard(alts: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val cands = alts.map { Nlu.stripWake(it, prefs.wakeName) }

        // Waiting for "yes/no" or "first/second"?
        if (pendingYes != null || pendingChoice != null) {
            if (now > pendingUntil) {
                clearPending()
            } else {
                val py = pendingYes
                val pc = pendingChoice
                for ((_, rest) in cands) {
                    if (pc != null) {
                        val n = Nlu.ordinal(rest, packs)
                        if (n != null && n in 1..pc.size) {
                            clearPending()
                            convoUntil = now + FOLLOW_MS
                            pc[n - 1]()
                            return
                        }
                    }
                    val c = Nlu.parse(rest, packs)
                    if (c is Cmd.Simple && c.id == "yes" && py != null) {
                        clearPending()
                        py()
                        return
                    }
                    if (c is Cmd.Simple && c.id == "no") {
                        clearPending()
                        say("ok")
                        return
                    }
                }
                if (cands.none { it.first }) return // not addressed to Nova: keep waiting quietly
                clearPending()
            }
        }

        var chosen: Cmd? = null
        var unknown: Cmd? = null
        var unknownHad = false
        for ((had, rest) in cands) {
            val addressed = had || now < convoUntil || !prefs.requireName
            if (!addressed) continue
            if (rest.isBlank()) {
                if (had) {
                    convoUntil = now + FOLLOW_MS
                    say("here")
                    return
                }
                continue
            }
            val c = Nlu.parse(rest, packs)
            if (c is Cmd.Unknown) {
                if (unknown == null) {
                    unknown = c
                    unknownHad = had
                }
            } else {
                chosen = c
                break
            }
        }
        // Stray chatter in the follow-up window is ignored quietly unless Nova was called by name.
        if (chosen == null && !unknownHad && !prefs.aiAllowed) return
        val cmd = chosen ?: unknown ?: return
        convoUntil = now + FOLLOW_MS
        run(cmd)
    }

    private fun run(cmd: Cmd) {
        when (cmd) {
            is Cmd.Simple -> simple(cmd.id)
            is Cmd.Arg -> withArg(cmd)
            is Cmd.Unknown -> askAi(cmd.text)
        }
    }

    // ------------------------------------------------------------------ simple commands

    private fun simple(id: String) {
        val svc = NovaAccessibilityService.inst
        val needsService = id !in setOf("help", "quit", "volume_up", "volume_down", "yes", "no")
        if (needsService && svc == null) {
            say("noservice")
            return
        }
        when (id) {
            "back" -> svc?.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc?.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc?.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc?.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> svc?.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> svc?.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "scroll_down" -> svc?.scroll(true)
            "scroll_up" -> svc?.scroll(false)
            "read_screen" -> readScreen(svc!!)
            "read_buttons" -> readButtons(svc!!)
            "clear_text" -> {
                val f = svc?.focusedInput()
                if (f == null) say("no_input") else svc?.setText(f, "")
            }
            "volume_up", "volume_down" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (id == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
            }
            "help" -> say("help")
            "quit" -> {
                speak(t("bye"))
                onQuit?.invoke()
            }
            else -> {} // yes / no with nothing pending: ignore
        }
    }

    private fun readScreen(svc: NovaAccessibilityService) {
        val scr = svc.scan()
        if (scr.texts.isEmpty()) say("empty_screen") else speak(scr.texts.joinToString(". "))
    }

    private fun readButtons(svc: NovaAccessibilityService) {
        val scr = svc.scan()
        val names = scr.controls.filter { !it.unlabeled && it.label.isNotBlank() }.map { it.label }.distinct().take(25)
        val unl = scr.controls.count { it.unlabeled }
        val sb = StringBuilder()
        if (names.isNotEmpty()) sb.append(t("buttons")).append(' ').append(names.joinToString(", ")).append('.')
        if (unl > 0) sb.append(' ').append(t("unlabeled", unl))
        if (sb.isEmpty()) say("empty_screen") else speak(sb.toString().trim())
    }

    // ------------------------------------------------------------------ commands with an argument

    private fun withArg(c: Cmd.Arg) {
        if (c.arg.isBlank() && c.id != "fill") {
            say("need_arg")
            return
        }
        when (c.id) {
            "tap" -> tap(c.arg)
            "open" -> openApp(c.arg)
            "call" -> call(c.arg)
            "whatsapp" -> whatsapp(c.arg)
            "search" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(c.arg, "UTF-8"))))
            "type" -> typeText(c.arg)
            "fill" -> fill(c.arg, c.arg2)
            "language" -> switchLanguage(c.arg)
            else -> say("notunderstood")
        }
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val host: Context = NovaAccessibilityService.inst ?: ctx
            host.startActivity(i)
        } catch (e: Exception) {
            say("openfail")
        }
    }

    // ---- tapping: accessibility tree first, then OCR, then icon naming by AI ----

    private fun needsConfirm(label: String): Boolean {
        if (prefs.confirmAll) return true
        val lw = Matcher.words(label)
        return packs.any { p -> p.risky.any { Matcher.containsWords(lw, Matcher.words(it)) } }
    }

    private fun confirmThen(label: String, act: () -> Unit) {
        if (needsConfirm(label)) {
            pendingYes = act
            pendingChoice = null
            pendingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
            say("confirm", label)
        } else {
            act()
        }
    }

    private fun tap(target: String) {
        val svc = NovaAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        val scr = svc.scan()
        val scored = scr.controls
            .map { it to Matcher.score(target, it.label) * (if (it.weak) 0.9 else 1.0) }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) {
            ocrTap(target, svc, scr)
            return
        }
        val top = scored[0].second
        val ties = scored.filter { top - it.second < 0.05 }
            .map { it.first }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            .take(5)
        if (ties.size >= 2) {
            pendingChoice = ties.map { item -> { clickItem(svc, item) } }
            pendingYes = null
            pendingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
            say("choose", ties.size, target)
            return
        }
        clickItem(svc, ties[0])
    }

    private fun clickItem(svc: NovaAccessibilityService, item: Item) {
        if (!item.enabled) {
            say("disabled", item.label)
            return
        }
        confirmThen(item.label) {
            begin()
            svc.click(item) { ok ->
                main.post {
                    if (!ok) say("tapfail")
                    end()
                }
            }
        }
    }

    private fun tapPoint(svc: NovaAccessibilityService, rect: Rect, label: String) {
        confirmThen(label) {
            begin()
            svc.tapAt(rect.exactCenterX(), rect.exactCenterY()) { ok ->
                main.post {
                    if (!ok) say("tapfail")
                    end()
                }
            }
        }
    }

    private fun ocrTap(target: String, svc: NovaAccessibilityService, scr: Screen) {
        if (Build.VERSION.SDK_INT < 30) {
            say("notfound", target)
            return
        }
        begin()
        svc.screenshot { bmp ->
            main.post {
                if (bmp == null) {
                    say("no_screenshot")
                    end()
                } else {
                    ocr.read(bmp) { lines ->
                        main.post {
                            val hit = lines
                                .map { it to Matcher.score(target, it.text) }
                                .filter { it.second >= 0.6 }
                                .sortedWith(
                                    compareByDescending<Pair<OcrLine, Double>> { it.second }
                                        .thenBy { it.first.rect.width() * it.first.rect.height() }
                                )
                                .firstOrNull()
                            if (hit != null) {
                                tapPoint(svc, hit.first.rect, hit.first.text)
                                end()
                            } else {
                                iconFallback(target, svc, scr, bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun crop(bmp: Bitmap, r: Rect): Bitmap? {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val tp = r.top.coerceIn(0, bmp.height - 1)
        val w = r.width().coerceAtMost(bmp.width - l)
        val h = r.height().coerceAtMost(bmp.height - tp)
        if (w < 8 || h < 8) return null
        return try {
            val c = Bitmap.createBitmap(bmp, l, tp, w, h)
            val scale = 128f / maxOf(w, h)
            if (scale < 1f) Bitmap.createScaledBitmap(c, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else c
        } catch (e: Exception) {
            null
        }
    }

    /** Last resort: ask Gemini to name unlabeled icon buttons from their pictures. */
    private fun iconFallback(target: String, svc: NovaAccessibilityService, scr: Screen, bmp: Bitmap) {
        val unl = scr.controls.filter { it.unlabeled }.take(10)
        if (unl.isEmpty() || !prefs.aiAllowed || scr.hasPassword) {
            say("notfound", target)
            end()
            return
        }
        val pairs = unl.mapNotNull { item -> crop(bmp, item.rect)?.let { item to it } }
        if (pairs.isEmpty()) {
            say("notfound", target)
            end()
            return
        }
        ai.labelIcons(pairs.map { it.second }) { labels ->
            if (labels == null) {
                say("ai_error")
            } else {
                var best = -1
                var bestScore = 0.6
                for (i in pairs.indices) {
                    val s = Matcher.score(target, labels.getOrElse(i) { "" })
                    if (s >= bestScore) { best = i; bestScore = s }
                }
                if (best >= 0) clickItem(svc, pairs[best].first) else say("notfound", target)
            }
            end()
        }
    }

    // ---- text fields ----

    private fun typeText(value: String) {
        val svc = NovaAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        val f = svc.focusedInput()
        if (f == null) say("no_input") else svc.setText(f, spoken(value))
    }

    private fun fill(field: String, value: String) {
        val svc = NovaAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        if (field.isBlank() || value.isBlank()) { say("need_arg"); return }
        val inputs = svc.scan().controls.filter { it.editable }
        val best = inputs.map { it to Matcher.score(field, it.label) }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
            ?: if (inputs.size == 1) inputs[0] else null
        if (best == null) say("field_missing", field) else svc.setText(best.node, spoken(value))
    }

    /** "john at gmail dot com" -> "john@gmail.com" */
    private fun spoken(v: String): String {
        if (!v.contains(" at ", true) || !v.contains(" dot ", true)) return v
        return v.replace(" at ", "@", true).replace(" dot ", ".", true).replace(" ", "")
    }

    // ---- apps, contacts, web ----

    private fun openApp(name: String) {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(q, 0)
            .map { it to Matcher.score(name, it.loadLabel(pm).toString()) }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }
        if (best == null) {
            say("app_missing", name)
            return
        }
        val pkg = best.first.activityInfo.packageName
        val i = pm.getLaunchIntentForPackage(pkg)
        if (i == null) say("app_missing", name) else {
            say("opening", best.first.loadLabel(pm).toString())
            launch(i)
        }
    }

    private class Contact(val name: String, val number: String)

    private fun findContact(name: String): Contact? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("nocontacts")
            return null
        }
        val out = ArrayList<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    out.add(Contact(n, num))
                }
            }
        } catch (e: Exception) {
            say("nocontacts")
            return null
        }
        val best = out.map { it to Matcher.score(name, it.name) }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
        if (best == null) say("contact_missing", name)
        return best
    }

    private fun call(name: String) {
        val c = findContact(name) ?: return
        val digits = c.number.filter { it.isDigit() || it == '+' }
        pendingYes = { launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))) }
        pendingChoice = null
        pendingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
        say("confirm_call", c.name)
    }

    private fun whatsapp(name: String) {
        val asDigits = name.filter { it.isDigit() }
        var num: String
        if (asDigits.length >= 7) {
            num = asDigits
        } else {
            val c = findContact(name) ?: return
            num = c.number.filter { it.isDigit() }
        }
        if (num.startsWith("0") && num.length == 11) num = "234" + num.substring(1) // Nigerian local format
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num")))
    }

    // ---- language ----

    private fun switchLanguage(name: String) {
        val n = Matcher.norm(name)
        for (code in Packs.codes(ctx)) {
            val p = Packs.load(ctx, code)
            if ((p.aliases + p.name + p.code).any { Matcher.norm(it) == n }) {
                prefs.lang = code
                pack = p
                speak(p.say["switched"]?.let { String.format(it, p.name) } ?: p.name)
                return
            }
        }
        say("lang_missing", name)
    }

    // ------------------------------------------------------------------ AI (only when rules fail)

    private fun askAi(text: String) {
        if (!prefs.aiAllowed) {
            say("notunderstood")
            return
        }
        val svc = NovaAccessibilityService.inst
        val scr = svc?.scan()
        val labels = if (scr == null || scr.hasPassword) emptyList()
        else scr.controls.map { it.label }.filter { it.isNotBlank() }.distinct().take(40)
        begin()
        ai.interpret(text, pack.name, labels) { r ->
            if (!r.ok) {
                say("ai_error")
            } else {
                handleAi(r.text)
            }
            end()
        }
    }

    private val AI_SIMPLE = setOf(
        "back", "home", "recents", "notifications", "quick_settings",
        "read_screen", "read_buttons", "scroll_down", "scroll_up"
    )
    private val AI_ARG = setOf("tap", "open", "call", "whatsapp", "search", "type")

    private fun handleAi(raw: String) {
        try {
            val o = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            when (o.optString("type")) {
                "chat" -> {
                    val reply = o.optString("reply")
                    if (reply.isBlank()) say("notunderstood") else speak(reply)
                }
                "command" -> {
                    val id = o.optString("id")
                    val arg = o.optString("arg")
                    // Only whitelisted actions are ever executed, never free-form AI output.
                    if (id in AI_SIMPLE) simple(id)
                    else if (id in AI_ARG && arg.isNotBlank()) withArg(Cmd.Arg(id, arg))
                    else say("notunderstood")
                }
                else -> say("notunderstood")
            }
        } catch (e: Exception) {
            say("notunderstood")
        }
    }
}
