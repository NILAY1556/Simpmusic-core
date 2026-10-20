package com.maxrave.data.repository

import DatabaseDao
import com.maxrave.domain.data.entities.OfflineRadioSeedEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.Track
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.OfflineRadioRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.isLive
import com.maxrave.domain.utils.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs

class OfflineRadioRepositoryImpl(
    private val databaseDao: DatabaseDao,
    private val songRepository: SongRepository,
    private val cacheRepository: CacheRepository,
    private val youTube: YouTube,
) : OfflineRadioRepository {

    override suspend fun buildOfflineQueue(
        seedTrack: Track,
        excludeVideoIds: Set<String>,
        limit: Int,
    ): List<Track> {
        val downloaded = songRepository.getDownloadedSongs().firstOrNull().orEmpty()
        val cachedIds = cacheRepository.getFullyCachedSongIds()
        val cached = if (cachedIds.isNotEmpty()) {
            songRepository.getSongsByListVideoId(cachedIds).firstOrNull().orEmpty()
        } else {
            emptyList()
        }

        val pool = (downloaded + cached)
            .distinctBy { it.videoId }
            .filter { song ->
                song.videoId.isNotBlank() &&
                    song.title.isNotBlank() &&
                    song.durationSeconds > 0 &&
                    !song.isLive &&
                    song.videoId != seedTrack.videoId &&
                    song.videoId !in excludeVideoIds
            }

        if (pool.isEmpty()) return emptyList()
        val preSeededIds = databaseDao.getRadioSeedsForTrack(seedTrack.videoId).firstOrNull().orEmpty()
        val poolByVideoId = pool.associateBy { it.videoId }
        val tier1Matches = preSeededIds.mapNotNull { poolByVideoId[it] }.distinctBy { it.videoId }
        val tier1Ids = tier1Matches.map { it.videoId }.toSet()

        val remainingCandidates = pool.filter { it.videoId !in tier1Ids }

        val tier2Matches = if (remainingCandidates.isEmpty()) {
            emptyList()
        } else {
            scoreRemainingCandidates(seedTrack, remainingCandidates)
        }

        val combinedCandidates = tier1Matches + tier2Matches
        val paced = if (pool.size >= 10) {
            applyPacing(combinedCandidates)
        } else {
            combinedCandidates
        }

        return paced.take(limit).map { it.toTrack() }
    }

    private suspend fun scoreRemainingCandidates(
        seedTrack: Track,
        candidates: List<SongEntity>,
    ): List<SongEntity> {
        val seedPrimaryArtistId = seedTrack.artists?.firstOrNull()?.id?.takeIf { it.isNotBlank() }
        val seedPrimaryArtistName = seedTrack.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        val allSeedArtistIds = seedTrack.artists?.mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }?.toSet().orEmpty()
        val allSeedArtistNames = seedTrack.artists?.mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }?.toSet().orEmpty()
        val coOccurringPlaylistIds = databaseDao.getCoOccurringPlaylistSongIds(seedTrack.videoId).toSet()
        val recentEvents = databaseDao.getPlaybackEventsByOffset(0, 500)
        val seedEvents = recentEvents.filter { it.videoId == seedTrack.videoId }
        val seedInstants = seedEvents.map { it.timestamp.toInstant(TimeZone.UTC).toEpochMilliseconds() }

        val candidateIds = candidates.map { it.videoId }.toSet()
        val candidateEvents = recentEvents.filter { it.videoId in candidateIds }

        val sessionCounts = mutableMapOf<String, Int>()
        if (seedInstants.isNotEmpty()) {
            for (event in candidateEvents) {
                val eventMillis = event.timestamp.toInstant(TimeZone.UTC).toEpochMilliseconds()
                val isWithin30Mins = seedInstants.any { seedMillis ->
                    abs(eventMillis - seedMillis) <= 30 * 60 * 1000L
                }
                if (isWithin30Mins) {
                    sessionCounts[event.videoId] = (sessionCounts[event.videoId] ?: 0) + 1
                }
            }
        }

        val maxSessionCount = maxOf(1, seedEvents.size, sessionCounts.values.maxOrNull() ?: 0)
        val seedAlbumId = seedTrack.album?.id?.takeIf { it.isNotBlank() }
        val maxPlayTime = candidates.maxOfOrNull { it.totalPlayTime } ?: 0L

        val scores = candidates.associate { candidate ->
            val candidatePrimaryId = candidate.artistId?.firstOrNull()?.takeIf { it.isNotBlank() }
            val candidatePrimaryName = candidate.artistName?.firstOrNull()?.takeIf { it.isNotBlank() }
            val allCandidateIds = candidate.artistId?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val allCandidateNames = candidate.artistName?.filter { it.isNotBlank() }?.toSet().orEmpty()

            val isPrimaryMatch = (seedPrimaryArtistId != null && candidatePrimaryId != null && seedPrimaryArtistId == candidatePrimaryId) ||
                (seedPrimaryArtistName != null && candidatePrimaryName != null && seedPrimaryArtistName.equals(candidatePrimaryName, ignoreCase = true))

            val artistScore = if (isPrimaryMatch) {
                1.0f
            } else {
                val isSecondaryMatch = (allCandidateIds.intersect(allSeedArtistIds).isNotEmpty()) ||
                    allCandidateNames.any { cName -> allSeedArtistNames.any { sName -> sName.equals(cName, ignoreCase = true) } }
                if (isSecondaryMatch) 0.6f else 0.0f
            }

            val count = sessionCounts[candidate.videoId] ?: 0
            val sessionScore = if (count > 0) {
                (count.toFloat() / maxSessionCount).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }

            val contextScore = when {
                seedAlbumId != null && !candidate.albumId.isNullOrBlank() && candidate.albumId == seedAlbumId -> 1.0f
                candidate.videoId in coOccurringPlaylistIds -> 0.7f
                else -> 0.0f
            }

            val affinityScore = if (maxPlayTime > 0L) {
                (candidate.totalPlayTime.toFloat() / maxPlayTime.toFloat()).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }

            val composite = 0.40f * artistScore + 0.30f * sessionScore + 0.20f * contextScore + 0.10f * affinityScore
            candidate.videoId to composite
        }

        return candidates.sortedWith(
            compareByDescending<SongEntity> { scores[it.videoId] ?: 0f }
                .thenByDescending { it.totalPlayTime }
        )
    }

    private fun applyPacing(candidates: List<SongEntity>): List<SongEntity> {
        if (candidates.size <= 2) return candidates
        val remaining = candidates.toMutableList()
        val paced = mutableListOf<SongEntity>()

        while (remaining.isNotEmpty()) {
            val last1Artist = paced.getOrNull(paced.size - 1)?.primaryArtist()
            val last2Artist = paced.getOrNull(paced.size - 2)?.primaryArtist()

            val pickIndex = if (
                paced.size >= 2 &&
                last1Artist != null &&
                last1Artist == last2Artist
            ) {
                val differentArtistIndex = remaining.indexOfFirst {
                    val candidateArtist = it.primaryArtist()
                    candidateArtist == null || candidateArtist != last1Artist
                }
                if (differentArtistIndex != -1) differentArtistIndex else 0
            } else {
                0
            }
            paced.add(remaining.removeAt(pickIndex))
        }
        return paced
    }

    private fun SongEntity.primaryArtist(): String? {
        return artistId?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: artistName?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    override suspend fun preSeedRadioForTrack(videoId: String) {
        runCatching {
            val response = youTube.next(WatchEndpoint(videoId = videoId)).getOrThrow()
            val seeds = response.items
                .filter { it.id.isNotBlank() && it.id != videoId }
                .take(25)
                .mapIndexed { index, item ->
                    OfflineRadioSeedEntity(
                        seedVideoId = videoId,
                        relatedVideoId = item.id,
                        rank = index,
                    )
                }
            if (seeds.isNotEmpty()) {
                databaseDao.insertRadioSeeds(seeds)
            }
        }.onFailure { e ->
            Logger.w(TAG, "preSeedRadioForTrack failed for $videoId: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OfflineRadioRepository"
    }
}
