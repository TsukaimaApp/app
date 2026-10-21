package app.tsukaima.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClarifyAnswersTest {
    private val single = ClarifyQuestion("q1", "Colour?", listOf("red", "green"), multiSelect = false)
    private val multi = ClarifyQuestion("q2", "Toppings?", listOf("ham", "olives"), multiSelect = true)
    private val open = ClarifyQuestion("q3", "Why?", null, multiSelect = false)

    @Test
    fun encodeMultiSelectMatchesTuiJsonStringify() {
        // ui-tui prompts.tsx lockPicked: JSON.stringify(values) — a compact JSON array of strings.
        assertEquals("""["red","green"]""", ClarifyAnswers.encodeMultiSelect(listOf("red", "green")))
        assertEquals("""["say \"hi\"","zażółć"]""", ClarifyAnswers.encodeMultiSelect(listOf("say \"hi\"", "zażółć")))
        assertEquals("", ClarifyAnswers.encodeMultiSelect(emptyList()))
    }

    @Test
    fun answerPerQuestionKind() {
        assertEquals("red", ClarifyAnswers.answer(single, listOf("red"), ""))
        assertEquals("purple", ClarifyAnswers.answer(single, emptyList(), "  purple "))
        assertNull(ClarifyAnswers.answer(single, emptyList(), "   "))
        // Multi-select: picked choices, then the typed "Other" value, like lockPicked([...picked, custom]).
        assertEquals("""["ham","olives","pineapple"]""", ClarifyAnswers.answer(multi, listOf("ham", "olives"), "pineapple"))
        assertNull(ClarifyAnswers.answer(multi, emptyList(), ""))
        assertEquals("because", ClarifyAnswers.answer(open, emptyList(), "because"))
        assertNull(ClarifyAnswers.answer(open, emptyList(), ""))
    }

    @Test
    fun displayDecodesMultiSelect() {
        assertEquals("ham, olives", ClarifyAnswers.display("""["ham","olives"]""", multiSelect = true))
        assertEquals("""["ham"]""", ClarifyAnswers.display("""["ham"]""", multiSelect = false))
        assertEquals("not json", ClarifyAnswers.display("not json", multiSelect = true))
        assertEquals("Skipped", ClarifyAnswers.display(null, multiSelect = false))
    }
}
