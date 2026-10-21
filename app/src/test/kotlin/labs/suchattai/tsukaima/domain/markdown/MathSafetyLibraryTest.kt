package labs.suchattai.tsukaima.domain.markdown

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.NewCommandMacro
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Checks [MathSafety.BANNED] against jlatexmath-android's own tables, loaded the way the app loads them (Robolectric:
 * the library reads its fonts and settings from assets).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathSafetyLibraryTest {

    /**
     * The library's commands with a global effect, from its bytecode (javap on the AAR's classes.jar): the
     * `PredefinedCommands` registrations whose `PredefMacroInfo.invokeID` handler calls
     * `NewCommandMacro.addNewCommand`/`addReNewCommand`, `NewEnvironmentMacro.addNewEnvironment`/`addReNewEnvironment`
     * or writes static state (`ColorAtom.Colors`, `JavaFontRenderingBox.setFont`, `DefaultTeXFont.setMathSizes` /
     * `setMagnification`, `jlmXML_macro`'s re-registration). `\mathbb` and the other `textstyle_macros` set and
     * restore `TeXFormula.externalFontMap` within the call, so they are not here.
     */
    private val globalCommands = listOf(
        "newcommand", "renewcommand", "newenvironment", "renewenvironment", "definecolor", "jlmXML", "jlmExternalFont",
        "DeclareMathSizes", "magnification", "includegraphics",
    )

    private fun loadLibrary() {
        JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
        JLatexMathDrawable.builder("\\frac{1}{2}").textSize(20f).build()
    }

    @Test
    fun theLibrarysGlobalCommandsAreRegisteredAndBanned() {
        loadLibrary()
        val registered = MacroInfo.Commands.keys
        for (name in globalCommands) {
            assertTrue("$name is not a jlatexmath command (list out of date?)", name in registered)
            assertTrue("$name is not banned", name in MathSafety.BANNED)
        }
    }

    @Test
    fun everyPredefinedMacroWhoseBodyDefinesIsBanned() {
        loadLibrary()
        @Suppress("UNCHECKED_CAST")
        val macros = NewCommandMacro::class.java.getDeclaredField("macrocode")
            .apply { isAccessible = true }
            .get(null) as Map<String, String>
        assertTrue("no predefined macros loaded", macros.isNotEmpty())
        val defining = macros.filterValues { !MathSafety.allowed(it) }.keys
        assertTrue("DeclareMathOperator not found among $defining", "DeclareMathOperator" in defining)
        for (name in defining) assertTrue("$name defines through its body but is not banned", name in MathSafety.BANNED)
    }
}
