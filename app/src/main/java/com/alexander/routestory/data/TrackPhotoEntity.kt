package com.alexander.routestory.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_photos",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class TrackPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val uri: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?
)