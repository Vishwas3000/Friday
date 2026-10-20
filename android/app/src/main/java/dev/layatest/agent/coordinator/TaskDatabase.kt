package dev.layatest.agent.coordinator

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TaskRecord::class, StepRecord::class, AttemptRecord::class,
        EvidenceRecord::class, FactRecord::class, EventRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao
}
