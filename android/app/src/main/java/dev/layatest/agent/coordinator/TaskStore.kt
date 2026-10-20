package dev.layatest.agent.coordinator

import android.content.Context
import androidx.room.Room
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArraySet

/** Database access stays on workers; listeners signal change on the committing thread. */
class TaskStore private constructor(private val database: TaskDatabase) {
    val dao: TaskDao = database.dao()
    private val observers = CopyOnWriteArraySet<() -> Unit>()

    fun <T> transaction(block: () -> T): T {
        val outer = !database.inTransaction()
        val result = database.runInTransaction(Callable { block() })
        if (outer) changed()
        return result
    }

    fun changed() { observers.forEach { listener -> runCatching { listener() } } }
    fun observe(listener: () -> Unit) { observers.add(listener) }
    fun removeObserver(listener: () -> Unit) { observers.remove(listener) }

    fun latestForChat(chatId: String): TaskSnapshot? = database.runInTransaction(Callable {
        dao.latestForChat(chatId)?.let { TaskSnapshot(it, dao.steps(it.id), dao.events(it.id)) }
    })

    fun snapshot(taskId: String): TaskSnapshot? = database.runInTransaction(Callable {
        dao.task(taskId)?.let { TaskSnapshot(it, dao.steps(taskId), dao.events(taskId)) }
    })

    companion object {
        @Volatile private var instance: TaskStore? = null

        fun get(context: Context): TaskStore = instance ?: synchronized(this) {
            instance ?: TaskStore(Room.databaseBuilder(
                context.applicationContext, TaskDatabase::class.java, "friday_tasks.db",
            ).build()).also { instance = it }
        }
    }
}
