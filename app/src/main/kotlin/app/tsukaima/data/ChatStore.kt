package app.tsukaima.data

import android.util.Log
import app.tsukaima.data.api.SessionDto
import app.tsukaima.domain.chat.OutboxMessage
import app.tsukaima.domain.chat.SavedTranscript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * What the phone keeps per chat so it is of use without Hermes: the transcript as last seen, the composer's draft,
 * and the messages written offline that still wait to be sent. A chat is named by its key: the stored session id,
 * or [NEW_CHAT] for the chat that has none yet. No call throws except on cancellation; a read that fails is "none".
 */
interface ChatStore {
    suspend fun transcript(key: String): SavedTranscript? = null
    suspend fun saveTranscript(key: String, transcript: SavedTranscript) {}
    suspend fun draft(key: String): String? = null

    /** Null or blank forgets the draft. */
    suspend fun saveDraft(key: String, text: String?) {}
    suspend fun outbox(key: String): List<OutboxMessage> = emptyList()
    suspend fun saveOutbox(key: String, messages: List<OutboxMessage>) {}

    /** The drawer's chat list as last loaded, for a start without Hermes; empty when none was kept. */
    suspend fun sessions(): List<SessionDto> = emptyList()
    suspend fun saveSessions(sessions: List<SessionDto>) {}

    /** Forgets everything kept for [key] (its session was deleted). */
    suspend fun forget(key: String) {}

    /** Forgets every chat (sign-out). */
    suspend fun clear() {}

    companion object {
        const val NEW_CHAT = "new"

        /** A store that keeps nothing. */
        val None: ChatStore = object : ChatStore {}
    }
}

/**
 * [ChatStore] as one JSON file per chat under [root]`/<scope>/`, where [scope] names the server and profile in use
 * (another server's chats are another folder). Files are app-private and not encrypted: they hold what the chat
 * screen shows. Writes go through a temp file and a rename, one at a time.
 */
class FileChatStore(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: () -> String,
) : ChatStore {
    @Serializable
    private data class Kept(val transcript: SavedTranscript? = null, val draft: String? = null, val outbox: List<OutboxMessage> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private fun file(key: String) = File(File(root, hash(scope())), hash(key) + ".json")

    private fun read(key: String): Kept = try {
        file(key).takeIf { it.isFile }?.let { json.decodeFromString(Kept.serializer(), it.readText()) } ?: Kept()
    } catch (e: Exception) {
        Log.w("Tsukaima", "chat store: unreadable entry dropped", e)
        Kept()
    }

    private suspend fun <T> get(key: String, pick: (Kept) -> T): T = withContext(io) { lock.withLock { pick(read(key)) } }

    private suspend fun change(key: String, block: (Kept) -> Kept) = withContext(io) {
        lock.withLock {
            try {
                val next = block(read(key))
                val target = file(key)
                if (next == Kept()) {
                    target.delete()
                } else {
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, target.name + ".tmp")
                    temp.writeText(json.encodeToString(Kept.serializer(), next))
                    if (!temp.renameTo(target)) {
                        target.delete()
                        temp.renameTo(target)
                    }
                }
            } catch (e: Exception) {
                Log.w("Tsukaima", "chat store: write failed", e)
            }
            Unit
        }
    }

    override suspend fun transcript(key: String) = get(key) { it.transcript }
    override suspend fun saveTranscript(key: String, transcript: SavedTranscript) = change(key) { it.copy(transcript = transcript) }
    override suspend fun draft(key: String) = get(key) { it.draft }
    override suspend fun saveDraft(key: String, text: String?) = change(key) { it.copy(draft = text?.takeIf(String::isNotBlank)) }
    override suspend fun outbox(key: String) = get(key) { it.outbox }
    override suspend fun saveOutbox(key: String, messages: List<OutboxMessage>) = change(key) { it.copy(outbox = messages) }
    private fun sessionsFile() = File(File(root, hash(scope())), "sessions.json")

    override suspend fun sessions(): List<SessionDto> = withContext(io) {
        lock.withLock {
            try {
                sessionsFile().takeIf { it.isFile }?.let { json.decodeFromString(ListSerializer(SessionDto.serializer()), it.readText()) }.orEmpty()
            } catch (e: Exception) {
                Log.w("Tsukaima", "chat store: unreadable session list dropped", e)
                emptyList()
            }
        }
    }

    override suspend fun saveSessions(sessions: List<SessionDto>) = withContext(io) {
        lock.withLock {
            try {
                val target = sessionsFile()
                target.parentFile?.mkdirs()
                target.writeText(json.encodeToString(ListSerializer(SessionDto.serializer()), sessions.take(MAX_SESSIONS)))
            } catch (e: Exception) {
                Log.w("Tsukaima", "chat store: session list not saved", e)
            }
            Unit
        }
    }

    override suspend fun forget(key: String) = withContext(io) { lock.withLock { file(key).delete() }; Unit }
    override suspend fun clear() = withContext(io) { lock.withLock { root.deleteRecursively() }; Unit }

    private companion object {
        /** The drawer's first page: what a start without Hermes lists. */
        const val MAX_SESSIONS = 100
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
