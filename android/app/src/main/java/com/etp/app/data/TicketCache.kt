package com.etp.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class CachedTickets(val fetchedAt: Long, val tickets: List<Ticket>)

/**
 * Offline ticket wallet: a plain JSON file (no Room — the access pattern is
 * read-all/write-all with zero queries). Cleared on logout so a second
 * account never sees the first one's tickets.
 */
class TicketCache(context: Context) {
    private val file = File(context.filesDir, "tickets_cache.json")

    suspend fun write(tickets: List<Ticket>) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(apiJson.encodeToString(CachedTickets(System.currentTimeMillis(), tickets)))
        }
    }

    /** Returns (tickets, fetchedAt millis) or null when absent/corrupt. */
    suspend fun read(): Pair<List<Ticket>, Long>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val cached = apiJson.decodeFromString<CachedTickets>(file.readText())
            cached.tickets to cached.fetchedAt
        }.getOrElse {
            file.delete()
            null
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { runCatching { file.delete() } }
}
