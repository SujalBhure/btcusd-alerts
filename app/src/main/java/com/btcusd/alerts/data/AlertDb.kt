package com.btcusd.alerts.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "alerts")
data class Alert(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String = "BTCUSD",
    val targetPrice: Double,
    /** "above" fires when lastPrice >= target, "below" when <= target */
    val direction: String,
    val oneShot: Boolean = true,
    val active: Boolean = true,
    val snoozeUntilMs: Long = 0L,
    val lastFiredMs: Long = 0L
)

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY targetPrice ASC")
    fun observe(): Flow<List<Alert>>
    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun active(): List<Alert>
    @Insert suspend fun insert(a: Alert): Long
    @Delete suspend fun delete(a: Alert)
    @Query("UPDATE alerts SET active = :on WHERE id = :id")
    suspend fun setActive(id: Long, on: Boolean)
    @Query("UPDATE alerts SET snoozeUntilMs = :until WHERE id = :id")
    suspend fun snooze(id: Long, until: Long)
    @Query("UPDATE alerts SET lastFiredMs = :now, active = CASE WHEN oneShot = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun markFired(id: Long, now: Long)
}

@Database(entities = [Alert::class], version = 1, exportSchema = false)
abstract class AlertDb : RoomDatabase() {
    abstract fun dao(): AlertDao
}
