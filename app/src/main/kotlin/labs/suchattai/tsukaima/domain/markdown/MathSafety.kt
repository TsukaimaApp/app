package labs.suchattai.tsukaima.domain.markdown

/**
 * Whether agent-written TeX may be handed to the renderer. JLaTeXMath honours file loading (`\includegraphics`) and
 * macro definitions, so a formula using any control word that loads, writes or (re)defines something is refused, as
 * is one longer than [MAX_LENGTH]; a refused formula shows as its source, like one that fails to parse. A control
 * word matches exactly: `\input` is refused, `\inputx` or `\definecolor` is another word. Pure; never throws.
 */
object MathSafety {
    const val MAX_LENGTH = 2000

    private val BANNED = listOf(
        "includegraphics", "input", "include", "def", "edef", "gdef", "xdef", "let", "newcommand", "renewcommand",
        "providecommand", "newenvironment", "renewenvironment", "csname", "catcode", "loop", "write", "openin",
        "openout", "read",
    )

    private val bannedWord = Regex("\\\\(?:${BANNED.joinToString("|")})(?![A-Za-z])")

    fun allowed(tex: String): Boolean = tex.length <= MAX_LENGTH && !bannedWord.containsMatchIn(tex)
}
