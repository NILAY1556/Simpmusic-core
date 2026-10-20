package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "offline_radio_seed",
    indices = [
        Index(value = ["seedVideoId"]),
        Index(value = ["seedVideoId", "relatedVideoId"], unique = true),
    ],
)
data class OfflineRadioSeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seedVideoId: String,
    val relatedVideoId: String,
    val rank: Int,
    val fetchedAt: LocalDateTime = now(),
)
