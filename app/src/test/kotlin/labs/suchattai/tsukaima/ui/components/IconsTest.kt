package labs.suchattai.tsukaima.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconsTest {
    private val handoffNames = listOf(
        "menu", "more_vert", "expand_more", "add", "arrow_upward", "arrow_back", "close", "check",
        "content_copy", "terminal", "psychology", "error", "gpp_maybe", "link", "settings",
        "monitor_heart", "schedule", "pause_circle", "play_circle", "play_arrow", "restart_alt",
        "refresh", "history", "chevron_right", "dns", "stop_circle", "forum", "chat", "memory",
        "folder", "edit", "archive", "unarchive", "delete", "radio_button_unchecked",
        "receipt_long", "bar_chart", "open_in_new", "cloud_off", "send", "tag",
    )

    @Test
    fun everyHandoffIconResolves() {
        assertEquals(handoffNames.size, IconName.entries.size)
        IconName.entries.forEach { name ->
            val vector = iconFor(name)
            assertNotNull("no vector for $name", vector)
            assertTrue("$name has no paths", vector.root.size > 0)
        }
    }

    @Test
    fun enumCoversExactlyTheHandoffList() {
        assertEquals(handoffNames.toSet(), IconName.entries.map { it.symbol }.toSet())
    }
}
