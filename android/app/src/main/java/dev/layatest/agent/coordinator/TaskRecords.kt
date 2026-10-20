package dev.layatest.agent.coordinator

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tasks", indices = [Index("chatId")])
data class TaskRecord(
    @PrimaryKey val id: String,
    val chatId: String,
    val request: String,
    val capability: String = "research",
    val provider: String = "com.google.android.apps.bard",
    val revision: Int = 1,
    val cancelEpoch: Int = 0,
    val executionId: String? = null,
    val state: String = "QUEUED",
    val payload: String = "{}",
    val answer: String? = null,
    val evidence: String = "{}",
    val message: String = "",
    val publicationKey: String? = null,
    val published: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val activeMs: Long = 0,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(
        entity = TaskRecord::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class StepRecord(
    @PrimaryKey val id: String,
    val taskId: String,
    val position: Int,
    val capability: String,
    val title: String,
    val dependsOn: String = "[]",
    val state: String = "READY",
    val output: String = "{}",
    val evidence: String = "{}",
    val attempts: Int = 0,
    val activeMs: Long = 0,
    val updatedAt: Long,
)

@Entity(
    tableName = "attempts",
    foreignKeys = [
        ForeignKey(entity = TaskRecord::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StepRecord::class, parentColumns = ["id"], childColumns = ["stepId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("taskId"), Index("stepId")],
)
data class AttemptRecord(
    @PrimaryKey val id: String,
    val taskId: String,
    val stepId: String,
    val revision: Int,
    val cancelEpoch: Int,
    val number: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMs: Long = 0,
    val state: String = "RUNNING",
    val dispatchState: String = "NOT_DISPATCHED",
    val detail: String = "{}",
)

@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(entity = TaskRecord::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StepRecord::class, parentColumns = ["id"], childColumns = ["stepId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AttemptRecord::class, parentColumns = ["id"], childColumns = ["attemptId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("taskId"), Index("stepId"), Index("attemptId")],
)
data class EvidenceRecord(
    @PrimaryKey val id: String,
    val taskId: String,
    val stepId: String,
    val attemptId: String,
    val sourcePackage: String?,
    val capturedAt: Long,
    val scope: String,
    val content: String,
    val sha256: String,
)

@Entity(
    tableName = "facts",
    primaryKeys = ["taskId", "key"],
    foreignKeys = [ForeignKey(
        entity = TaskRecord::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class FactRecord(
    val taskId: String,
    val key: String,
    val type: String,
    val value: String,
    val provenance: String,
    val status: String,
    val sensitivity: String = "owner",
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(entity = TaskRecord::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StepRecord::class, parentColumns = ["id"], childColumns = ["stepId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("taskId"), Index("stepId")],
)
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val stepId: String? = null,
    val revision: Int,
    val kind: String,
    val state: String,
    val message: String,
    val durationMs: Long = 0,
    val createdAt: Long,
)

data class TaskSnapshot(
    val task: TaskRecord,
    val steps: List<StepRecord>,
    val events: List<EventRecord>,
)
