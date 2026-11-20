package app.mettle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Totals for a time window, used by the weekly snapshot and goals. */
data class PeriodTotals(
    val distanceM: Long,
    val movingS: Long,
    val elevGainM: Double,
    val floorsUp: Long,
    val count: Int,
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE deletedAt IS NULL ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 30): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun byId(id: String): Flow<ActivityEntity?>

    @Query(
        """SELECT COALESCE(SUM(distanceM),0) AS distanceM, COALESCE(SUM(movingS),0) AS movingS,
           COALESCE(SUM(elevGainM),0) AS elevGainM, COALESCE(SUM(floorsUp),0) AS floorsUp, COUNT(*) AS count
           FROM activities WHERE deletedAt IS NULL AND startedAt >= :fromMs AND startedAt < :toMs"""
    )
    fun totals(fromMs: Long, toMs: Long): Flow<PeriodTotals>

    @Query("SELECT * FROM activities WHERE deletedAt IS NULL AND startedAt >= :fromMs AND startedAt < :toMs ORDER BY startedAt")
    fun between(fromMs: Long, toMs: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE syncState = 'DIRTY'")
    suspend fun dirty(): List<ActivityEntity>

    @Upsert suspend fun upsert(activity: ActivityEntity)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: String): ActivityEntity?

    @Query("UPDATE activities SET syncState = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface LapDao {
    @Query("SELECT * FROM laps WHERE activityId = :activityId ORDER BY kind, idx")
    fun forActivity(activityId: String): Flow<List<LapEntity>>

    @Upsert suspend fun upsertAll(laps: List<LapEntity>)

    @Query("SELECT * FROM laps WHERE activityId = :activityId ORDER BY kind, idx")
    suspend fun forActivityNow(activityId: String): List<LapEntity>
}

@Dao
interface BestEffortDao {
    @Query("SELECT * FROM best_efforts WHERE activityId = :activityId")
    fun forActivity(activityId: String): Flow<List<BestEffortEntity>>

    @Query("SELECT MIN(timeS) FROM best_efforts WHERE effort = :effort")
    suspend fun best(effort: String): Int?

    @Upsert suspend fun upsertAll(efforts: List<BestEffortEntity>)

    @Query("SELECT * FROM best_efforts WHERE activityId = :activityId")
    suspend fun forActivityNow(activityId: String): List<BestEffortEntity>

    @Query("SELECT effort, MIN(timeS) AS timeS FROM best_efforts GROUP BY effort")
    fun bestPerEffort(): Flow<List<EffortBest>>
}

data class EffortBest(val effort: String, val timeS: Int)

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sample: RecordingSampleEntity)

    @Query("SELECT * FROM recording_samples WHERE sessionId = :sessionId ORDER BY tMs")
    suspend fun samples(sessionId: String): List<RecordingSampleEntity>

    @Query("SELECT DISTINCT sessionId FROM recording_samples")
    suspend fun unfinishedSessions(): List<String>

    @Query("DELETE FROM recording_samples WHERE sessionId = :sessionId")
    suspend fun clear(sessionId: String)
}

@Dao
interface GearDao {
    @Query("SELECT * FROM gear WHERE deletedAt IS NULL AND retiredAt IS NULL ORDER BY name")
    fun active(): Flow<List<GearEntity>>

    @Upsert suspend fun upsert(gear: GearEntity)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE active = 1")
    fun active(): Flow<List<GoalEntity>>

    @Upsert suspend fun upsert(goal: GoalEntity)
}

@Dao
interface DayDao {
    @Query("SELECT * FROM day_minutes WHERE day = :day ORDER BY minute")
    fun minutes(day: String): Flow<List<DayMinuteEntity>>

    @Query("SELECT * FROM day_minutes WHERE day = :day ORDER BY minute")
    suspend fun minutesNow(day: String): List<DayMinuteEntity>

    @Query("SELECT * FROM day_minutes WHERE day = :day AND minute = :minute")
    suspend fun minute(day: String, minute: Int): DayMinuteEntity?

    @Upsert suspend fun upsert(minute: DayMinuteEntity)

    @Query("SELECT DISTINCT day FROM day_minutes WHERE updatedAt >= :sinceMs")
    suspend fun daysChangedSince(sinceMs: Long): List<String>

    @Query("DELETE FROM day_minutes WHERE day < :beforeDay")
    suspend fun deleteBefore(beforeDay: String)
}

@Dao
interface TrackDao {
    @Insert suspend fun insert(point: TrackPointEntity)

    @Query("SELECT * FROM track_points ORDER BY tMs DESC LIMIT 1")
    suspend fun latest(): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE tMs >= :fromMs AND tMs < :toMs ORDER BY tMs")
    suspend fun between(fromMs: Long, toMs: Long): List<TrackPointEntity>

    @Query("DELETE FROM track_points WHERE tMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE deletedAt IS NULL ORDER BY name")
    fun all(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE syncState = 'DIRTY'")
    suspend fun dirty(): List<PlaceEntity>

    @Upsert suspend fun upsert(place: PlaceEntity)

    @Query("UPDATE places SET syncState = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}
