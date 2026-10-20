package labs.suchattai.tsukaima.data.api

import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adds the bearer token and, on a 401, re-runs the password login once (single-flight) and retries.
 *
 * OkHttp interceptors are blocking, so [login] is a plain blocking function executed on the OkHttp
 * thread. Requests tagged [NoAuth] are passed through untouched.
 */
class AuthInterceptor(
    private val creds: CredentialStore,
    private val settings: () -> Settings,
    private val login: (url: String, username: String, password: String) -> String,
) : Interceptor {
    private val lock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(NoAuth::class.java) != null) return chain.proceed(request)

        val usedToken = creds.accessToken()
        val first = chain.proceed(request.withBearer(usedToken))
        if (first.code != 401) return first
        first.close()

        val token = refreshToken(usedToken)
        val retry = chain.proceed(request.withBearer(token))
        if (retry.code == 401) {
            retry.close()
            throw AuthException()
        }
        return retry
    }

    /** Returns a token newer than [staleToken]: either one a concurrent caller just stored, or a fresh login. */
    private fun refreshToken(staleToken: String?): String = lock.withLock {
        val current = creds.accessToken()
        if (current != null && current != staleToken) return@withLock current
        val password = creds.password() ?: throw AuthException("No stored credentials")
        val s = settings()
        val fresh = login(s.serverUrl, s.username, password)
        creds.save(password, fresh)
        fresh
    }

    private fun Request.withBearer(token: String?): Request =
        if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()
}
