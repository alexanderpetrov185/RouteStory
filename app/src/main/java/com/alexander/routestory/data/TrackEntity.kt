package com.alexander.routestory.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distance: Float,
    val duration: Long
)