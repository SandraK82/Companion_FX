package com.diabetesscreenreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompanionEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(event: CompanionEventEntity): Long

    @Query(
        "SELECT * FROM companion_events " +
            "WHERE state IN ('pending', 'failed') " +
            "AND uploadAllowed = 1 " +
            "AND confirmationState != 'pending' " +
            "ORDER BY eventTimestamp ASC LIMIT :limit"
    )
    suspend fun getPending(limit: Int): List<CompanionEventEntity>

    @Query("SELECT * FROM companion_events WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): CompanionEventEntity?

    /**
     * CamAPS reports the last bolus as an age in whole minutes. As the next
     * screen read arrives, the reconstructed timestamp can move by a few
     * seconds across a minute boundary. Treat the same amount in a short
     * neighbourhood as the same observed bolus so that this presentation
     * jitter cannot create a second treatment row.
     */
    @Query(
        "SELECT * FROM companion_events " +
            "WHERE eventType = :eventType AND amount = :amount " +
            "AND ABS(eventTimestamp - :eventTimestamp) <= :windowMs " +
            "ORDER BY ABS(eventTimestamp - :eventTimestamp) ASC LIMIT 1"
    )
    suspend fun findRecentByTypeAndAmount(
        eventType: String,
        amount: Double,
        eventTimestamp: Long,
        windowMs: Long
    ): CompanionEventEntity?

    @Query(
        "SELECT * FROM companion_events " +
            "WHERE eventType = :eventType " +
            "AND ABS(eventTimestamp - :eventTimestamp) <= :windowMs " +
            "ORDER BY ABS(eventTimestamp - :eventTimestamp) ASC LIMIT 1"
    )
    suspend fun findRecentByType(
        eventType: String,
        eventTimestamp: Long,
        windowMs: Long
    ): CompanionEventEntity?

    @Query("UPDATE companion_events SET attemptCount = attemptCount + 1, lastAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markAttempt(id: Long, attemptedAt: Long)

    @Query("UPDATE companion_events SET state = 'uploaded', serverId = :serverId, lastError = NULL WHERE id = :id")
    suspend fun markUploaded(id: Long, serverId: String?)

    @Query("UPDATE companion_events SET state = 'failed', lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("SELECT COUNT(*) FROM companion_events WHERE state IN ('pending', 'failed')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM companion_events ORDER BY eventTimestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<CompanionEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveObservation(state: CompanionObservationStateEntity)

    @Query("SELECT * FROM companion_observation_state WHERE `key` = :key LIMIT 1")
    suspend fun getObservation(key: String): CompanionObservationStateEntity?
}
