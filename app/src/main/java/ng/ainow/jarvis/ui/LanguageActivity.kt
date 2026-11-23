package ng.ainow.jarvis.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Normalizer
import java.util.Locale
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.language.Pack
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.speaking.Speaker

/**
 * Full-screen language picker: a search box, then English and the Nigerian languages in their own
 * section at the top, then every other language. Built from plain Android controls so TalkBack and
 * Jieshuo read it well: section titles are headings (swipe by heading to jump between them), each
 * row is "Yorùbá (Yoruba), checked/not checked", and tapping a row plays a short sample in it.
 * "Use this language" saves the checked row.
 */
class LanguageActivity : Activity() {

    companion object {
        /** Shown first, in this order. Everything else follows, sorted by English name. */
        val PINNED = listOf("en", "pcm", "yo", "ig", "ha")

        fun englishName(code: String): String = when (code) {
            "pcm" -> "Nigerian Pidgin"
            else -> Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
        }

        fun previewText(p: Pack): String = p.say["switched"]?.let { String.format(it, p.name) } ?: p.name
    }

    private sealed class Row {
        class Header(val title: String) : Row()
        class Lang(val pack: Pack, val english: String) : Row()
    }

    private lateinit var prefs: Prefs
    private lateinit var all: List<Row.Lang>
    private var rows: List<Row> = emptyList()
    private var chosen = "en"
    private lateinit var list: ListView
    private lateinit var useBtn: Button
    private val voice by lazy { Speaker(this) {} }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Choose language"
        val codes = Packs.codes(this)
        chosen = if (prefs.lang in codes) prefs.lang else "en"
        all = codes.map { Row.Lang(Packs.load(this, it), englishName(it)) }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val heading = TextView(this)
        heading.text = "Choose language. Tap one to hear it."
        heading.textSize = 20f
        heading.setTypeface(heading.typeface, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        col.addView(heading)

        val search = EditText(this)
        search.hint = "Search languages"
        search.contentDescription = "Search languages"
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.setSingleLine()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
        })
        col.addView(search)

        list = ListView(this)
        list.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos] as? Row.Lang ?: return@setOnItemClickListener
            pick(r.pack)
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = Button(this)
        cancel.text = "Cancel"
        cancel.setOnClickListener { finish() }
        useBtn = Button(this)
        useBtn.setOnClickListener {
            prefs.lang = chosen
            setResult(RESULT_OK)
            finish()
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(useBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        col.addView(buttons)

        setContentView(col)
        filter("")
        updateUseButton()
    }

    override fun onPause() {
        voice.stop()
        super.onPause()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    private fun pick(p: Pack) {
        chosen = p.code
        syncChecked()
        updateUseButton()
        voice.stop()
        voice.speak(previewText(p), p.tts)
        if (voice.hasVoice(p.tts) == false) {
            voice.speak("This phone has no " + p.name + " voice, so Jarvis will read " + p.name + " with an English voice.", "en-NG")
        }
    }

    private fun updateUseButton() {
        val p = all.firstOrNull { it.pack.code == chosen }?.pack
        useBtn.text = "Use " + (p?.name ?: "English")
    }

    /** Matches the native name, English name, code or aliases, ignoring accents and case. */
    private fun plain(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun filter(query: String) {
        val q = plain(query.trim())
        val hits = all.filter { r ->
            q.isEmpty() || (listOf(r.pack.name, r.english, r.pack.code) + r.pack.aliases).any { plain(it).contains(q) }
        }
        val pinned = PINNED.mapNotNull { c -> hits.firstOrNull { it.pack.code == c } }
        val others = hits.filter { it.pack.code !in PINNED }.sortedBy { it.english }
        val out = ArrayList<Row>()
        if (pinned.isNotEmpty()) { out.add(Row.Header("English and Nigerian languages")); out.addAll(pinned) }
        if (others.isNotEmpty()) { out.add(Row.Header("Other languages")); out.addAll(others) }
        if (out.isEmpty()) out.add(Row.Header("No language matches \"" + query.trim() + "\""))
        rows = out
        adapter.notifyDataSetChanged()
        syncChecked()
    }

    private fun syncChecked() {
        list.clearChoices()
        val i = rows.indexOfFirst { it is Row.Lang && it.pack.code == chosen }
        if (i >= 0) list.setItemChecked(i, true)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Lang
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val pad = (16 * resources.displayMetrics.density).toInt()
            return when (val r = rows[position]) {
                is Row.Header -> {
                    val tv = convertView as? TextView ?: TextView(this@LanguageActivity).apply {
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(pad / 2, pad, pad / 2, pad / 3)
                        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                    }
                    tv.text = r.title
                    tv
                }
                is Row.Lang -> {
                    val tv = convertView as? CheckedTextView ?: (layoutInflater.inflate(
                        android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView)
                    // "Yorùbá (Yoruba)"; just "English" when both names are the same.
                    tv.text = if (plain(r.pack.name) == plain(r.english)) r.pack.name else r.pack.name + " (" + r.english + ")"
                    tv
                }
            }
        }
    }
}
