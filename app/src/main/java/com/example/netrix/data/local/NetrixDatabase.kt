package com.example.netrix.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SurveySessionEntity::class,
        SpatialSampleEntity::class,
        NetworkObservationEntity::class,
        ActiveProbeResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NetrixDatabase : RoomDatabase() {
    abstract fun netrixDao(): NetrixDao

    companion object {
        @Volatile
        private var INSTANCE: NetrixDatabase? = null

        fun getDatabase(context: Context): NetrixDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetrixDatabase::class.java,
                    "netrix_telemetry.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
