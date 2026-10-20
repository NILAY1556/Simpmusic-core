package com.maxrave.data.repository

import DatabaseDao
import androidx.room.RoomRawQuery
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.ArtistMotionEntity
import com.maxrave.domain.data.entities.AutoEqCurveEntity
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.entities.AutoEqIndexMetaEntity
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.FollowedArtistSingleAndAlbum
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.domain.data.entities.OfflineRadioSeedEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastWithEpisodes
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.entities.analytics.EventArtistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.analytics.query.DecadeCount
import com.maxrave.domain.data.entities.analytics.query.PlaybackSample
import com.maxrave.domain.data.entities.analytics.query.SongPlaySample
import com.maxrave.domain.data.entities.analytics.query.TopPlayedAlbum
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtist
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtistTime
import com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks
import com.maxrave.domain.data.model.browse.album.Album
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.extension.now
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.YouTubeLikeStatus
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineRadioRepositoryTest {

    @Test
    fun emptyPoolReturnsEmptyList() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository()
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 25)

        assertTrue(result.isEmpty())
    }

    @Test
    fun excludedVideoIdsAreOmitted() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("song_1", "Song 1", "Artist A"),
                createTestSong("song_2", "Song 2", "Artist B"),
                createTestSong("song_3", "Song 3", "Artist C"),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, excludeVideoIds = setOf("song_2"), limit = 2)

        val resultIds = result.map { it.videoId }
        assertFalse(resultIds.contains("song_2"))
        assertTrue(resultIds.contains("song_1") || resultIds.contains("song_3"))
    }

    @Test
    fun tier1PreSeededSeedsArePrioritized() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("song_unseeded_1", "Song 1", "Artist X"),
                createTestSong("song_seeded_2", "Song 2", "Artist Y"),
                createTestSong("song_unseeded_2", "Song 3", "Artist Z"),
                createTestSong("song_seeded_1", "Song 4", "Artist W"),
                createTestSong("song_unseeded_3", "Song 5", "Artist V"),
                createTestSong("song_unseeded_4", "Song 6", "Artist U"),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        dao.radioSeeds = mutableListOf(
            OfflineRadioSeedEntity(seedVideoId = "seed_1", relatedVideoId = "song_seeded_1", rank = 0),
            OfflineRadioSeedEntity(seedVideoId = "seed_1", relatedVideoId = "song_seeded_2", rank = 1),
        )
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 3)

        assertEquals(3, result.size)
        assertEquals("song_seeded_1", result[0].videoId)
        assertEquals("song_seeded_2", result[1].videoId)
    }

    @Test
    fun tier2AffinityScoringPrioritizesSameArtistAndCoListenedTracks() = runBlocking {
        val now = now()
        val dao = FakeDatabaseDao()
        dao.playbackEvents = mutableListOf(
            PlaybackEventEntity(eventId = 1, videoId = "seed_1", timestamp = now),
            PlaybackEventEntity(eventId = 2, videoId = "song_colistened", timestamp = now),
        )
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("song_unrelated_1", "Unrelated 1", "Stranger 1", totalPlayTime = 0),
                createTestSong("song_same_artist", "Same Artist", "Artist A", totalPlayTime = 10),
                createTestSong("song_unrelated_2", "Unrelated 2", "Stranger 2", totalPlayTime = 0),
                createTestSong("song_colistened", "Co-listened", "Stranger 3", totalPlayTime = 0),
                createTestSong("song_unrelated_3", "Unrelated 3", "Stranger 4", totalPlayTime = 0),
                createTestSong("song_unrelated_4", "Unrelated 4", "Stranger 5", totalPlayTime = 0),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 2)

        assertEquals(2, result.size)
        val resultIds = result.map { it.videoId }.toSet()
        assertTrue(resultIds.contains("song_same_artist"), "Expected same-artist song to be prioritized")
        assertTrue(resultIds.contains("song_colistened"), "Expected co-listened song to be prioritized")
    }

    @Test
    fun pacingPreventsMoreThan2ConsecutiveTracksBySameArtistWhenPoolIs10OrGreater() = runBlocking {
        val dao = FakeDatabaseDao()
        val poolSongs = listOf(
            createTestSong("a1", "A1", "Artist A", totalPlayTime = 100),
            createTestSong("a2", "A2", "Artist A", totalPlayTime = 90),
            createTestSong("a3", "A3", "Artist A", totalPlayTime = 80),
            createTestSong("a4", "A4", "Artist A", totalPlayTime = 70),
            createTestSong("a5", "A5", "Artist A", totalPlayTime = 60),
            createTestSong("a6", "A6", "Artist A", totalPlayTime = 50),
            createTestSong("b1", "B1", "Artist B", totalPlayTime = 40),
            createTestSong("b2", "B2", "Artist B", totalPlayTime = 30),
            createTestSong("b3", "B3", "Artist B", totalPlayTime = 20),
            createTestSong("b4", "B4", "Artist B", totalPlayTime = 10),
            createTestSong("c1", "C1", "Artist C", totalPlayTime = 5),
            createTestSong("c2", "C2", "Artist C", totalPlayTime = 1),
        )
        val songRepo = FakeSongRepository(downloaded = poolSongs)
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 10)

        assertEquals(10, result.size)
        for (i in 0..result.size - 3) {
            val artist0 = result[i].artists?.firstOrNull()?.name
            val artist1 = result[i + 1].artists?.firstOrNull()?.name
            val artist2 = result[i + 2].artists?.firstOrNull()?.name
            val allThreeSame = artist0 != null && artist0 == artist1 && artist1 == artist2
            assertFalse(allThreeSame, "Found 3 consecutive tracks by $artist0 at index $i")
        }
    }

    @Test
    fun poolSmallerThanLimitStillAppliesScoring() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("song_stranger", "Stranger", "Stranger", totalPlayTime = 0),
                createTestSong("song_same_artist", "Same Artist", "Artist A", totalPlayTime = 100),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        // limit is 25, pool is 2. The same-artist song should still be ranked #1
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 25)

        assertEquals(2, result.size)
        assertEquals("song_same_artist", result[0].videoId)
        assertEquals("song_stranger", result[1].videoId)
    }

    @Test
    fun liveStreamsAndSelfSeedAreFilteredOut() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("seed_1", "Self Seed", "Artist A"),
                createTestSong("live_song", "Live Broadcast", "Artist A", resultType = "LIVE"),
                createTestSong("valid_song", "Valid Song", "Artist A"),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 25)

        assertEquals(1, result.size)
        assertEquals("valid_song", result[0].videoId)
    }

    @Test
    fun contextScorePrioritizesSameAlbumThenCoPlaylist() = runBlocking {
        val dao = FakeDatabaseDao()
        dao.coOccurringPlaylistSongIds = listOf("song_coplaylist")
        val songRepo = FakeSongRepository(
            downloaded = listOf(
                createTestSong("song_unrelated", "Unrelated", "Stranger 1"),
                createTestSong("song_coplaylist", "Co-playlist", "Stranger 2"),
                createTestSong("song_same_album", "Same Album", "Stranger 3", albumId = "album_seed"),
            ),
        )
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist Seed", albumId = "album_seed")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 3)

        assertEquals(3, result.size)
        assertEquals("song_same_album", result[0].videoId, "Same album should rank #1")
        assertEquals("song_coplaylist", result[1].videoId, "Co-playlist should rank #2")
        assertEquals("song_unrelated", result[2].videoId, "Unrelated should rank #3")
    }

    @Test
    fun pacingNotAppliedWhenPoolUnder10() = runBlocking {
        val dao = FakeDatabaseDao()
        val poolSongs = (1..5).map { createTestSong("a$it", "A$it", "Artist A", totalPlayTime = (100 - it).toLong()) }
        val songRepo = FakeSongRepository(downloaded = poolSongs)
        val cacheRepo = FakeCacheRepository()
        val repository = OfflineRadioRepositoryImpl(dao, songRepo, cacheRepo, YouTube())

        val seedTrack = createTestTrack("seed_1", "Seed Song", "Artist A")
        val result = repository.buildOfflineQueue(seedTrack, emptySet(), limit = 5)

        // When pool < 10, all 5 songs by Artist A are allowed consecutively
        assertEquals(5, result.size)
    }
    private fun createTestTrack(
        videoId: String,
        title: String,
        artistName: String,
        albumId: String? = null,
    ): Track {
        return Track(
            videoId = videoId,
            title = title,
            artists = listOf(Artist(id = "artist_$artistName", name = artistName)),
            album = albumId?.let { Album(id = it, name = "Album $it") },
            duration = "3:30",
            durationSeconds = 210,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = null,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            feedbackTokens = null,
            resultType = null,
            year = "2024",
        )
    }

    private fun createTestSong(
        videoId: String,
        title: String,
        artistName: String,
        albumId: String? = null,
        totalPlayTime: Long = 0,
        downloadState: Int = 3,
        resultType: String? = null,
    ): SongEntity {
        return SongEntity(
            videoId = videoId,
            title = title,
            artistName = listOf(artistName),
            artistId = listOf("artist_$artistName"),
            albumName = albumId?.let { "Album $it" },
            albumId = albumId,
            duration = "3:30",
            durationSeconds = 210,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = "https://example.com/thumb.jpg",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            resultType = resultType,
            totalPlayTime = totalPlayTime,
            downloadState = downloadState,
        )
    }
}

