package app.tsukaima.share

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tsukaima.MainActivity
import app.tsukaima.TsukaimaApp
import app.tsukaima.di.AppContainer
import app.tsukaima.notifications.ApprovalNotifier
import app.tsukaima.notifications.DeepLink
import app.tsukaima.ui.chat.Attachments
import app.tsukaima.ui.chat.ContentPickedFile
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.isDarkTheme
import java.util.Collections

/**
 * "Send to Tsukaima" in the system share sheet: text, images and PDFs from another app, in a translucent window over
 * it. The streams are copied into the app's cache here, while the sender's grant is valid ([SharedFileStore]); then
 * one [ShareCard] asks where they go (always shown: nothing reaches Hermes without a tap; without a last chat it
 * offers "New chat" alone), and the choice is left on [AppContainer.pendingShare] for that chat, which
 * [MainActivity] opens. Back, a tap outside or leaving the window
 * cancel the share and delete the copies. Nothing is ever sent from here: the chat's composer only gets the draft.
 */
class ShareActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var model: ShareModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container = (application as TsukaimaApp).container
        model = ViewModelProvider(
            this,
            viewModelFactory { initializer { ShareModel(container, application) } },
        )[ShareModel::class.java]
        if (!model.started) {
            model.started = true
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val request = ShareIntake.parse(intent.action, intent.type, text, streams(intent))
            if (request == null) {
                toast(applicationContext, NOTHING)
                finish()
                return
            }
            model.prepare(request, fallbackMime = intent.type?.takeUnless { it.endsWith("/*") })
        }
        lifecycleScope.launch {
            if (model.prepared.filterNotNull().first().empty) finish()
        }
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val share by model.prepared.collectAsStateWithLifecycle()
            TsukaimaTheme(darkTheme = isDarkTheme(settings?.theme), dynamicColor = settings?.materialYou == true) {
                // While copying (a moment), only the see-through window.
                val ready = share?.takeIf { !it.empty } ?: return@TsukaimaTheme
                ShareCard(
                    summary = ShareIntake.summary(ready.text, ready.files),
                    hasLastChat = ready.lastChatId != null,
                    onLastChat = { deliver(ready, ShareTarget.LastChat) },
                    onNewChat = { deliver(ready, ShareTarget.NewChat) },
                    onDismiss = ::finish,
                )
            }
        }
    }

    /** Leaving the card (Home, another app) cancels the share; [ShareModel.onCleared] deletes the copies. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    /** Leaves the share for its chat and opens it (the last chat by its stored id, or a new one). */
    private fun deliver(share: PreparedShare, target: ShareTarget) {
        if (model.handedOff) return
        model.handedOff = true
        val sessionId = if (target == ShareTarget.LastChat) share.lastChatId else null
        val replaced = container.pendingShare.value
        container.pendingShare.value = SharedContent(share.text, share.files, target, sessionId)
        // A share no chat took yet (e.g. sent before sign-in) is superseded by this one.
        replaced?.let { old ->
            container.appScope.launch(Dispatchers.IO) { old.files.forEach(container.sharedFiles::delete) }
        }
        val open = Intent(this, MainActivity::class.java)
            .putExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN, ApprovalNotifier.SCREEN_CHAT)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        if (sessionId != null) open.putExtra(DeepLink.EXTRA_SESSION_ID, sessionId)
        startActivity(open)
        finish()
    }

    /** `EXTRA_STREAM` as URI strings: one for `SEND`, the list for `SEND_MULTIPLE`. */
    private fun streams(intent: Intent): List<String> {
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        return uris.map(Uri::toString)
    }

    internal companion object {
        const val NOTHING = "Nothing Tsukaima can take from that share"

        fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** A share with its streams copied: [lastChatId] is where "Continue last chat" goes (null: only "New chat"). */
internal data class PreparedShare(val text: String, val files: List<SharedFile>, val lastChatId: String?) {
    val empty: Boolean get() = text.isEmpty() && files.isEmpty()
}

/**
 * The share's copies, kept across a rotation: [prepare] copies the streams once; when the share ends without being
 * handed to a chat ([handedOff]), [onCleared] deletes every copy made.
 */
internal class ShareModel(private val container: AppContainer, private val app: Application) : ViewModel() {
    private val _prepared = MutableStateFlow<PreparedShare?>(null)
    val prepared: StateFlow<PreparedShare?> = _prepared.asStateFlow()

    /** [prepare] ran (not again after a configuration change). */
    var started = false

    /** The copies now belong to [AppContainer.pendingShare]. */
    var handedOff = false

    /** Every copy made, as it is made (the copying runs on IO), so a cancelled share deletes them all. */
    private val copies: MutableList<SharedFile> = Collections.synchronizedList(mutableListOf())

    fun prepare(request: ShareRequest, fallbackMime: String?) {
        viewModelScope.launch {
            val skipped = mutableListOf<String>() // toasts, one per stream left out
            val files = withContext(Dispatchers.IO) {
                try {
                    container.sharedFiles.prune()
                } catch (e: Exception) {
                    Log.w(TAG, "pruning shared files failed", e)
                }
                request.streams.mapNotNull { copy(app.contentResolver, it, fallbackMime, skipped) }
            }
            skipped.forEach { ShareActivity.toast(app, it) }
            val share = PreparedShare(request.text, files, container.settings.filterNotNull().first().lastChatId)
            if (share.empty && skipped.isEmpty()) ShareActivity.toast(app, ShareActivity.NOTHING)
            _prepared.value = share
        }
    }

    /**
     * One stream into the cache, or null: a URI that is not another app's `content:` one (a `file:` path, or this
     * app's own provider, would read Tsukaima's private files; see [ShareIntake.acceptsAuthority]), an unreadable
     * one, or one over the size cap. A named stream left out adds its toast to [skipped].
     */
    private fun copy(
        resolver: ContentResolver,
        raw: String,
        fallbackMime: String?,
        skipped: MutableList<String>,
    ): SharedFile? {
        val uri = raw.toUri()
        val authority = uri.authority
        if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
            !ShareIntake.acceptsAuthority(authority, authority?.let(::providerPackage), app.packageName)
        ) {
            Log.w(TAG, "share: ignored a stream that is not another app's content URI")
            return null
        }
        val picked = ContentPickedFile(resolver, uri)
        return try {
            val info = picked.describe()
            if (Attachments.tooLarge(info.size)) {
                skipped += "Skipped ${info.name}: over 12 MB"
                return null
            }
            val copied = container.sharedFiles.copy(info.name, info.mime ?: fallbackMime, info.size) { picked.open() }
            // Unreadable, or of unknown size and over the cap once read.
            if (copied == null) skipped += "Skipped ${info.name}" else copies += copied
            copied
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "share: couldn’t read a stream", e)
            null
        }
    }

    /** The package whose content provider [authority] names, or null when none resolves (or it is not visible). */
    private fun providerPackage(authority: String): String? = try {
        val pm = app.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveContentProvider(authority, 0)
        }
        info?.packageName
    } catch (e: RuntimeException) {
        Log.w(TAG, "share: resolving a provider failed", e)
        null
    }

    override fun onCleared() {
        if (handedOff) return
        val made = synchronized(copies) { copies.toList() }
        container.appScope.launch(Dispatchers.IO) { made.forEach(container.sharedFiles::delete) }
    }

    private companion object {
        const val TAG = "Tsukaima"
    }
}
