package dev.layatest.agent.coordinator

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

/** Synchronous operations for the coordinator's worker thread. */
@Dao
interface TaskDao {
    @Insert fun insertTask(task: TaskRecord)
    @Insert fun insertSteps(steps: List<StepRecord>)
    @Insert fun insertAttempt(attempt: AttemptRecord)
    @Insert fun insertEvidence(evidence: EvidenceRecord)
    @Insert fun insertEvent(event: EventRecord): Long
    @Update fun updateTask(task: TaskRecord)
    @Update fun updateStep(step: StepRecord)
    @Update fun updateAttempt(attempt: AttemptRecord)
    @Upsert fun upsertFacts(facts: List<FactRecord>)

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted = 0 LIMIT 1")
    fun task(id: String): TaskRecord?

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position, id")
    fun steps(taskId: String): List<StepRecord>

    @Query("SELECT * FROM events WHERE taskId = :taskId ORDER BY id")
    fun events(taskId: String): List<EventRecord>

    @Query("SELECT * FROM attempts WHERE taskId = :taskId ORDER BY startedAt, id")
    fun attempts(taskId: String): List<AttemptRecord>

    @Query("SELECT * FROM evidence WHERE taskId = :taskId ORDER BY capturedAt, id")
    fun evidence(taskId: String): List<EvidenceRecord>

    @Query("SELECT * FROM facts WHERE taskId = :taskId ORDER BY `key`")
    fun facts(taskId: String): List<FactRecord>

    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND deleted = 0 ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun latestForChat(chatId: String): TaskRecord?

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'UNSUPPORTED') ORDER BY createdAt, id")
    fun recoverableTasks(): List<TaskRecord>

    @Query("SELECT * FROM tasks WHERE published = 0 AND answer IS NOT NULL AND deleted = 0 ORDER BY createdAt, id")
    fun pendingPublications(): List<TaskRecord>

    @Query("DELETE FROM tasks WHERE chatId = :chatId")
    fun deleteForChat(chatId: String): Int
}
