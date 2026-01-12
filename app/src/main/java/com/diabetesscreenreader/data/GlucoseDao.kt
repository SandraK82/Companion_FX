package com.diabetesscreenreader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: GlucoseReading): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<GlucoseReading>)

    @Update
    suspend fun update(reading: GlucoseReading)

    @Delete
    suspend fun delete(reading: GlucoseReading)

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReading(): GlucoseReading?

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReadingFlow(): Flow<GlucoseReading?>

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestReadings(limit: Int): List<GlucoseReading>

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getLatestReadingsFlow(limit: Int): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM glucose_readings WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getReadingsInRange(startTime: Long, endTime: Long): List<GlucoseReading>

    @Query("SELECT * FROM glucose_readings WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getReadingsInRangeFlow(startTime: Long, endTime: Long): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM glucose_readings WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getReadingsSince(since: Long): List<GlucoseReading>

    @Query("SELECT * FROM glucose_readings WHERE uploadedToNightscout = 0 ORDER BY timestamp DESC")
    suspend fun getUnuploadedReadings(): List<GlucoseReading>

    @Query("SELECT * FROM glucose_readings WHERE uploadedToNightscout = 0 ORDER BY timestamp DESC")
    fun getUnuploadedReadingsFlow(): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getAllReadingsFlow(limit: Int): Flow<List<GlucoseReading>>

    @Query("UPDATE glucose_readings SET uploadedToNightscout = 1, nightscoutId = :nightscoutId WHERE id = :id")
    suspend fun markAsUploaded(id: Long, nightscoutId: String)

    @Query("SELECT COUNT(*) FROM glucose_readings")
    suspend fun getReadingsCount(): Int

    @Query("SELECT AVG(value) FROM glucose_readings WHERE timestamp BETWEEN :startTime AND :endTime AND unit = :unit")
    suspend fun getAverageInRange(startTime: Long, endTime: Long, unit: GlucoseUnit): Double?

    @Query("DELETE FROM glucose_readings WHERE timestamp < :threshold")
    suspend fun deleteOldReadings(threshold: Long): Int

    @Query("SELECT * FROM glucose_readings WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getReadingsSinceFlow(since: Long): Flow<List<GlucoseReading>>

    @Query("UPDATE glucose_readings SET uploadedToNightscout = 1, nightscoutId = 'cleared' WHERE uploadedToNightscout = 0")
    suspend fun clearUploadQueue(): Int
}
