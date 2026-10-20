package labs.suchattai.tsukaima.data.api

import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adds the bearer token and, on a 401, re-runs the password login once (single-flight) and retries.
 *
 * Credentials are bound to the origin they were saved for ([CredentialStore.origin]). The bearer is attached,
 * and a re-login attempted, only for requests to that exact `scheme://host:port`; any other request goes out
 * without a bearer and its 401 is returned as-is. A re-login also requires the configured server to be that
 * origin (so the username matches) and, over cleartext http, a [cleartextReplayAllowed] host.
 *
 * OkHttp interceptors are blocking, so [login] is a plain blocking function executed on the OkHttp
 * thread; it is called with the stored origin as `url`. Requests tagged [NoAuth] are passed through untouched.
 */
class AuthInterceptor(
    private val creds: CredentialStore,
    private val settings: () -> Settings,
    private val cleartextReplayAllowed: (HttpUrl) -> Boolean = TrustedHost::allowsCleartextReplay,
    private val login: (url: String, username: String, password: String) -> String,
) : Interceptor {
    private val lock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(NoAuth::class.java) != null) return chain.proceed(request)

        val origin = creds.origin()?.toHttpUrlOrNull()
        if (origin == null || !sameOrigin(request.url, origin)) return chain.proceed(request)

        val usedToken = creds.accessToken()
        val first = chain.proceed(request.withBearer(usedToken))
        if (first.code != 401) return first
        val configured = settings().serverUrl.toHttpUrlOrNull()
        if (configured == null || !sameOrigin(configured, origin)) return first // origin is mid-switch: no replay
        first.close()

        val token = refreshToken(usedToken, origin)
        val retry = chain.proceed(request.withBearer(token))
        if (retry.code == 401) {
            retry.close()
            throw AuthException()
        }
        return retry
    }

    /** Credentials state at the time a re-login last failed with an auth error; null when none is latched. */
    private var failedLogin: Pair<String?, String?>? = null

    /**
     * Returns a token newer than [staleToken]: either one a concurrent caller just stored, or a fresh login
     * against [origin]. After an auth-level login failure, later callers fail fast until the stored token or
     * password changes, so a wrong password cannot fan out into a burst of logins that trips the rate limit.
     */
    private fun refreshToken(staleToken: String?, origin: HttpUrl): String = lock.withLock {
        val current = creds.accessToken()
        if (current != null && current != staleToken) return@withLock current
        val password = creds.password() ?: throw AuthException("No stored credentials")
        if (!cleartextReplayAllowed(origin)) {
            throw AuthException("Sign in again — automatic login is disabled over unencrypted http")
        }
        if (failedLogin == current to password) throw AuthException("Re-login previously failed; credentials unchanged")
        val storedOrigin = creds.origin() ?: throw AuthException("No stored credentials")
        val fresh = try {
            login(storedOrigin, settings().username, password)
        } catch (e: AuthException) {
            failedLogin = current to password
            throw e
        }
        failedLogin = null
        // A sign-out that raced the login must win: never write the old credentials back.
        if (creds.password() != password || creds.origin() != storedOrigin) throw AuthException("Signed out")
        creds.save(password, fresh, storedOrigin)
        fresh
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun Request.withBearer(token: String?): Request =
        if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()
}
