package com.maxrave.domain.repository

import com.maxrave.domain.data.model.Track

interface OfflineRadioRepository {
    suspend fun buildOfflineQueue(
        seedTrack: Track,
        excludeVideoIds: Set<String>,
        limit: Int = 25,
    ): List<Track>

    suspend fun preSeedRadioForTrack(videoId: String)
}
