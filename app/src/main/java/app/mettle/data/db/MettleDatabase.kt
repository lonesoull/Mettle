package app.mettle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ActivityEntity::class, LapEntity::class, BestEffortEntity::class, GearEntity::class,
        PlaceEntity::class, PrivacyZoneEntity::class, RouteEntity::class, TagEntity::class,
        ActivityTagEntity::class, GoalEntity::class, RecordingSampleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MettleDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun laps(): LapDao
    abstract fun bestEfforts(): BestEffortDao
    abstract fun recording(): RecordingDao
    abstract fun gear(): GearDao
    abstract fun goals(): GoalDao

    companion object {
        @Volatile private var instance: MettleDatabase? = null

        fun get(context: Context): MettleDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MettleDatabase::class.java, "mettle.db")
                .build()
                .also { instance = it }
        }
    }
}
