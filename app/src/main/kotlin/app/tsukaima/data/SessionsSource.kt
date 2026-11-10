package app.tsukaima.data

import app.tsukaima.data.api.DrawerSessions
import app.tsukaima.data.api.SessionDto

/** The session-list slice of [HermesRepository] the drawer needs; a seam for tests. Never throws. */
interface SessionsSource {
    suspend fun sessions(): Result<List<SessionDto>>

    /** The list with up to [chats] chats, most recently used first; the default knows only the first page. */
    suspend fun sessionsPage(chats: Int): Result<DrawerSessions> = sessions().map { DrawerSessions(it, more = false) }

    /** Pins or unpins a session. */
    suspend fun pin(id: String, pinned: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("pin"))
    suspend fun rename(id: String, title: String): Result<Unit>
    suspend fun archive(id: String, archived: Boolean): Result<Unit>
    suspend fun delete(id: String): Result<Unit>

    /** Server-side search (`/api/sessions/search`); rows carry a [SessionDto.snippet]. */
    suspend fun search(query: String): Result<List<SessionDto>>
}
