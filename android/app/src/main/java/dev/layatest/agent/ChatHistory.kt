package dev.layatest.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
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
            appendLocked(ctx, record)
        }
        changed()
    }

    /** A task can retry publication after a crash without adding the same completed turn twice. */
    fun appendTaskResult(ctx: Context, record: JSONObject, publicationKey: String): Boolean {
        require(!(record.opt("chat_id") as? String).isNullOrBlank()) { "Task results require an explicit chat_id" }
        require(publicationKey.isNotBlank()) { "Task results require a publication key" }
        synchronized(this) {
            val log = file(ctx)
            if (log.exists() && log.useLines { lines -> lines.any { line ->
                runCatching { JSONObject(line).opt("publication_key") == publicationKey }.getOrDefault(false)
            } }) return true
            record.put("publication_key", publicationKey)
            appendLocked(ctx, record)
        }
        changed()
        return true
    }

    /** Correct one published task turn only when its identity and original answer still match. */
    fun replaceTaskResult(ctx: Context, record: JSONObject, publicationKey: String,
                          replacesKey: String, expectedAnswerSha256: String): Boolean {
        val chat = (record.opt("chat_id") as? String)?.takeIf { it.isNotBlank() }
        val task = (record.opt("task_id") as? String)?.takeIf { it.isNotBlank() }
        require(chat != null && task != null) { "Task replacement requires explicit chat_id and task_id" }
        require(publicationKey.isNotBlank() && replacesKey.isNotBlank() && publicationKey != replacesKey) {
            "Task replacement requires distinct publication keys"
        }
        val answer = record.opt("answer") as? String ?: return false
        synchronized(this) {
            val log = file(ctx)
            if (!log.exists()) return false
            val bytes = log.readBytes()
            val rows = mutableListOf<Triple<Int, Int, JSONObject>>()
            var start = 0
            for (end in 0..bytes.size) {
                if (end != bytes.size && bytes[end] != 10.toByte()) continue
                val bodyEnd = if (end > start && bytes[end - 1] == 13.toByte()) end - 1 else end
                val row = runCatching { JSONObject(String(bytes, start, bodyEnd - start, Charsets.UTF_8)) }.getOrNull()
                if (row != null) rows.add(Triple(start, bodyEnd, row))
                start = end + 1
            }
            fun sameTask(row: JSONObject) = row.opt("chat_id") == chat && row.opt("task_id") == task
            val existing = rows.filter { it.third.opt("publication_key") == publicationKey }
            if (existing.isNotEmpty()) return existing.size == 1 && sameTask(existing.single().third) &&
                existing.single().third.opt("answer") == answer
            val previous = rows.filter { it.third.opt("publication_key") == replacesKey }
            if (previous.size != 1) return false
            val (from, until, old) = previous.single()
            val oldAnswer = old.opt("answer") as? String ?: return false
            val hash = MessageDigest.getInstance("SHA-256").digest(oldAnswer.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!sameTask(old) || hash != expectedAnswerSha256) return false
            val replacement = JSONObject(record.toString()).put("publication_key", publicationKey)
                .toString().toByteArray(Charsets.UTF_8)
            val staged = File(log.parentFile, log.name + ".replace")
            try {
                FileOutputStream(staged).use { stream ->
                    stream.write(bytes, 0, from)
                    stream.write(replacement)
                    stream.write(bytes, until, bytes.size - until)
                    stream.flush()
                    stream.fd.sync()
                }
                if (!staged.renameTo(log)) return false
            } finally { if (staged.exists()) staged.delete() }
        }
        changed()
        return true
    }

    private fun appendLocked(ctx: Context, record: JSONObject) {
        val log = file(ctx)
        val separator = log.length() > 0 && RandomAccessFile(log, "r").use { it.seek(it.length() - 1); it.readByte().toInt() != 10 }
        val bytes = ((if (separator) "\n" else "") + record.toString() + "\n").toByteArray(Charsets.UTF_8)
        FileOutputStream(log, true).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
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

    fun clear(ctx: Context, chatId: String = activeId(ctx)): Boolean {
        val cleared = synchronized(this) {
            runCatching {
                val log = file(ctx)
                if (log.exists()) {
                    val id = chatId
                    // Keep other chats and malformed rows intact; only remove identifiable turns in this chat.
                    val kept = log.readLines().filter { line ->
                        val record = runCatching { JSONObject(line) }.getOrNull()
                        record == null || ChatHistory.chatId(record) != id
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
