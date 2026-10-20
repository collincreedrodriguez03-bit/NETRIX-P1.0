package com.example.netrix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NetrixDao {

    // Survey Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SurveySessionEntity)

    @Update
    suspend fun updateSession(session: SurveySessionEntity)

    @Query("SELECT * FROM survey_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SurveySessionEntity>>

    @Query("SELECT * FROM survey_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SurveySessionEntity?

    @Query("SELECT * FROM survey_sessions WHERE status = 'ACTIVE' ORDER BY startTime DESC LIMIT 1")
    fun getActiveSession(): Flow<SurveySessionEntity?>

    @Query("UPDATE survey_sessions SET status = :status, endTime = :endTime, totalSamples = :samples, deadZonesCount = :deadZones, coverageScore = :coverage WHERE id = :sessionId")
    suspend fun finalizeSession(sessionId: String, status: String, endTime: Long, samples: Int, deadZones: Int, coverage: Float)

    @Query("DELETE FROM survey_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    // Spatial Samples
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: SpatialSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<SpatialSampleEntity>)

    @Query("SELECT * FROM spatial_samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getSamplesForSession(sessionId: String): Flow<List<SpatialSampleEntity>>

    @Query("SELECT * FROM spatial_samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSamplesListForSession(sessionId: String): List<SpatialSampleEntity>

    @Query("SELECT COUNT(*) FROM spatial_samples WHERE sessionId = :sessionId")
    suspend fun getSampleCountForSession(sessionId: String): Int

    // Network Observations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObservation(observation: NetworkObservationEntity)

    @Query("SELECT * FROM network_observations ORDER BY timestamp DESC LIMIT 500")
    fun getRecentObservations(): Flow<List<NetworkObservationEntity>>

    @Query("SELECT * FROM network_observations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getObservationsForSession(sessionId: String): List<NetworkObservationEntity>

    // Diagnostic Probes
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProbeResult(result: ActiveProbeResultEntity)

    @Query("SELECT * FROM probe_results ORDER BY timestamp DESC LIMIT 100")
    fun getRecentProbeResults(): Flow<List<ActiveProbeResultEntity>>

    @Query("DELETE FROM probe_results")
    suspend fun clearProbeResults()
}
