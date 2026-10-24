package app.tsukaima.domain.voice

/** [seconds] as `m:ss` (minutes unpadded): 64 → "1:04", 750 → "12:30". The voice line and notification show it. */
fun liveClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
