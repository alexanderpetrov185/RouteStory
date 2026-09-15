package com.alexander.routestory.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RouteStoryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}