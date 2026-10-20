package labs.suchattai.tsukaima.ui.chat.components

import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.domain.chat.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalScopesTest {
    private val card = ChatItem.Approval("srq-1", "r1", "ls", "terminal", answered = false)

    @Test
    fun sheetOffersOnlyTheScopesTheServerAllows() {
        assertEquals(emptyList<ApprovalChoice>(), approvalScopes(card))
        assertEquals(listOf(ApprovalChoice.session), approvalScopes(card.copy(allowSession = true)))
        assertEquals(listOf(ApprovalChoice.always), approvalScopes(card.copy(allowAlways = true)))
        assertEquals(
            listOf(ApprovalChoice.session, ApprovalChoice.always),
            approvalScopes(card.copy(allowSession = true, allowAlways = true)),
        )
    }

    @Test
    fun answeredCaptionNamesTheScope() {
        assertEquals("Approved", decisionLabel(ApprovalChoice.once))
        assertEquals("Approved for this session", decisionLabel(ApprovalChoice.session))
        assertEquals("Always approved", decisionLabel(ApprovalChoice.always))
        assertEquals("Denied", decisionLabel(ApprovalChoice.deny))
        assertEquals("Answered", decisionLabel(null))
    }
}
