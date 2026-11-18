package ng.ainow.nova

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Google Gemini over plain HTTPS (a normal network call, no WebView tricks).
 * Used ONLY for: chat, sentences the rules cannot understand, and naming icons.
 */
class AiClient(private val prefs: Prefs) {
    class Result(val ok: Boolean, val text: String, val error: String)
    private class Resp(val code: Int, val body: String, val msg: String)

    companion object {
        val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest")
        private val ex = Executors.newCachedThreadPool()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Callback always runs on the main thread. */
    fun ask(system: String, user: String, images: List<Bitmap> = emptyList(), json: Boolean = false, cb: (Result) -> Unit) {
        ex.execute {
            val r = askSync(system, user, images, json)
            main.post { cb(r) }
        }
    }

    private fun askSync(system: String, user: String, images: List<Bitmap>, json: Boolean): Result {
        val key = prefs.aiKey.trim()
        if (key.isEmpty()) return Result(false, "", "No API key set")
        var last = "unknown error"
        for (model in MODELS) {
            for (think in listOf(true, false)) {
                val res = post(model, key, body(system, user, images, json, think))
                if (res.code == 200) {
                    val t = extractText(res.body)
                    if (t.isNotBlank()) return Result(true, t, "")
                    last = "empty reply from $model"
                    break
                }
                last = "HTTP ${res.code} ${res.msg}".trim()
                if (res.code == -1) return Result(false, "", last)            // no network / timeout
                if (res.code == 400 && think && res.msg.contains("think", true)) continue // retry without thinking setting
                if (res.code == 400 || res.code == 401 || res.code == 403) return Result(false, "", last) // key or request problem
                break // 404, 429, 5xx: try the next model
            }
        }
        return Result(false, "", last)
    }

    private fun body(system: String, user: String, images: List<Bitmap>, json: Boolean, think: Boolean): String {
        val parts = JSONArray().put(JSONObject().put("text", user))
        for (b in images) {
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                )
            )
        }
        val o = JSONObject()
        o.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (system.isNotEmpty()) {
            o.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val gc = JSONObject()
        gc.put("maxOutputTokens", 600)
        if (json) gc.put("responseMimeType", "application/json")
        if (think) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        o.put("generationConfig", gc)
        return o.toString()
    }

    private fun post(model: String, key: String, payload: String): Resp {
        var c: HttpURLConnection? = null
        return try {
            c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            var msg = ""
            if (code >= 400) {
                msg = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { text.take(120) }
            }
            Resp(code, text, msg.take(160))
        } catch (e: Exception) {
            Resp(-1, "", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160))
        } finally {
            c?.disconnect()
        }
    }

    private fun extractText(body: String): String {
        return try {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** One short request: is this a command, or just chat? Returns JSON text. */
    fun interpret(text: String, languageName: String, buttons: List<String>, cb: (Result) -> Unit) {
        val system = """
You are Nova, a friendly voice assistant that controls an Android phone for a blind user.
The user speaks $languageName (they may mix English). Answer ONLY with JSON.
If the user wants the phone to do something, reply {"type":"command","id":"<id>","arg":"<text>"}.
Allowed ids: tap (arg = a button name from the list below), open (arg = app name), call (arg = contact name),
whatsapp (arg = contact name), search (arg = what to search), type (arg = text to type),
back, home, recents, notifications, quick_settings, read_screen, read_buttons, scroll_down, scroll_up.
Otherwise reply {"type":"chat","reply":"<answer in $languageName, at most 40 words, plain text, no markdown>"}.
Buttons visible now: ${buttons.joinToString(" | ")}
""".trimIndent()
        ask(system, text, json = true, cb = cb)
    }

    /** Names icon-only buttons from small pictures. Returns one label per picture, same order. */
    fun labelIcons(crops: List<Bitmap>, cb: (List<String>?) -> Unit) {
        val system = "You name icon buttons in a phone app for a blind user. Answer ONLY with a JSON array of strings."
        val user = "The next ${crops.size} images are icon buttons, in order. For each give 1 to 3 lowercase words naming what the button does (for example: send, back, search, camera, settings, more options, close). Use an empty string if unsure."
        ask(system, user, crops, true) { r ->
            if (!r.ok) { cb(null); return@ask }
            try {
                val a = JSONArray(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                cb(List(a.length()) { a.optString(it, "") })
            } catch (e: Exception) {
                cb(null)
            }
        }
    }
}
