package com.alexander.routestory.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, TrackPointEntity::class, TrackPhotoEntity::class, PlannedRouteEntity::class, PlannedRoutePointEntity::class],
    version = 3,
    exportSchema = false
)
abstract class RouteStoryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun trackPhotoDao(): TrackPhotoDao
    abstract fun plannedRouteDao(): PlannedRouteDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS planned_routes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, profile TEXT NOT NULL, distanceMeters REAL NOT NULL, createdAt INTEGER NOT NULL, geometry TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS planned_route_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routeId INTEGER NOT NULL, orderIndex INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, FOREIGN KEY(routeId) REFERENCES planned_routes(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_route_points_routeId ON planned_route_points(routeId)")
    }
}

/** Adds the track_photos table without losing existing data. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_photos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "trackId INTEGER NOT NULL, " +
                "uri TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL, " +
                "latitude REAL, " +
                "longitude REAL, " +
                "FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_photos_trackId ON track_photos(trackId)")
    }
}