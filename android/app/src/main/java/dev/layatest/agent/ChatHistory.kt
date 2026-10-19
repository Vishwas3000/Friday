package dev.layatest.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArraySet
import java.util.UUID

data class ChatSession(val id: String, val title: String, val time: Long)

/** Completed turns use the existing private request log; observers receive append/clear events, not a timer. */
object ChatHistory {
    private const val LIMIT = 100
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private fun file(ctx: Context) = File(ctx.filesDir, Assistant.REQUEST_LOG)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("friday_chats", Context.MODE_PRIVATE)

    fun activeId(ctx: Context): String = prefs(ctx).getString("active", "legacy")?.takeIf { it.isNotBlank() } ?: "legacy"

    fun newSession(ctx: Context): String {
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            val metadata = metadata(ctx).put(id, System.currentTimeMillis())
            prefs(ctx).edit().putString("sessions", metadata.toString()).putString("active", id).apply()
        }
        changed()
        return id
    }

    fun selectSession(ctx: Context, id: String): Boolean {
        synchronized(this) {
            if (sessions(ctx).none { it.id == id }) return false
            prefs(ctx).edit().putString("active", id).apply()
        }
        changed()
        return true
    }

    private fun metadata(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString("sessions", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun chatId(record: JSONObject): String = (record.opt("chat_id") as? String)?.takeIf { it.isNotBlank() } ?: "legacy"

    fun append(ctx: Context, record: JSONObject) {
        synchronized(this) {
            if ((record.opt("chat_id") as? String).isNullOrBlank()) record.put("chat_id", activeId(ctx))
            val log = file(ctx)
            val separator = log.length() > 0 && RandomAccessFile(log, "r").use { it.seek(it.length() - 1); it.readByte().toInt() != 10 }
            log.appendText((if (separator) "\n" else "") + record.toString() + "\n")
        }
        changed()
    }

    fun entries(ctx: Context): List<JSONObject> = synchronized(this) {
        val id = activeId(ctx)
        entriesAll(ctx).filter { chatId(it) == id }.takeLast(LIMIT)
    }

    fun entriesAll(ctx: Context): List<JSONObject> = synchronized(this) {
        val recent = mutableListOf<JSONObject>()
        val log = file(ctx)
        if (log.exists()) log.useLines { lines -> lines.forEach { line ->
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if (entry.opt("request") !is String || entry.opt("answer") !is String) return@forEach
            if (entry.getString("request").isBlank() && entry.getString("answer").isBlank()) return@forEach
            recent.add(entry)
        } }
        recent.toList()
    }

    fun sessions(ctx: Context): List<ChatSession> = synchronized(this) {
        val all = entriesAll(ctx).groupBy { chatId(it) }
        val metadata = metadata(ctx)
        val ids = linkedSetOf(activeId(ctx))
        ids.addAll(all.keys)
        metadata.keys().forEach { ids.add(it) }
        ids.map { id ->
            val entries = all[id].orEmpty()
            val title = entries.firstOrNull()?.optString("request")?.replace(Regex("\\s+"), " ")?.trim()
                ?.take(64)?.takeIf { it.isNotBlank() } ?: if (id == "legacy") "Earlier conversation" else "New chat"
            ChatSession(id, title, entries.maxOfOrNull { it.optLong("time") } ?: metadata.optLong(id))
        }.sortedByDescending { it.time }
    }

    fun observe(listener: () -> Unit) { observers.add(listener) }
    fun removeObserver(listener: () -> Unit) { observers.remove(listener) }

    fun clear(ctx: Context): Boolean {
        val cleared = synchronized(this) {
            runCatching {
                val log = file(ctx)
                if (log.exists()) {
                    val id = activeId(ctx)
                    // Keep other chats and malformed rows intact; only remove identifiable turns in this chat.
                    val kept = log.readLines().filter { line ->
                        val record = runCatching { JSONObject(line) }.getOrNull()
                        record == null || chatId(record) != id
                    }
                    val staged = File(log.parentFile, log.name + ".clear")
                    staged.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
                    check(staged.renameTo(log)) { "Could not replace chat history" }
                }
                true
            }.getOrDefault(false)
        }
        if (cleared) changed()
        return cleared
    }

    private fun changed() { observers.forEach { runCatching { it() } } }
}
