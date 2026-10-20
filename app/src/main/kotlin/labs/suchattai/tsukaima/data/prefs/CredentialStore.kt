@file:Suppress("DEPRECATION") // security-crypto is deprecated upstream but is the specified store

package labs.suchattai.tsukaima.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface CredentialStore {
    fun password(): String?
    fun accessToken(): String?
    fun save(password: String?, accessToken: String?)
    fun clear()
}

/** Thin wrapper over EncryptedSharedPreferences; Android-only, exercised via [InMemoryCredentialStore] in tests. */
class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "tsukaima_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun password(): String? = prefs.getString(PASSWORD, null)

    override fun accessToken(): String? = prefs.getString(ACCESS_TOKEN, null)

    override fun save(password: String?, accessToken: String?) {
        prefs.edit()
            .putOrRemove(PASSWORD, password)
            .putOrRemove(ACCESS_TOKEN, accessToken)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val PASSWORD = "password"
        const val ACCESS_TOKEN = "access_token"
    }
}

/** Process-lifetime store: tests, and the fallback when the encrypted store cannot be built. Thread-safe. */
class InMemoryCredentialStore : CredentialStore {
    private var password: String? = null
    private var accessToken: String? = null

    @Synchronized
    override fun password(): String? = password

    @Synchronized
    override fun accessToken(): String? = accessToken

    @Synchronized
    override fun save(password: String?, accessToken: String?) {
        this.password = password
        this.accessToken = accessToken
    }

    @Synchronized
    override fun clear() {
        password = null
        accessToken = null
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

    override fun save(password: String?, accessToken: String?) = delegate.save(password, accessToken)

    override fun clear() = delegate.clear()
}
