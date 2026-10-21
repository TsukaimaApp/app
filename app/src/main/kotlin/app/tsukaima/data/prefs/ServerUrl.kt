package app.tsukaima.data.prefs

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * Reduces user input to `scheme://host[:port]`: prepends `http://` when no scheme is given,
     * lowercases the host, drops path/query/fragment and default ports. Returns null if the
     * input is not a valid http(s) URL with a non-empty host.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (schemePrefix.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://$host$port"
    }
}
