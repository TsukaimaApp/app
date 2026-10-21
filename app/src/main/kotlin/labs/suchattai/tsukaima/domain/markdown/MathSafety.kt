package labs.suchattai.tsukaima.domain.markdown

/**
 * Whether agent-written TeX may be handed to the renderer. JLaTeXMath honours file loading (`\includegraphics`), and
 * its definitions are process-global: a macro, colour, font or size one reply defines leaks into every later
 * formula. So a formula using any control word in [BANNED] is refused, as is one longer than [MAX_LENGTH]; a refused
 * formula shows as its source, like one that fails to parse. Pure; never throws.
 *
 * A control word matches when no ASCII letter follows it. JLaTeXMath reads a command name with
 * `Character.isLetter`, so `\inputé` is a different command to it; refusing it anyway errs on the safe side, and
 * no character the library ends a name on is one this match treats as continuing it.
 */
object MathSafety {
    const val MAX_LENGTH = 2000

    /**
     * TeX primitives that load, write or define, plus every jlatexmath-android 0.2.0 command with a global effect,
     * read from its bytecode (`PredefinedCommands` registrations dispatched by `PredefMacroInfo.invokeID` to
     * `PredefMacros.*_macro`, and the predefined macro bodies in `PredefMacros.jlmXML_macro`):
     * `newcommand`/`renewcommand` (`NewCommandMacro.addNewCommand`/`addReNewCommand`),
     * `newenvironment`/`renewenvironment` (`NewEnvironmentMacro`), `DeclareMathOperator` (a predefined macro whose
     * body is `\newcommand`), `definecolor` (static `ColorAtom.Colors`), `jlmXML` (re-registers the predefined
     * macros), `jlmExternalFont` (`JavaFontRenderingBox.setFont`), `DeclareMathSizes` and `magnification`
     * (`DefaultTeXFont.setMathSizes`/`setMagnification`). `MathSafetyLibraryTest` checks the list against the
     * library's tables.
     */
    internal val BANNED = listOf(
        "includegraphics", "input", "include", "def", "edef", "gdef", "xdef", "let", "newcommand", "renewcommand",
        "providecommand", "newenvironment", "renewenvironment", "csname", "catcode", "loop", "write", "openin",
        "openout", "read", "DeclareMathOperator", "definecolor", "jlmXML", "jlmExternalFont", "DeclareMathSizes",
        "magnification",
    )

    private val bannedWord = Regex("\\\\(?:${BANNED.joinToString("|")})(?![A-Za-z])")

    fun allowed(tex: String): Boolean = tex.length <= MAX_LENGTH && !bannedWord.containsMatchIn(tex)
}
