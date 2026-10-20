package labs.suchattai.tsukaima.data.api

import java.io.IOException

/** Non-2xx response that is not an authentication failure. [message] includes a truncated response body. */
class ApiException(val code: Int, message: String) : IOException(message)

/** 401 after a re-login attempt failed, or no stored credentials to re-login with. */
class AuthException(message: String = "Authentication failed") : IOException(message)

/** Request tag: skip the bearer header and the 401 re-login retry (login, health, status, auth providers). */
class NoAuth
