package app.mettle.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * On-phone database. It mirrors the server tables (supabase/migrations) so the app
 * works fully offline; a sync worker uploads rows whose [SyncState] is DIRTY.
 *
 * Ids are UUID strings created on the phone. Times are epoch milliseconds (UTC).
 * Routes are stored as Google encoded polylines to keep rows small.
 */

enum class ActivityType { RUN, WALK, HIKE, STAIRS }
enum class ActivitySub { OUTDOOR, TREADMILL, STAIRWELL, STAIR_MACHINE }
enum class Visibility { PRIVATE, GROUP }
enum class LapKind { AUTO_KM, MANUAL, STAIR_SET }
enum class GearKind { SHOES, BOOTS, OTHER }
enum class GoalMetric { DISTANCE_M, MOVING_S, ELEV_GAIN_M, FLOORS_UP, ACTIVITIES }
enum class GoalPeriod { WEEK, MONTH, YEAR }
enum class SyncState { DIRTY, SYNCED }

@Entity(tableName = "activities", indices = [Index("startedAt"), Index("syncState")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val userId: String?,                 // null until the user has signed in
    val type: ActivityType,
    val subType: ActivitySub = ActivitySub.OUTDOOR,
    val title: String,
    val description: String? = null,
    val startedAt: Long,
    val timezone: String,
    val elapsedS: Int,
    val movingS: Int,
    val distanceM: Int = 0,
    val elevGainM: Double = 0.0,
    val elevLossM: Double = 0.0,
    val floorsUp: Int = 0,
    val floorsDown: Int = 0,
    val avgPaceSKm: Double? = null,
    val gapSKm: Double? = null,
    val heatAdjPaceSKm: Double? = null,
    val avgCadenceSpm: Int? = null,
    val calories: Int? = null,
    val rpe: Int? = null,
    val trainingLoad: Int? = null,
    val downhillStress: Int? = null,
    val weatherJson: String? = null,
    val gearId: String? = null,
    val placeId: String? = null,
    val routeId: String? = null,
    val visibility: Visibility = Visibility.GROUP,
    val routePolyline: String? = null,
    val streamsFile: String? = null,     // local file with per-second samples (gzip JSON)
    val source: String = "mettle",
    val device: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.DIRTY,
)

@Entity(
    tableName = "laps",
    foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")],
)
data class LapEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val idx: Int,
    val kind: LapKind,
    val startOffsetS: Int,
    val endOffsetS: Int,
    val distanceM: Int = 0,
    val movingS: Int = 0,
    val elevGainM: Double = 0.0,
    val floorsUp: Int = 0,
    val floorsDown: Int = 0,
    val climbS: Int? = null,
    val descentS: Int? = null,
    val restS: Int? = null,
)

@Entity(
    tableName = "best_efforts",
    foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId"), Index(value = ["effort", "timeS"])],
)
data class BestEffortEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val effort: String,                  // run_400m, run_1k, run_5k, stairs_up_10fl, ...
    val timeS: Int,
    val startOffsetS: Int,
    val endOffsetS: Int,
    val rankAtTime: Int?,
    val achievedAt: Long,
)

@Entity(tableName = "gear")
data class GearEntity(
    @PrimaryKey val id: String,
    val kind: GearKind = GearKind.SHOES,
    val name: String,
    val startDistanceM: Int = 0,
    val retireAtM: Int = 700_000,
    val defaultFor: String = "",         // comma-separated ActivityType names
    val retiredAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.DIRTY,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val floorHeightM: Double = 3.0,
    val stepsPerFloor: Int? = null,
    val floorsTotal: Int? = null,
    val calibratedAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.DIRTY,
)

@Entity(tableName = "privacy_zones")
data class PrivacyZoneEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int = 300,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.DIRTY,
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fingerprint: String,
    val polyline: String,
    val distanceM: Int,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.DIRTY,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(
    tableName = "activity_tags",
    primaryKeys = ["activityId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class ActivityTagEntity(val activityId: String, val tagId: String)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val metric: GoalMetric,
    val period: GoalPeriod,
    val target: Int,
    val activityTypes: String = "RUN,WALK,HIKE,STAIRS",
    val active: Boolean = true,
    val updatedAt: Long,
    val syncState: SyncState = SyncState.DIRTY,
)

/**
 * Local only: crash-safe buffer of raw samples while a workout is being recorded.
 * Written every second by the recording service, compacted into a streams file
 * when the workout is saved, then deleted.
 */
@Entity(tableName = "recording_samples", indices = [Index(value = ["sessionId", "tMs"])])
data class RecordingSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val tMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val speedMs: Float?,
    val steps: Int?,                     // cumulative step count
    val paused: Boolean,
)

/** All-day tracking: what happened in each minute of the day (local only, summarised for the server). */
enum class DayState { STILL, WALKING, RUNNING, VEHICLE, CYCLING, UNKNOWN }

@Entity(tableName = "day_minutes", primaryKeys = ["day", "minute"])
data class DayMinuteEntity(
    val day: String,                     // local date, e.g. 2026-11-21
    val minute: Int,                     // 0..1439 local minute of the day
    val steps: Int,
    val state: DayState,
    val updatedAt: Long,
)

/** All-day position samples (local; uploaded later as one compressed file per day). */
@Entity(tableName = "track_points", indices = [Index("tMs")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val verticalAccuracyM: Float?,
    val speedMs: Float?,
    val pressureHpa: Float?,             // barometer phones only
    val state: DayState,
)