class FakeCacheRepository(
    var fullyCachedSongIds: List<String> = emptyList(),
) : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long = 0L
    override fun clearCache(cacheName: String) {}
    override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()
    override suspend fun getFullyCachedSongIds(): List<String> = fullyCachedSongIds
    override suspend fun removeCachedSong(videoId: String) {}
}


open class FakeDatabaseDao : DatabaseDao {
    override suspend fun getSearchHistory(): List<SearchHistory> = throw NotImplementedError()
    override suspend fun deleteSearchHistory()= throw NotImplementedError()
    override suspend fun insertSearchHistory(searchHistory: SearchHistory): Long = throw NotImplementedError()
    override suspend fun getAllSongs(limit: Int): List<SongEntity> = throw NotImplementedError()
    override suspend fun getLikedSongs(limit: Int, offset: Int): List<SongEntity> = throw NotImplementedError()
    override fun getLikedSongsByArtist(channelId: String): Flow<List<SongEntity>> = throw NotImplementedError()
    override suspend fun getSong(videoId: String): SongEntity? = throw NotImplementedError()
    override fun getSongAsFlow(videoId: String): Flow<SongEntity?> = throw NotImplementedError()
    override suspend fun updateTotalPlayTime(videoId: String)= throw NotImplementedError()
    override suspend fun resetTotalPlayTime(videoId: String)= throw NotImplementedError()
    override suspend fun updateSongInLibrary(inLibrary: LocalDateTime, videoId: String): Int = throw NotImplementedError()
    override suspend fun updateLiked(liked: Int, videoId: String, favoriteAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun insertSong(song: SongEntity): Long = throw NotImplementedError()
    override suspend fun refreshAlbumIfPlaceholder(videoId: String, albumName: String, albumId: String?)= throw NotImplementedError()
    override suspend fun refreshArtists(videoId: String, artistName: List<String>, artistId: List<String>?)= throw NotImplementedError()
    override suspend fun updateCanvasUrl(videoId: String, canvasUrl: String)= throw NotImplementedError()
    override suspend fun updateCanvasThumbUrl(videoId: String, canvasThumbUrl: String)= throw NotImplementedError()
    override suspend fun updateThumbnailsSongEntity(thumbnails: String, videoId: String): Int = throw NotImplementedError()
    override suspend fun updateVideoTypeSongEntity(videoType: String, videoId: String): Int = throw NotImplementedError()
    override suspend fun markSongLive(videoId: String): Int = throw NotImplementedError()
    override suspend fun unmarkSongLive(videoId: String): Int = throw NotImplementedError()
    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = throw NotImplementedError()
    override fun countLikedSongs(): Flow<Int> = throw NotImplementedError()
    override fun getLikedSongThumbnails(limit: Int): Flow<List<String?>> = throw NotImplementedError()
    override fun countDownloadedSongs(): Flow<Int> = throw NotImplementedError()
    override fun getDownloadedSongThumbnails(limit: Int): Flow<List<String?>> = throw NotImplementedError()
    override fun countFollowedArtists(): Flow<Int> = throw NotImplementedError()
    override fun getFollowedArtistThumbnails(limit: Int): Flow<List<String?>> = throw NotImplementedError()
    override suspend fun updateDownloadState(downloadState: Int, videoId: String, downloadedAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun getSongsByCategory(category: String): List<SongEntity> = throw NotImplementedError()
    override suspend fun updateSongCategory(category: String?, videoId: String): Int = throw NotImplementedError()
    override suspend fun clearCategoryFromNotDownloaded(category: String): Int = throw NotImplementedError()
    override suspend fun clearCategoryFromLiked(category: String): Int = throw NotImplementedError()
    override suspend fun updateDurationSeconds(durationSeconds: Int, videoId: String)= throw NotImplementedError()
    override suspend fun getDownloadedSongs(limit: Int, offset: Int): List<SongEntity> = throw NotImplementedError()
    override suspend fun getDownloadingSongs(limit: Int, offset: Int): List<SongEntity> = throw NotImplementedError()
    override suspend fun getSongByListVideoIdFull(primaryKeyList: List<String>): List<SongEntity> = throw NotImplementedError()
    override suspend fun getSongByListVideoId(primaryKeyList: List<String>, offset: Int): List<SongEntity> = throw NotImplementedError()
    override suspend fun getCanvasSong(max: Int): List<SongEntity> = throw NotImplementedError()
    override fun getDownloadedVideoIdByListVideoId(primaryKeyList: List<String>): Flow<List<String>> = throw NotImplementedError()
    override suspend fun getAllArtists(limit: Int): List<ArtistEntity> = throw NotImplementedError()
    override suspend fun getArtist(channelId: String): ArtistEntity? = throw NotImplementedError()
    override suspend fun getFollowedArtists(limit: Int, offset: Int): List<ArtistEntity> = throw NotImplementedError()
    override suspend fun insertArtist(artist: ArtistEntity)= throw NotImplementedError()
    override suspend fun updateArtistImage(channelId: String, thumbnails: String)= throw NotImplementedError()
    override suspend fun updateArtistNameLogo(channelId: String, nameLogoUrl: String?, nameLogoColor: String?)= throw NotImplementedError()
    override suspend fun getArtistMotion(channelId: String): ArtistMotionEntity? = throw NotImplementedError()
    override suspend fun upsertArtistMotion(motion: ArtistMotionEntity)= throw NotImplementedError()
    override suspend fun updateFollowed(followed: Int, channelId: String, followedAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun updateArtistInLibrary(inLibrary: LocalDateTime, channelId: String)= throw NotImplementedError()
    override suspend fun getAllAlbums(limit: Int): List<AlbumEntity> = throw NotImplementedError()
    override suspend fun getAlbum(browseId: String): AlbumEntity? = throw NotImplementedError()
    override fun getAlbumAsFlow(browseId: String): Flow<AlbumEntity?> = throw NotImplementedError()
    override suspend fun getLikedAlbums(limit: Int, offset: Int): List<AlbumEntity> = throw NotImplementedError()
    override suspend fun insertAlbum(album: AlbumEntity): Long = throw NotImplementedError()
    override suspend fun updateAlbumLiked(liked: Int, browseId: String, favoriteAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun updateAlbumInLibrary(inLibrary: LocalDateTime, browseId: String)= throw NotImplementedError()
    override suspend fun updateAlbumDownloadState(downloadState: Int, browseId: String, downloadedAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun getDownloadedAlbums(limit: Int, offset: Int): List<AlbumEntity> = throw NotImplementedError()
    override suspend fun getDownloadingAlbums(limit: Int, offset: Int): List<AlbumEntity> = throw NotImplementedError()
    override suspend fun getAllPlaylists(limit: Int): List<PlaylistEntity> = throw NotImplementedError()
    override suspend fun getPlaylist(playlistId: String): PlaylistEntity? = throw NotImplementedError()
    override suspend fun getLikedPlaylists(limit: Int, offset: Int): List<PlaylistEntity> = throw NotImplementedError()
    override suspend fun insertPlaylist(playlist: PlaylistEntity)= throw NotImplementedError()
    override suspend fun insertAndReplacePlaylist(playlist: PlaylistEntity)= throw NotImplementedError()
    override suspend fun insertRadioPlaylist(playlist: PlaylistEntity)= throw NotImplementedError()
    override suspend fun updatePlaylistLiked(liked: Int, playlistId: String, favoriteAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun updatePlaylistInLibrary(inLibrary: LocalDateTime, playlistId: String)= throw NotImplementedError()
    override suspend fun updatePlaylistDownloadState(downloadState: Int, playlistId: String, downloadedAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun getDownloadedPlaylists(limit: Int, offset: Int): List<PlaylistEntity> = throw NotImplementedError()
    override suspend fun getDownloadingPlaylists(limit: Int, offset: Int): List<PlaylistEntity> = throw NotImplementedError()
    override suspend fun getAllLocalPlaylists(limit: Int, offset: Int): List<LocalPlaylistEntity> = throw NotImplementedError()
    override suspend fun getAllDownloadingLocalPlaylists(limit: Int, offset: Int): List<LocalPlaylistEntity> = throw NotImplementedError()
    override suspend fun getLocalPlaylist(id: Long): LocalPlaylistEntity? = throw NotImplementedError()
    override suspend fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity): Long = throw NotImplementedError()
    override suspend fun deleteLocalPlaylist(id: Long)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistTitle(title: String, id: Long)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistTracks(tracks: List<String>, id: Long)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistThumbnail(thumbnail: String, id: Long)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistInLibrary(inLibrary: LocalDateTime, id: Long)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistDownloadState(downloadState: Int, id: Long, downloadedAt: LocalDateTime?)= throw NotImplementedError()
    override suspend fun getDownloadedLocalPlaylists(limit: Int, offset: Int): List<LocalPlaylistEntity> = throw NotImplementedError()
    override suspend fun updateLocalPlaylistYouTubePlaylistId(id: Long, youtubePlaylistId: String?)= throw NotImplementedError()
    override suspend fun updateLocalPlaylistYouTubePlaylistSyncState(id: Long, state: Int)= throw NotImplementedError()
    override suspend fun unsyncLocalPlaylist(id: Long)= throw NotImplementedError()
    override fun getDownloadStateFlowOfLocalPlaylist(id: Long): Flow<Int> = throw NotImplementedError()
    override fun getListTracksFlowOfLocalPlaylist(id: Long): Flow<List<String>> = throw NotImplementedError()
    override suspend fun insertLyrics(lyrics: LyricsEntity)= throw NotImplementedError()
    override suspend fun getLyrics(videoId: String): LyricsEntity? = throw NotImplementedError()
    override suspend fun raw(supportSQLiteQuery: RoomRawQuery): Int = throw NotImplementedError()
    override suspend fun getPreparingSongs(limit: Int, offset: Int): List<SongEntity> = throw NotImplementedError()
    override suspend fun insertNewFormat(format: NewFormatEntity)= throw NotImplementedError()
    override suspend fun updateNewFormat(format: NewFormatEntity)= throw NotImplementedError()
    override suspend fun getNewFormat(videoId: String): NewFormatEntity? = throw NotImplementedError()
    override fun getNewFormatAsFlow(videoId: String): Flow<NewFormatEntity?> = throw NotImplementedError()
    override suspend fun insertSongInfo(songInfo: SongInfoEntity)= throw NotImplementedError()
    override suspend fun getSongInfo(videoId: String): SongInfoEntity? = throw NotImplementedError()
    override suspend fun recoverQueue(queue: QueueEntity)= throw NotImplementedError()
    override suspend fun deleteQueue()= throw NotImplementedError()
    override suspend fun getQueue(): List<QueueEntity> = throw NotImplementedError()
    override suspend fun getLocalPlaylistByYoutubePlaylistId(youtubePlaylistId: String): LocalPlaylistEntity? = throw NotImplementedError()
    override suspend fun insertSetVideoId(setVideoIdEntity: SetVideoIdEntity)= throw NotImplementedError()
    override suspend fun getSetVideoId(videoId: String, youtubePlaylistIds: List<String>): SetVideoIdEntity? = throw NotImplementedError()
    override suspend fun deleteSetVideoId(videoId: String, youtubePlaylistId: String)= throw NotImplementedError()
    override suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist)= throw NotImplementedError()
    override suspend fun getPlaylistPairOfSong(videoId: String, localPlaylistId: Long): PairSongLocalPlaylist = throw NotImplementedError()
    override suspend fun getPlaylistPairSong(playlistId: Long, limit: Int, offset: Int): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getPlaylistPairSongByListPosition(playlistId: Long, positionList: List<Int>): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun searchPlaylistPairSong(playlistId: Long, query: String, limit: Int): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getPlaylistPairSongByOffset(playlistId: Long, offset: Int): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getPlaylistPairSongByOlderFirst(playlistId: Long, cutPoint: LocalDateTime): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getPlaylistPairSongByNewerFirst(playlistId: Long, cutPoint: LocalDateTime): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getNewestPlaylistPairSong(playlistId: Long): PairSongLocalPlaylist? = throw NotImplementedError()
    override suspend fun editPositionOfSongInPlaylist(playlistId: Long, videoId: String, newPosition: Int)= throw NotImplementedError()
    override suspend fun getAllPlaylistPairSongByPosition(playlistId: Long): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun shiftPositionsForward(playlistId: Long, from: Int, to: Int)= throw NotImplementedError()
    override suspend fun shiftPositionsBackward(playlistId: Long, from: Int, to: Int)= throw NotImplementedError()
    override suspend fun getPlaylistPairSongByFromToDesc(playlistId: Long, from: Int, to: Int): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun getPlaylistPairSongByTitle(playlistId: Long, offset: Int): List<PairSongLocalPlaylist> = throw NotImplementedError()
    override suspend fun deletePairSongLocalPlaylist(playlistId: Long, videoId: String)= throw NotImplementedError()
    override suspend fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Long = throw NotImplementedError()
    override suspend fun getAllGoogleAccount(): List<GoogleAccountEntity> = throw NotImplementedError()
    override suspend fun getUsedGoogleAccount(): GoogleAccountEntity? = throw NotImplementedError()
    override suspend fun updateGoogleAccountUsed(isUsed: Boolean, email: String): Int = throw NotImplementedError()
    override suspend fun deleteGoogleAccount(email: String)= throw NotImplementedError()
    override suspend fun setInLibrary(videoId: String, inLibrary: LocalDateTime)= throw NotImplementedError()
    override suspend fun insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum: FollowedArtistSingleAndAlbum)= throw NotImplementedError()
    override suspend fun getFollowedArtistSingleAndAlbum(channelId: String): FollowedArtistSingleAndAlbum? = throw NotImplementedError()
    override suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String)= throw NotImplementedError()
    override suspend fun getAllFollowedArtistSingleAndAlbum(limit: Int, offset: Int): List<FollowedArtistSingleAndAlbum> = throw NotImplementedError()
    override suspend fun insertNotification(notificationEntity: NotificationEntity)= throw NotImplementedError()
    override suspend fun getAllNotification(): List<NotificationEntity> = throw NotImplementedError()
    override suspend fun countNotificationByLink(link: String): Int = throw NotImplementedError()
    override suspend fun deleteNotification(id: Long)= throw NotImplementedError()
    override suspend fun deleteNotificationsByChannelId(channelId: String)= throw NotImplementedError()
    override suspend fun insertTranslatedLyrics(translatedLyricsEntity: TranslatedLyricsEntity)= throw NotImplementedError()
    override suspend fun getTranslatedLyrics(videoId: String, language: String): TranslatedLyricsEntity? = throw NotImplementedError()
    override suspend fun removeTranslatedLyrics(videoId: String, language: String)= throw NotImplementedError()
    override suspend fun insertPodcast(podcast: PodcastsEntity): Long = throw NotImplementedError()
    override suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long> = throw NotImplementedError()
    override suspend fun getPodcastWithEpisodes(podcastId: String): PodcastWithEpisodes? = throw NotImplementedError()
    override suspend fun getAllPodcastWithEpisodes(limit: Int, offset: Int): List<PodcastWithEpisodes> = throw NotImplementedError()
    override suspend fun getAllPodcasts(limit: Int): List<PodcastsEntity> = throw NotImplementedError()
    override suspend fun getPodcast(podcastId: String): PodcastsEntity? = throw NotImplementedError()
    override suspend fun getPodcastEpisodes(podcastId: String, limit: Int, offset: Int): List<EpisodeEntity> = throw NotImplementedError()
    override suspend fun getEpisode(videoId: String): EpisodeEntity? = throw NotImplementedError()
    override suspend fun getFavoritePodcasts(limit: Int, offset: Int): List<PodcastsEntity> = throw NotImplementedError()
    override suspend fun updatePodcastInLibrary(id: String, inLibrary: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun deletePodcast(podcastId: String): Int = throw NotImplementedError()
    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList)= throw NotImplementedError()
    override suspend fun getYourYouTubePlaylistList(emailPageId: String): YourYouTubePlaylistList? = throw NotImplementedError()
    override suspend fun deleteAllYourYouTubePlaylist()= throw NotImplementedError()
    override suspend fun insertPlaybackEvent(playbackEventEntity: PlaybackEventEntity): Long = throw NotImplementedError()
    override suspend fun insertEventArtist(eventArtist: EventArtistEntity)= throw NotImplementedError()
    var playbackEvents: MutableList<PlaybackEventEntity> = mutableListOf()
    override suspend fun getPlaybackEventsByOffset(offset: Int, limit: Int): List<PlaybackEventEntity> {
        return playbackEvents.drop(offset).take(limit)
    }
    override suspend fun getPlaybackEventsByOffsetAndTimestamp(offset: Int, limit: Int, cutoffTimestamp: LocalDateTime): List<PlaybackEventEntity> = throw NotImplementedError()
    override suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime)= throw NotImplementedError()
    override suspend fun deleteAllPlaybackEvents()= throw NotImplementedError()
    override suspend fun deleteUnfollowedArtists(): Int = throw NotImplementedError()
    override suspend fun deleteNotificationsOfUnfollowedArtists(): Int = throw NotImplementedError()
    override suspend fun deleteOrphanedArtistMotion(): Int = throw NotImplementedError()
    override suspend fun deleteFollowedArtistReleasesOfUnfollowedArtists(): Int = throw NotImplementedError()
    override suspend fun deleteUnfavoritedPodcasts(): Int = throw NotImplementedError()
    override suspend fun deleteUnreferencedAlbums(): Int = throw NotImplementedError()
    override suspend fun deleteUnreferencedPlaylists(): Int = throw NotImplementedError()
    override suspend fun getOrphanedSongIds(): List<String> = throw NotImplementedError()
    override suspend fun deleteSongsByIds(videoIds: List<String>): Int = throw NotImplementedError()
    override suspend fun deleteLyricsByIds(videoIds: List<String>)= throw NotImplementedError()
    override suspend fun deleteTranslatedLyricsByIds(videoIds: List<String>)= throw NotImplementedError()
    override suspend fun deleteNewFormatsByIds(videoIds: List<String>)= throw NotImplementedError()
    override suspend fun deleteSongInfoByIds(videoIds: List<String>)= throw NotImplementedError()
    override suspend fun deleteStaleNewFormats(): Int = throw NotImplementedError()
    override suspend fun deleteStaleLyrics(): Int = throw NotImplementedError()
    override suspend fun deleteStaleTranslatedLyrics(): Int = throw NotImplementedError()
    override suspend fun deleteStaleSongInfo(): Int = throw NotImplementedError()
    override suspend fun queryTopPlayedSongsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<TopPlayedTracks> = throw NotImplementedError()
    override suspend fun queryTopArtistsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<TopPlayedArtist> = throw NotImplementedError()
    override suspend fun queryTopArtistsWithTimeInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<TopPlayedArtistTime> = throw NotImplementedError()
    override suspend fun queryTopAlbumsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<TopPlayedAlbum> = throw NotImplementedError()
    override suspend fun getTotalPlaybackEventCount(): Long = throw NotImplementedError()
    override suspend fun getTotalEventArtistCount(): Long = throw NotImplementedError()
    override suspend fun getTotalListeningTimeInSeconds(): Long = throw NotImplementedError()
    override suspend fun getPlaybackEventCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Long = throw NotImplementedError()
    override suspend fun getPlaybackSamplesInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<PlaybackSample> = throw NotImplementedError()
    override suspend fun getSongPlaysSince(from: LocalDateTime): List<SongPlaySample> = throw NotImplementedError()
    override suspend fun getDistinctTrackCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun getDistinctAlbumCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun getDistinctArtistCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun getNewArtistCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun getArtistPlayCountsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<TopPlayedArtist> = throw NotImplementedError()
    override suspend fun getDecadeCountsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): List<DecadeCount> = throw NotImplementedError()
    override suspend fun getDatedPlayCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Int = throw NotImplementedError()
    override suspend fun getAutoEqEntryCount(): Int = throw NotImplementedError()
    override suspend fun getAutoEqEntries(limit: Int): List<AutoEqEntryEntity> = throw NotImplementedError()
    override suspend fun searchAutoEqEntries(query: String, limit: Int): List<AutoEqEntryEntity> = throw NotImplementedError()
    override suspend fun getAutoEqCurve(path: String): AutoEqCurveEntity? = throw NotImplementedError()
    override suspend fun insertAutoEqCurve(curve: AutoEqCurveEntity)= throw NotImplementedError()
    override suspend fun getAutoEqCachedCurvePaths(): List<String> = throw NotImplementedError()
    override suspend fun getAutoEqIndexMeta(): AutoEqIndexMetaEntity? = throw NotImplementedError()
    override suspend fun insertAutoEqEntries(entries: List<AutoEqEntryEntity>)= throw NotImplementedError()
    override suspend fun upsertAutoEqIndexMeta(meta: AutoEqIndexMetaEntity)= throw NotImplementedError()
    override suspend fun deleteAllAutoEqEntries()= throw NotImplementedError()
    var radioSeeds: MutableList<OfflineRadioSeedEntity> = mutableListOf()
    override fun getRadioSeedsForTrack(seedVideoId: String): Flow<List<String>> = flowOf(
        radioSeeds.filter { it.seedVideoId == seedVideoId }.sortedBy { it.rank }.map { it.relatedVideoId }
    )
    override suspend fun insertRadioSeeds(seeds: List<OfflineRadioSeedEntity>) {
        radioSeeds.addAll(seeds)
    }
    override suspend fun pruneOldRadioSeeds(threshold: LocalDateTime) = throw NotImplementedError()
    override fun getDownloadedAlbums(cachedIds: List<String>): Flow<List<AlbumEntity>> = throw NotImplementedError()
    override fun getDownloadedArtists(cachedIds: List<String>): Flow<List<ArtistEntity>> = throw NotImplementedError()
    var coOccurringPlaylistSongIds: List<String> = emptyList()
    override suspend fun getCoOccurringPlaylistSongIds(videoId: String): List<String> = coOccurringPlaylistSongIds
}

open class FakeSongRepository(
    downloaded: List<SongEntity> = emptyList(),
    cached: List<SongEntity> = emptyList(),
) : SongRepository {
    init {
        this.downloadedSongs = downloaded
        this.cachedSongs = cached
    }
    override fun getAllSongs(limit: Int): Flow<List<SongEntity>> = throw NotImplementedError()
    override suspend fun setInLibrary(videoId: String, inLibrary: LocalDateTime)= throw NotImplementedError()
    var cachedSongs: List<SongEntity> = emptyList()
    override fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>> = flowOf(
        cachedSongs.filter { it.videoId in listVideoId }
    )
    var downloadedSongs: List<SongEntity> = emptyList()
    override fun getDownloadedSongs(): Flow<List<SongEntity>?> = flowOf(downloadedSongs)
    override fun getDownloadingSongs(): Flow<List<SongEntity>?> = throw NotImplementedError()
    override fun getPreparingSongs(): Flow<List<SongEntity>> = throw NotImplementedError()
    override fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>): Flow<List<String>> = throw NotImplementedError()
    override fun getLikedSongs(): Flow<List<SongEntity>> = throw NotImplementedError()
    override fun getLikedSongsByArtist(channelId: String): Flow<List<SongEntity>> = throw NotImplementedError()
    override suspend fun downloadAllLikedSongs(): Int = throw NotImplementedError()
    override suspend fun runSmartDownloads(count: Int): Int = throw NotImplementedError()
    override suspend fun clearSmartDownloads()= throw NotImplementedError()
    override suspend fun clearHistoryAndOrphanedSongs(): Int = throw NotImplementedError()
    override fun getCanvasSong(max: Int): Flow<List<SongEntity>> = throw NotImplementedError()
    override fun getSongById(id: String): Flow<SongEntity?> = throw NotImplementedError()
    override fun getSongAsFlow(id: String): Flow<SongEntity?> = throw NotImplementedError()
    override fun insertSong(songEntity: SongEntity): Flow<Long> = throw NotImplementedError()
    override fun updateThumbnailsSongEntity(thumbnail: String, videoId: String): Flow<Int> = throw NotImplementedError()
    override fun updateVideoTypeSongEntity(videoType: String, videoId: String): Flow<Int> = throw NotImplementedError()
    override suspend fun updateListenCount(videoId: String)= throw NotImplementedError()
    override suspend fun resetTotalPlayTime(videoId: String)= throw NotImplementedError()
    override suspend fun updateLikeStatus(videoId: String, likeStatus: Int)= throw NotImplementedError()
    override fun updateSongInLibrary(inLibrary: LocalDateTime, videoId: String): Flow<Int> = throw NotImplementedError()
    override suspend fun updateDurationSeconds(durationSeconds: Int, videoId: String)= throw NotImplementedError()
    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = throw NotImplementedError()
    override suspend fun updateDownloadState(videoId: String, downloadState: Int)= throw NotImplementedError()
    override suspend fun insertSongInfo(songInfo: SongInfoEntity)= throw NotImplementedError()
    override suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> = throw NotImplementedError()
    override suspend fun recoverQueue(temp: List<Track>)= throw NotImplementedError()
    override suspend fun removeQueue()= throw NotImplementedError()
    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> = throw NotImplementedError()
    override fun getContinueTrack(playlistId: String, continuation: String, fromPlaylist: Boolean): Flow<Pair<ArrayList<Track>?, String?>> = throw NotImplementedError()
    override fun getSongInfo(videoId: String): Flow<SongInfoEntity?> = throw NotImplementedError()
    override suspend fun getLikeStatus(videoId: String): Flow<YouTubeLikeStatus> = throw NotImplementedError()
    override suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int> = throw NotImplementedError()
    override suspend fun addToYouTubeDisliked(mediaId: String?): Flow<Int> = throw NotImplementedError()
    override suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int> = throw NotImplementedError()
    override fun downloadToFile(track: Track, path: String, videoId: String, isVideo: Boolean): Flow<DownloadProgress> = throw NotImplementedError()
    override fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>> = throw NotImplementedError()
    override fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>> = throw NotImplementedError()
    override fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>> = throw NotImplementedError()
    override fun postComment(createCommentParams: String, text: String): Flow<Resource<YouTubeComment?>> = throw NotImplementedError()
    override fun postCommentReply(createReplyParams: String, text: String): Flow<Resource<YouTubeComment?>> = throw NotImplementedError()
    override fun performCommentAction(action: String): Flow<Resource<Unit>> = throw NotImplementedError()
    override fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>> = throw NotImplementedError()
}
