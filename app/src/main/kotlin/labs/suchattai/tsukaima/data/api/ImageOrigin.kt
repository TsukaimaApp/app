package labs.suchattai.tsukaima.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Origin rules for images in replies (`![alt](url)`). An image on the server's own origin loads by itself and gets
 * the bearer; any other origin is a third party: loading it on its own would let a prompt-injected reply leak data
 * through the URL, so it waits for a tap and never carries credentials.
 */
object ImageOrigin {
    /**
     * Whether [url] has the same scheme, host and port as [baseUrl]. Parsed with [HttpUrl], so the host is
     * compared case-insensitively, a default port equals an explicit one (`https://h` = `https://h:443`), and
     * userinfo is not the host (`https://server@evil.example` is evil.example). Anything unparseable is not
     * the same origin.
     */
    fun sameOrigin(url: String, baseUrl: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        val own = baseUrl.toHttpUrlOrNull() ?: return false
        return target.scheme == own.scheme && target.host == own.host && target.port == own.port
    }

    /** The host [url] would be fetched from, for the "tap to load" caption; null when it is not http(s). */
    fun host(url: String): String? = url.toHttpUrlOrNull()?.host
}

/** An image over [HermesApi.IMAGE_MAX_BYTES]: final, unlike other [IOException]s. */
class ImageTooLargeException(message: String) : IOException(message)
