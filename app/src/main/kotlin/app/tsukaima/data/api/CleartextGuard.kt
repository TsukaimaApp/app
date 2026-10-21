package app.tsukaima.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refuses plain-http requests to hosts [allowed] does not trust ([TrustedHost.allowsCleartext]) before anything is
 * sent, with an [ApiException] carrying [MESSAGE]. https always passes.
 *
 * Installed on the shared client as an application interceptor (refused before a connection is opened) and as a
 * network interceptor (also sees each redirect hop, so an https server cannot bounce a request to public http).
 */
class CleartextGuard(private val allowed: (HttpUrl) -> Boolean = TrustedHost::allowsCleartext) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (refuses(url, allowed)) throw refused()
        return chain.proceed(chain.request())
    }

    companion object {
        const val MESSAGE = "Plain http is only allowed for LAN, tailnet and single-label hosts — use https"

        fun refuses(url: HttpUrl, allowed: (HttpUrl) -> Boolean = TrustedHost::allowsCleartext): Boolean =
            !url.isHttps && !allowed(url)

        /** For a typed server URL: true when it parses and would be refused. An unparseable URL is not ours to judge. */
        fun refuses(url: String): Boolean = url.trim().toHttpUrlOrNull()?.let { refuses(it) } ?: false

        fun refused() = ApiException(0, MESSAGE, MESSAGE)
    }
}
