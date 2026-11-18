package ng.ainow.nova

import java.text.Normalizer

/** Text normalising and fuzzy matching. No AI, works offline, works for any Latin-script language. */
object Matcher {
    private val MARKS = Regex("\\p{Mn}+")
    private val JUNK = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")

    /** Lowercase, remove accents and tone marks, drop punctuation. "Ṣí" -> "si", "Écran" -> "ecran". */
    fun norm(s: String): String {
        var t = s.lowercase()
        t = t.replace('ɓ', 'b').replace('ɗ', 'd').replace('ƙ', 'k').replace('ƴ', 'y').replace("ß", "ss")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
        t = t.replace(JUNK, " ").replace(SPACES, " ").trim()
        return t
    }

    fun words(s: String): List<String> {
        val n = norm(s)
        return if (n.isEmpty()) emptyList() else n.split(" ")
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Do the words of [needle] appear one after another inside [hay]? */
    fun containsWords(hay: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > hay.size) return false
        for (i in 0..(hay.size - needle.size)) {
            var ok = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    private fun tokenSim(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = minOf(a.length, b.length)
        val l = lev(a, b)
        if (m >= 4 && l <= 1) return 0.85
        if (m >= 6 && l <= 2) return 0.7
        return 0.0
    }

    /** 0.0 = no match, 1.0 = same words. Speech recognisers misspell, so this is forgiving. */
    fun score(target: String, label: String): Double {
        val tt = words(target)
        val lt = words(label)
        if (tt.isEmpty() || lt.isEmpty()) return 0.0
        if (tt == lt) return 1.0
        if (containsWords(lt, tt)) return 0.9 - 0.1 * minOf(1.0, (lt.size - tt.size) / 6.0)
        if (norm(label).length >= 3 && containsWords(tt, lt)) return 0.8
        var sum = 0.0
        for (x in tt) {
            var best = 0.0
            for (y in lt) best = maxOf(best, tokenSim(x, y))
            sum += best
        }
        val ratio = sum / tt.size
        return if (ratio >= 0.85) 0.7 * ratio + 0.1 else 0.0
    }
}
