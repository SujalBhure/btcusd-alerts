package com.btcusd.alerts.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "alerts")
data class Alert(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String = "BTCUSD",
    val targetPrice: Double,
    /** legacy: kept for old rows, crossing logic ignores it */
    val direction: String = "cross",
    /** true = ring once then auto-delete on dismiss, false = ring every crossing */
    val oneShot: Boolean = true,
    val active: Boolean = true,
    val snoozeUntilMs: Long = 0L,
    val lastFiredMs: Long = 0L,
    /** per-alert ringtone content URI string; null = use universal ringtone */
    val ringtoneUri: String? = null
)

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY targetPrice ASC")
    fun observe(): Flow<List<Alert>>
    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun active(): List<Alert>
    @Insert suspend fun insert(a: Alert): Long
    @Delete suspend fun delete(a: Alert)
    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("UPDATE alerts SET active = :on WHERE id = :id")
    suspend fun setActive(id: Long, on: Boolean)
    @Query("UPDATE alerts SET snoozeUntilMs = :until, active = 1 WHERE id = :id")
    suspend fun snooze(id: Long, until: Long)
    @Query("UPDATE alerts SET lastFiredMs = :now, active = CASE WHEN oneShot = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun markFired(id: Long, now: Long)
    /** once-alerts that already rang but were never dismissed (notification swiped away) */
    @Query("DELETE FROM alerts WHERE oneShot = 1 AND active = 0 AND lastFiredMs != 0")
    suspend fun cleanupFiredOnce()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alerts ADD COLUMN ringtoneUri TEXT")
    }
}

@Database(entities = [Alert::class], version = 2, exportSchema = false)
abstract class AlertDb : RoomDatabase() {
    abstract fun dao(): AlertDao
}
