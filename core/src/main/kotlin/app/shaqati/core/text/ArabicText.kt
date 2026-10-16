package app.shaqati.core.text

/**
 * Arabic text normalization used before any keyword matching, so that
 * "مجدّدة", "مجدده" and "مجددة" all match the same rule.
 */
object ArabicText {
    private val diacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val tatweel = Regex("\\u0640")
    private val spaces = Regex("\\s+")

    /** Converts Arabic-Indic and Persian digits to ASCII digits. */
    fun asciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.' // Arabic decimal separator
                    '٬' -> ',' // Arabic thousands separator
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var s = asciiDigits(input)
        s = diacritics.replace(s, "")
        s = tatweel.replace(s, "")
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    '،' -> ','
                    '؛' -> ';'
                    else -> c.lowercaseChar()
                }
            )
        }
        return spaces.replace(sb.toString(), " ").trim()
    }

    /** Words spelled out in Arabic ads, mapped to numbers. Keys are normalized. */
    private val numberWords = mapOf(
        "واحد" to 1, "واحده" to 1, "وحده" to 1,
        "اثنين" to 2, "اثنان" to 2, "ثنتين" to 2,
        "ثلاث" to 3, "ثلاثه" to 3, "ثلاثة" to 3,
        "اربع" to 4, "اربعه" to 4,
        "خمس" to 5, "خمسه" to 5,
        "ست" to 6, "سته" to 6,
    )

    fun numberWord(word: String): Int? = word.toIntOrNull() ?: numberWords[normalize(word)]
}
