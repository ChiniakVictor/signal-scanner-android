package com.victor.signalscanner

import android.content.Context
import androidx.room.*

@Entity(tableName = "trackers")
data class TrackerRow(
    @PrimaryKey val trackerId: String,
    val type: String, val family: String, val detail: String, val address: String, val payload: String?,
    val separated: Boolean?, val firstSeen: Long, val lastSeen: Long, val trusted: Boolean = false, val alertedAt: Long = 0,
)

@Entity(tableName = "sightings", indices = [Index("trackerId"), Index("t")])
data class SightingRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackerId: String, val t: Long, val lat: Double?, val lon: Double?, val acc: Float?, val rssi: Int, val address: String,
)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM trackers WHERE trackerId = :id") fun get(id: String): TrackerRow?
    @Upsert fun upsert(r: TrackerRow)
    @Insert fun insert(s: SightingRow)
    @Query("SELECT * FROM trackers WHERE lastSeen >= :since ORDER BY lastSeen DESC") fun recent(since: Long): List<TrackerRow>
    @Query("SELECT * FROM sightings WHERE trackerId = :id AND t >= :since ORDER BY t") fun sightings(id: String, since: Long): List<SightingRow>
    @Query("UPDATE trackers SET trusted = :trusted WHERE trackerId = :id") fun setTrusted(id: String, trusted: Boolean)
    @Query("UPDATE trackers SET alertedAt = :t WHERE trackerId = :id") fun setAlerted(id: String, t: Long)
    @Query("DELETE FROM sightings WHERE t < :before") fun pruneSightings(before: Long)
    @Query("DELETE FROM trackers WHERE lastSeen < :before AND trusted = 0") fun pruneTrackers(before: Long)
    @Query("DELETE FROM trackers WHERE trackerId = :id") fun delete(id: String)
    @Query("DELETE FROM sightings WHERE trackerId = :id") fun deleteSightings(id: String)
}

@Database(entities = [TrackerRow::class, SightingRow::class], version = 1, exportSchema = false)
abstract class TrackerDb : RoomDatabase() {
    abstract fun dao(): TrackerDao
    companion object {
        @Volatile private var inst: TrackerDb? = null
        fun get(ctx: Context): TrackerDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, TrackerDb::class.java, "trackers.db").build().also { inst = it }
        }
    }
}
