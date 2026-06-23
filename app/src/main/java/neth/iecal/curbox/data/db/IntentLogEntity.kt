package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "intent_logs")
data class IntentLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val packageName: String,
    val intentText: String,
    val unlockedDurationMs: Long
)
