package app.tsukaima.ui.chat

/**
 * The phone's wall clock (epoch millis) for what the transcript stamps itself: when a message was sent and how long
 * reasoning streamed. Hermes sends no time with live events. Replaceable so a test can fix the time.
 */
internal object WallClock {
    @Volatile
    var now: () -> Long = System::currentTimeMillis
}
