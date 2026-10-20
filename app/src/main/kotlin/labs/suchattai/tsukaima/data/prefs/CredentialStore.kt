package labs.suchattai.tsukaima.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface CredentialStore {
    fun password(): String?
    fun accessToken(): String?

    /** Normalized server URL (`scheme://host[:port]`) the password and token belong to. */
    fun origin(): String?

    /** [origin] and [accessToken] in one consistent read: a concurrent [save] or [clear] lands wholly before or after it. */
    fun originAndToken(): OriginToken

    /** Stores credentials bound to [origin]; they are only ever sent to that origin. */
    fun save(password: String?, accessToken: String?, origin: String?)
    fun clear()
}

data class OriginToken(val origin: String?, val accessToken: String?)

/** Thin wrapper over EncryptedSharedPreferences; Android-only, exercised via [InMemoryCredentialStore] in tests. */
class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = encryptedPrefs(context)

    override fun password(): String? = prefs.getString(PASSWORD, null)

    override fun accessToken(): String? = prefs.getString(ACCESS_TOKEN, null)

    override fun origin(): String? = prefs.getString(ORIGIN, null)

    /** One `getAll()`: SharedPreferences applies an edit to its in-memory map atomically, so this is a snapshot. */
    override fun originAndToken(): OriginToken {
        val all = prefs.all
        return OriginToken(all[ORIGIN] as? String, all[ACCESS_TOKEN] as? String)
    }

    override fun save(password: String?, accessToken: String?, origin: String?) {
        prefs.edit {
            putOrRemove(PASSWORD, password)
            putOrRemove(ACCESS_TOKEN, accessToken)
            putOrRemove(ORIGIN, origin)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val PASSWORD = "password"
        const val ACCESS_TOKEN = "access_token"
        const val ORIGIN = "origin"

        /**
         * security-crypto is deprecated upstream without a drop-in successor; it stays the specified store (moving
         * the stored credentials to another scheme is a migration of its own), so the deprecation is suppressed here
         * and only here.
         */
        @Suppress("DEPRECATION")
        fun encryptedPrefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            "tsukaima_creds",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/** Process-lifetime store: tests, and the fallback when the encrypted store cannot be built. Thread-safe. */
class InMemoryCredentialStore : CredentialStore {
    private var password: String? = null
    private var accessToken: String? = null
    private var origin: String? = null

    @Synchronized
    override fun password(): String? = password

    @Synchronized
    override fun accessToken(): String? = accessToken

    @Synchronized
    override fun origin(): String? = origin

    @Synchronized
    override fun originAndToken(): OriginToken = OriginToken(origin, accessToken)

    @Synchronized
    override fun save(password: String?, accessToken: String?, origin: String?) {
        this.password = password
        this.accessToken = accessToken
        this.origin = origin
    }

    @Synchronized
    override fun clear() {
        password = null
        accessToken = null
        origin = null
    }
}

/**
 * Defers building the real store until first use, and survives a store that cannot be built.
 *
 * [EncryptedCredentialStore] touches the Android Keystore: slow, and it can throw (corrupted keyset,
 * broken Keystore on some devices). [create] runs at most once, on whichever thread first reads or writes
 * credentials (callers keep that off the main thread). If it throws, [onFallback] is told and an
 * [InMemoryCredentialStore] is used for the rest of the process, so the app still starts and the user can
 * sign in again (credentials then do not persist across restarts).
 */
class LazyCredentialStore(
    create: () -> CredentialStore,
    onFallback: (Throwable) -> Unit,
) : CredentialStore {
    private val delegate: CredentialStore by lazy {
        try {
            create()
        } catch (e: Exception) {
            onFallback(e)
            InMemoryCredentialStore()
        }
    }

    override fun password(): String? = delegate.password()

    override fun accessToken(): String? = delegate.accessToken()

    override fun origin(): String? = delegate.origin()

    override fun originAndToken(): OriginToken = delegate.originAndToken()

    override fun save(password: String?, accessToken: String?, origin: String?) =
        delegate.save(password, accessToken, origin)

    override fun clear() = delegate.clear()
}
