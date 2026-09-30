package org.sonorus.data

// The server's `restoreReserved` (src/lib/reserved.js), for the names this phone
// stored before the server read them back itself. Keep the two tables alike.
private val LOOKALIKES = mapOf(
    '/' to "\u2215\u2044\u29f8\u2571\u27cb",
    '\\' to "\u2216\u29f5\u29f9\ufe68\u2572\u27cd",
    ':' to "\ua789\u2236\u02d0\ufe55\ua4fd",
    '?' to "\ufe56",
    '*' to "\u2217\u204e\ufe61",
    '"' to "\u2033\u02ba",
    '<' to "\u02c2\u1438\ufe64",
    '>' to "\u02c3\u1433\ufe65",
    '|' to "\u01c0\u2223\u2502\u23d0\u2758",
)

private val REAL: Map<Char, Char> =
    LOOKALIKES.flatMap { (real, alikes) -> alikes.map { it to real } }.toMap()

private const val CJK =
    "\\u2e80-\\u2fdf\\u3000-\\u30ff\\u31f0-\\u31ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uff00-\\uffef\\x{20000}-\\x{2fa1f}"

// Fullwidth forms are ordinary punctuation in Japanese and Chinese, so a run of
// them stays where it touches that script or another fullwidth character.
private val FULLWIDTH = Regex(
    "(?<![$CJK])[\\uff02\\uff0a\\uff0f\\uff1a\\uff1c\\uff1e\\uff1f\\uff3c\\uff5c]+(?![$CJK])"
)

/** "AC∕DC" -> "AC/DC": the character a file name could not carry. */
fun restoreReserved(name: String): String {
    // Runs over every stored name at start, and no look-alike sits below U+01C0.
    if (name.none { it >= '\u01c0' }) return name
    val twins = buildString(name.length) { for (ch in name) append(REAL[ch] ?: ch) }
    return FULLWIDTH.replace(twins) { run -> run.value.map { it - 0xfee0 }.joinToString("") }
}
