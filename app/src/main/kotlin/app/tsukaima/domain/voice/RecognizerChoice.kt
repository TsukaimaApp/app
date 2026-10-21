package app.tsukaima.domain.voice

/** Which recogniser a run should use for a language tag (null = the device default language). */
data class RecognizerChoice(val onDevice: Boolean, val preferOffline: Boolean)

object RecognizerRouting {
    /**
     * On-device recognition (and the offline preference) only for the device's own language: an explicit other
     * language goes to the default, network-capable recogniser with no offline preference. A language match is
     * enough (`pl` is the device's language on a `pl-PL` device).
     */
    fun choose(tag: String?, deviceTag: String, onDeviceAvailable: Boolean): RecognizerChoice {
        val sameAsDevice = tag == null ||
            tag.equals(deviceTag, ignoreCase = true) ||
            tag.substringBefore('-').equals(deviceTag.substringBefore('-'), ignoreCase = true)
        return RecognizerChoice(onDevice = onDeviceAvailable && sameAsDevice, preferOffline = sameAsDevice)
    }
}
