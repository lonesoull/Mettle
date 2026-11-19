package app.mettle.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun activityType(v: ActivityType): String = v.name
    @TypeConverter fun activityType(v: String): ActivityType = ActivityType.valueOf(v)
    @TypeConverter fun activitySub(v: ActivitySub): String = v.name
    @TypeConverter fun activitySub(v: String): ActivitySub = ActivitySub.valueOf(v)
    @TypeConverter fun visibility(v: Visibility): String = v.name
    @TypeConverter fun visibility(v: String): Visibility = Visibility.valueOf(v)
    @TypeConverter fun lapKind(v: LapKind): String = v.name
    @TypeConverter fun lapKind(v: String): LapKind = LapKind.valueOf(v)
    @TypeConverter fun gearKind(v: GearKind): String = v.name
    @TypeConverter fun gearKind(v: String): GearKind = GearKind.valueOf(v)
    @TypeConverter fun goalMetric(v: GoalMetric): String = v.name
    @TypeConverter fun goalMetric(v: String): GoalMetric = GoalMetric.valueOf(v)
    @TypeConverter fun goalPeriod(v: GoalPeriod): String = v.name
    @TypeConverter fun goalPeriod(v: String): GoalPeriod = GoalPeriod.valueOf(v)
    @TypeConverter fun syncState(v: SyncState): String = v.name
    @TypeConverter fun syncState(v: String): SyncState = SyncState.valueOf(v)
}
