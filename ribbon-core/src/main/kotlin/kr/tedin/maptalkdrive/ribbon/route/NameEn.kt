package kr.tedin.maptalkdrive.ribbon.route

/**
 * English names for screens outside Korea, the same rules as tools/anchors/lib/english.mjs: OSM name:en wins; otherwise
 * the Korean name is romanised (Revised Romanisation, syllable by syllable, no sound-change rules) and the facility word
 * translated. A few names have a published English form that romanisation cannot guess.
 */
object NameEn {
    private val OVERRIDES = mapOf(
        "인제양양터널" to "Inje-Yangyang Tunnel",
        "렛츠런파크교차로" to "Let's Run Park Junction",
        "1100로" to "1100 Road",
        "평화로" to "Pyeonghwa-ro",
    )
    private val SUFFIXES = listOf(
        "터널" to "Tunnel", "지하차도" to "Underpass", "분기점" to "JC", "나들목" to "IC", "요금소" to "Tollgate",
        "영업소" to "Tollgate", "교차로" to "Junction", "삼거리" to "Junction", "사거리" to "Crossroad",
        "오거리" to "Crossroad", "입구" to "Entrance", "IC" to "IC", "JC" to "JC", "TG" to "TG",
    )
    private val INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINAL = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    private val TRAILING_NUMBER = Regex("^(.*?)(\\d+)$")

    fun of(name: String): String {
        OVERRIDES[name]?.let { return it }
        val (ko, en) = SUFFIXES.firstOrNull { name.endsWith(it.first) } ?: ("" to "")
        val stem = name.dropLast(ko.length)
        val m = TRAILING_NUMBER.matchEntire(stem)
        val words = if (m != null) listOf(romanise(m.groupValues[1]), m.groupValues[2]) else listOf(romanise(stem))
        return (words + en).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun romanise(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            val code = ch.code - 0xAC00
            if (code < 0 || code > 11171) { out.append(ch); continue }
            out.append(INITIAL[code / 588]).append(MEDIAL[(code % 588) / 28]).append(FINAL[code % 28])
        }
        return out.toString().replaceFirstChar { it.uppercaseChar() }
    }
}
