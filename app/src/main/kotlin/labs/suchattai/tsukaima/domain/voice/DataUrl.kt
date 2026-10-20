package labs.suchattai.tsukaima.domain.voice

import java.util.Base64
import java.util.Locale

/** Base64 `data:` URLs, as Hermes' audio relays carry them (`/api/audio/speak` answers one). */
object DataUrl {
    /**
     * The media type (parameters such as `codecs=` dropped) and the decoded bytes of a
     * `data:<mime>[;…];base64,<payload>` URL. Anything else (another scheme, a non-base64 payload, no comma, invalid
     * base64) is an [IllegalArgumentException].
     */
    fun decode(dataUrl: String): Pair<String, ByteArray> {
        require(dataUrl.startsWith(PREFIX)) { "not a data: URL" }
        val comma = dataUrl.indexOf(',')
        require(comma >= 0) { "data: URL without a payload" }
        val header = dataUrl.substring(PREFIX.length, comma).split(';')
        require(header.last().equals("base64", ignoreCase = true)) { "data: URL is not base64" }
        val mime = header.first().trim().lowercase(Locale.ROOT)
        return mime to Base64.getDecoder().decode(dataUrl.substring(comma + 1)) // throws IllegalArgumentException
    }

    /** The file extension for an audio [mime] type, so the player can sniff the container: mp3, wav, ogg, else bin. */
    fun audioExtension(mime: String): String = when (mime.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/ogg", "audio/opus" -> "ogg"
        else -> "bin"
    }

    private const val PREFIX = "data:"
}
