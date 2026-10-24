package app.tsukaima.share

/** Where a shared payload goes: into the most recent chat, or into a fresh one. */
enum class ShareTarget { LastChat, NewChat }

/** One shared stream copied into the app's cache at [path]. */
data class SharedFile(val path: String, val name: String, val mime: String?, val size: Long)

/** A share ready to hand to the chat screen: typed [text], cached [files], and where they go. */
data class SharedContent(val text: String, val files: List<SharedFile>, val target: ShareTarget, val sessionId: String?)
