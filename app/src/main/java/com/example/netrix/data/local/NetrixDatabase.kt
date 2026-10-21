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
    version = 2,
    exportSchema = false
)
abstract class NetrixDatabase : RoomDatabase() {
    abstract fun netrixDao(): NetrixDao

    companion object {
        @Volatile
        private var INSTANCE: NetrixDatabase? = null

        fun getDatabase(context: Context): NetrixDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        NetrixDatabase::class.java,
                        "netrix_telemetry.db"
                    )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                        .build()
                } catch (e: Throwable) {
                    android.util.Log.e("NetrixDatabase", "Error initializing disk database: ${e.message}", e)
                    Room.inMemoryDatabaseBuilder(
                        context.applicationContext,
                        NetrixDatabase::class.java
                    )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                        .build()
                }
                INSTANCE = instance
                instance
            }
        }
    }
}
