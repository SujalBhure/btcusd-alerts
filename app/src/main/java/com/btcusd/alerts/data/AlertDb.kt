package com.btcusd.alerts.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
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
    /** per-alert ringtone internal file URI string; null = use alertType or universal ringtone */
    val ringtoneUri: String? = null,
    /** Alert type: ENTRY, SL, TP, LEVEL, or user custom string */
    val alertType: String = "LEVEL",
    /** Trigger condition: CROSS, RETEST, PINBAR, ENGULFING, ANY_TRIGGER */
    val triggerCondition: String = TriggerDetector.COND_CROSS,
    /** Candle timeframe for pattern/retest detection: "5", "15", "60", "240" */
    val timeframe: String = "15",
    /** Unique UUID string for two-way synchronization with PC app */
    val syncId: String = "",
    /** Timestamp in epoch ms of last creation/edit/toggle */
    val updatedAtMs: Long = 0L
)

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY targetPrice ASC")
    fun observe(): Flow<List<Alert>>
    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun active(): List<Alert>
    @Query("SELECT * FROM alerts")
    suspend fun getAllList(): List<Alert>
    @Insert suspend fun insert(a: Alert): Long
    @Update suspend fun update(a: Alert)
    @Delete suspend fun delete(a: Alert)
    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM alerts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Alert?
    @Query("SELECT * FROM alerts WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Alert?
    @Query("DELETE FROM alerts WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)
    @Query("SELECT syncId FROM deleted_alerts")
    suspend fun getDeletedSyncIds(): List<String>
    @Query("INSERT OR REPLACE INTO deleted_alerts (syncId, deletedAtMs) VALUES (:syncId, :now)")
    suspend fun recordDeleted(syncId: String, now: Long)
    @Query("UPDATE alerts SET active = :on, updatedAtMs = :now WHERE id = :id")
    suspend fun setActiveWithTime(id: Long, on: Boolean, now: Long)
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alerts ADD COLUMN alertType TEXT NOT NULL DEFAULT 'LEVEL'")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alerts ADD COLUMN triggerCondition TEXT NOT NULL DEFAULT 'CROSS'")
        db.execSQL("ALTER TABLE alerts ADD COLUMN timeframe TEXT NOT NULL DEFAULT '15'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alerts ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE alerts ADD COLUMN updatedAtMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_alerts (syncId TEXT PRIMARY KEY NOT NULL, deletedAtMs INTEGER NOT NULL)")
    }
}

@Database(entities = [Alert::class], version = 5, exportSchema = false)
abstract class AlertDb : RoomDatabase() {
    abstract fun dao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: AlertDb? = null

        fun get(context: Context): AlertDb {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(
                    context.applicationContext,
                    AlertDb::class.java,
                    "alerts.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = inst
                inst
            }
        }
    }
}
