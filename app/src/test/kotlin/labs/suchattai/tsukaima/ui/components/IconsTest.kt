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

    /**
     * Added after the handoff (v0.2.0: clarify card, attachment chips; v0.3.0: `MEDIA:` file cards; v0.4.0: Stop,
     * masked-prompt card, sign-out; v0.5.0: compact context, subagent rows, output-risk note; v0.6.0: voice mode;
     * v0.6.1: voice language picker; v0.7.0: speech recognition; v0.8.0: PDF attachments, images in replies).
     */
    private val additions = listOf(
        "help", "attach_file", "image",
        "download", "description", "video_file", "audio_file", "folder_zip", "insert_drive_file",
        "stop", "key", "visibility", "visibility_off", "logout",
        "compress", "account_tree", "warning",
        "mic", "mic_off", "keyboard",
        "translate",
        "graphic_eq",
        "picture_as_pdf", "broken_image",
    )

    @Test
    fun everyHandoffIconResolves() {
        assertEquals(handoffNames.size + additions.size, IconName.entries.size)
        IconName.entries.forEach { name ->
            val vector = iconFor(name)
            assertNotNull("no vector for $name", vector)
            assertTrue("$name has no paths", vector.root.size > 0)
        }
    }

    @Test
    fun enumCoversExactlyTheHandoffList() {
        assertEquals((handoffNames + additions).toSet(), IconName.entries.map { it.symbol }.toSet())
    }
}
