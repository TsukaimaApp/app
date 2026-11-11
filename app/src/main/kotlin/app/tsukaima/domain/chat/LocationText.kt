package app.tsukaima.domain.chat

import java.util.Locale

/** The user's position as text for a message. */
object LocationText {
    /**
     * "My location: 52.22970, 21.01220 (within 15 m) https://maps.google.com/?q=52.22970,21.01220": five decimals
     * (about a metre), the accuracy rounded up when the phone reported one, and a link any map app opens.
     */
    fun of(latitude: Double, longitude: Double, accuracyM: Float? = null): String {
        val lat = String.format(Locale.US, "%.5f", latitude)
        val lon = String.format(Locale.US, "%.5f", longitude)
        val within = accuracyM?.takeIf { it > 0 && it.isFinite() }?.let { " (within ${formatDistance(it)})" }.orEmpty()
        return "My location: $lat, $lon$within https://maps.google.com/?q=$lat,$lon"
    }

    private fun formatDistance(metres: Float): String {
        val m = kotlin.math.ceil(metres.toDouble()).toLong()
        return if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "$m m"
    }
}
