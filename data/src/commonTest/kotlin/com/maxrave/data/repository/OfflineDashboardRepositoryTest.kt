package com.maxrave.data.repository

import androidx.paging.PagingData
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.browse.playlist.PlaylistState
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDashboardRepositoryTest {

    @Test
    fun emptyOfflineLibraryYieldsEmptyShelvesList() = runBlocking {
        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository()
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val shelves = repository.getOfflineHomeShelves().first()
        assertTrue(shelves.isEmpty(), "Empty library must yield empty shelves list")

        val mixes = repository.getOfflineMixes()
        assertTrue(mixes.isEmpty(), "Empty library must yield empty mixes")
    }

    @Test
    fun shelfGenerationWhenLibraryHasDownloadedSongsAndHistory() = runBlocking {
        val song1 = createTestSong("song_1", "Song 1", "Artist A", totalPlayTime = 100L)
        val song2 = createTestSong("song_2", "Song 2", "Artist B", totalPlayTime = 200L)
        val song3 = createTestSong("song_3", "Song 3", "Artist A", totalPlayTime = 300L)

        val dao = FakeDatabaseDao()
        dao.playbackEvents = mutableListOf(
            PlaybackEventEntity(eventId = 1, videoId = "song_1", timestamp = now()),
            PlaybackEventEntity(eventId = 2, videoId = "online_only_song", timestamp = now()),
        )
        dao.downloadedAlbumsList = listOf(
            createTestAlbum("album_1", "Album 1", "Artist A"),
        )
        dao.downloadedArtistsList = listOf(
            createTestArtist("channel_Artist A", "Artist A"),
        )

        val songRepo = FakeSongRepository(
            downloaded = listOf(song1, song2, song3),
        )
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository(
            localPlaylists = listOf(createTestLocalPlaylist(1L, "My Local Playlist")),
        )
        val playlistRepo = FakePlaylistRepository(
            downloadedPlaylists = listOf(createTestPlaylist("pl_1", "Downloaded Playlist")),
        )

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val shelves = repository.getOfflineHomeShelves().first()
        assertEquals(6, shelves.size, "All 6 shelves should be present")

        // Shelf 1: Jump Back In
        val jumpBackIn = shelves[0]
        assertEquals("Jump Back In", jumpBackIn.title)
        assertEquals("Recent offline listening", jumpBackIn.subtitle)
        assertEquals(1, jumpBackIn.contents.size)
        assertEquals("song_1", jumpBackIn.contents[0]?.videoId)

        // Shelf 2: Quick Picks
        val quickPicks = shelves[1]
        assertEquals("Quick Picks", quickPicks.title)
        assertEquals("Most listened offline", quickPicks.subtitle)
        assertEquals(4, quickPicks.itemsPerColumn)
        assertEquals(3, quickPicks.contents.size)
        assertEquals("song_3", quickPicks.contents[0]?.videoId) // highest play time (300)

        // Shelf 3: Mixed for you
        val mixedForYou = shelves[2]
        assertEquals("Mixed for you", mixedForYou.title)
        assertEquals("Offline radio mixes", mixedForYou.subtitle)
        assertTrue(mixedForYou.contents.isNotEmpty())

        // Shelf 4: Downloaded Albums
        val albumsShelf = shelves[3]
        assertEquals("Downloaded Albums", albumsShelf.title)
        assertEquals("Offline albums", albumsShelf.subtitle)
        assertEquals(1, albumsShelf.contents.size)
        assertEquals("album_1", albumsShelf.contents[0]?.browseId)

        // Shelf 5: Downloaded Artists
        val artistsShelf = shelves[4]
        assertEquals("Downloaded Artists", artistsShelf.title)
        assertEquals("Artists in your library", artistsShelf.subtitle)
        assertEquals(1, artistsShelf.contents.size)
        assertEquals("channel_Artist A", artistsShelf.contents[0]?.browseId)

        // Shelf 6: Offline Playlists
        val playlistsShelf = shelves[5]
        assertEquals("Offline Playlists", playlistsShelf.title)
        assertEquals("Downloaded & local playlists", playlistsShelf.subtitle)
        assertEquals(2, playlistsShelf.contents.size)
        val playlistIds = playlistsShelf.contents.map { it?.playlistId }
        assertTrue(playlistIds.contains("1"))
        assertTrue(playlistIds.contains("pl_1"))
    }

    @Test
    fun quickPicksShelfCorrectlySetsItemsPerColumnFour() = runBlocking {
        val songs = listOf(
            createTestSong("song_1", "Song 1", "Artist A", totalPlayTime = 50L),
            createTestSong("song_2", "Song 2", "Artist B", totalPlayTime = 500L),
            createTestSong("song_3", "Song 3", "Artist C", totalPlayTime = 250L),
        )

        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(downloaded = songs)
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val shelves = repository.getOfflineHomeShelves().first()
        val quickPicks = shelves.firstOrNull { it.title == "Quick Picks" }

        assertNotNull(quickPicks)
        assertEquals(4, quickPicks.itemsPerColumn, "Quick Picks itemsPerColumn must be 4")
        assertEquals("Most listened offline", quickPicks.subtitle)
        assertEquals(listOf("song_2", "song_3", "song_1"), quickPicks.contents.map { it?.videoId })
    }

    @Test
    fun offlineMixesGeneratedWithAppropriateTitlesAndContents() = runBlocking {
        val song1 = createTestSong("song_1", "Song 1", "The Beatles", totalPlayTime = 1000L)
        val song2 = createTestSong("song_2", "Song 2", "Queen", totalPlayTime = 500L)
        val song3 = createTestSong("song_3", "Song 3", "The Beatles", totalPlayTime = 800L)

        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(downloaded = listOf(song1, song2, song3))
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val mixes = repository.getOfflineMixes()
        assertEquals(4, mixes.size, "Should synthesize 4 mix cards when 2+ artists exist")

        // Card 1: Offline Supermix
        assertEquals("Offline Supermix", mixes[0].title)
        assertEquals("offline_mix_supermix", mixes[0].playlistId)
        assertEquals("Offline radio mix", mixes[0].description)

        // Card 2: [Artist 1] & Similar
        assertEquals("The Beatles & Similar", mixes[1].title)
        assertEquals("offline_mix_the_beatles", mixes[1].playlistId)
        assertEquals("The Beatles", mixes[1].artists?.firstOrNull()?.name)

        // Card 3: [Artist 2] Mix
        assertEquals("Queen Mix", mixes[2].title)
        assertEquals("offline_mix_queen", mixes[2].playlistId)
        assertEquals("Queen", mixes[2].artists?.firstOrNull()?.name)

        // Card 4: Offline Discover
        assertEquals("Offline Discover", mixes[3].title)
        assertEquals("offline_mix_discover", mixes[3].playlistId)
        assertNull(mixes[3].artists)
    }

    @Test
    fun offlineMixesWithSingleArtistGeneratesThreeCards() = runBlocking {
        val song1 = createTestSong("song_1", "Song 1", "Pink Floyd", totalPlayTime = 300L)
        val song2 = createTestSong("song_2", "Song 2", "Pink Floyd", totalPlayTime = 100L)

        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(downloaded = listOf(song1, song2))
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val mixes = repository.getOfflineMixes()
        assertEquals(3, mixes.size, "Should synthesize 3 mix cards when only 1 artist exists")
        assertEquals("Offline Supermix", mixes[0].title)
        assertEquals("Pink Floyd & Similar", mixes[1].title)
        assertEquals("Offline Discover", mixes[2].title)
    }

    @Test
    fun cachedSongsAreIncludedInOfflinePool() = runBlocking {
        val cachedSong = createTestSong("cached_1", "Cached Song", "Artist C", totalPlayTime = 150L)

        val dao = FakeDatabaseDao()
        val songRepo = FakeSongRepository(cached = listOf(cachedSong))
        val cacheRepo = FakeCacheRepository(fullyCachedSongIds = listOf("cached_1"))
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val shelves = repository.getOfflineHomeShelves().first()
        assertTrue(shelves.isNotEmpty(), "Cached songs should form offline pool and generate shelves")
        val quickPicks = shelves.firstOrNull { it.title == "Quick Picks" }
        assertNotNull(quickPicks)
        assertEquals("cached_1", quickPicks.contents.firstOrNull()?.videoId)
    }

    @Test
    fun playbackEventsForNonOfflineSongsAreFilteredOut() = runBlocking {
        val downloadedSong = createTestSong("offline_1", "Offline Song", "Artist X")

        val dao = FakeDatabaseDao()
        dao.playbackEvents = mutableListOf(
            PlaybackEventEntity(eventId = 1, videoId = "streamed_only_1", timestamp = now()),
            PlaybackEventEntity(eventId = 2, videoId = "streamed_only_2", timestamp = now()),
        )

        val songRepo = FakeSongRepository(downloaded = listOf(downloadedSong))
        val cacheRepo = FakeCacheRepository()
        val localPlaylistRepo = FakeLocalPlaylistRepository()
        val playlistRepo = FakePlaylistRepository()

        val repository = OfflineDashboardRepositoryImpl(
            databaseDao = dao,
            songRepository = songRepo,
            cacheRepository = cacheRepo,
            localPlaylistRepository = localPlaylistRepo,
            playlistRepository = playlistRepo,
        )

        val shelves = repository.getOfflineHomeShelves().first()
        val jumpBackIn = shelves.firstOrNull { it.title == "Jump Back In" }
        assertNull(jumpBackIn, "Jump Back In shelf should be omitted when no offline songs match playback events")
    }

    private fun createTestSong(
        videoId: String,
        title: String,
        artist: String,
        totalPlayTime: Long = 0L,
        albumId: String? = null,
        thumbnails: String = "https://example.com/thumb_w544_h544.jpg",
    ): SongEntity = SongEntity(
        videoId = videoId,
        albumId = albumId,
        albumName = albumId?.let { "Album $it" },
        artistId = listOf("channel_$artist"),
        artistName = listOf(artist),
        duration = "3:30",
        durationSeconds = 210,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = thumbnails,
        title = title,
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = null,
        resultType = null,
        totalPlayTime = totalPlayTime,
    )

    private fun createTestAlbum(
        browseId: String,
        title: String,
        artist: String,
    ): AlbumEntity = AlbumEntity(
        browseId = browseId,
        artistId = listOf("channel_$artist"),
        artistName = listOf(artist),
        audioPlaylistId = "audio_$browseId",
        description = "Description",
        duration = "40:00",
        durationSeconds = 2400,
        thumbnails = "https://example.com/album.jpg",
        title = title,
        trackCount = 10,
        tracks = emptyList(),
        type = "Album",
        year = "2024",
    )

    private fun createTestArtist(
        channelId: String,
        name: String,
    ): ArtistEntity = ArtistEntity(
        channelId = channelId,
        name = name,
        thumbnails = "https://example.com/artist.jpg",
        followed = true,
    )

    private fun createTestLocalPlaylist(
        id: Long,
        title: String,
    ): LocalPlaylistEntity = LocalPlaylistEntity(
        id = id,
        title = title,
        thumbnail = "https://example.com/playlist.jpg",
        tracks = listOf("song_1"),
    )

    private fun createTestPlaylist(
        id: String,
        title: String,
    ): PlaylistEntity = PlaylistEntity(
        id = id,
        title = title,
        thumbnails = "https://example.com/playlist.jpg",
        trackCount = 5,
    )
}

open class FakeLocalPlaylistRepository(
    var localPlaylists: List<LocalPlaylistEntity> = emptyList(),
) : LocalPlaylistRepository {
    override fun getAllLocalPlaylists(): Flow<List<LocalPlaylistEntity>> = flowOf(localPlaylists)
    override fun getLocalPlaylist(id: Long): Flow<LocalResource<LocalPlaylistEntity?>> = throw NotImplementedError()
    override suspend fun updateLocalPlaylistTracks(tracks: List<String>, id: Long) = throw NotImplementedError()
    override suspend fun updateLocalPlaylistDownloadState(downloadState: Int, id: Long) = throw NotImplementedError()
    override suspend fun updateLocalPlaylistYouTubePlaylistSyncState(id: Long, syncState: Int) = throw NotImplementedError()
    override suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist) = throw NotImplementedError()
    override fun getPlaylistPairSongByListPosition(playlistId: Long, listPosition: List<Int>): Flow<List<PairSongLocalPlaylist>?> = throw NotImplementedError()
    override fun getPlaylistPairSongByOffset(playlistId: Long, offset: Int, filterState: FilterState): Flow<List<PairSongLocalPlaylist>?> = throw NotImplementedError()
    override fun getPlaylistPairSongByTime(playlistId: Long, filterState: FilterState, localDateTime: LocalDateTime): Flow<List<PairSongLocalPlaylist>?> = throw NotImplementedError()
    override fun getPlaylistPairOfSong(playlistId: Long, videoId: String): Flow<PairSongLocalPlaylist?> = throw NotImplementedError()
    override fun changePositionOfSongInPlaylist(playlistId: Long, videoId: String, newPosition: Int): Flow<String> = throw NotImplementedError()
    override fun moveItemInSyncedPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun moveItemInLocalPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun downloadStateFlow(id: Long): Flow<Int> = throw NotImplementedError()
    override fun getAllDownloadingLocalPlaylists(): Flow<List<LocalPlaylistEntity>> = throw NotImplementedError()
    override fun listTrackFlow(id: Long): Flow<List<String>> = throw NotImplementedError()
    override fun searchTracks(id: Long, query: String, limit: Int): Flow<List<Pair<SongEntity, PairSongLocalPlaylist>>> = throw NotImplementedError()
    override fun getTracksPaging(id: Long, filter: FilterState): Flow<PagingData<Pair<SongEntity, PairSongLocalPlaylist>>> = throw NotImplementedError()
    override suspend fun getFullPlaylistTracks(id: Long): List<SongEntity> = throw NotImplementedError()
    override suspend fun getListTrackVideoId(id: Long): List<String> = throw NotImplementedError()
    override fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun deleteLocalPlaylist(id: Long, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateTitleLocalPlaylist(id: Long, newTitle: String, updatedMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateThumbnailLocalPlaylist(id: Long, newThumbnail: String, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateDownloadState(id: Long, downloadState: Int, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun syncYouTubePlaylistToLocalPlaylist(playlist: PlaylistState, tracks: List<Track>, successMessage: String, errorMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun syncLocalPlaylistToYouTubePlaylist(playlistId: Long, successMessage: String, errorMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun unsyncLocalPlaylist(id: Long, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateSyncState(id: Long, syncState: Int, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateYouTubePlaylistId(id: Long, youtubePlaylistId: String, successMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun updateListTrackSynced(id: Long): Flow<Boolean> = throw NotImplementedError()
    override fun addTrackToLocalPlaylist(id: Long, song: SongEntity, successMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun removeTrackFromLocalPlaylist(id: Long, song: SongEntity, successMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = throw NotImplementedError()
    override fun getSuggestionsTrackForPlaylist(id: Long): Flow<LocalResource<Pair<String?, List<Track>>>> = throw NotImplementedError()
    override fun reloadSuggestionPlaylist(reloadParams: String): Flow<LocalResource<Pair<String?, List<Track>>>> = throw NotImplementedError()
    override fun getYouTubeSetVideoId(youtubePlaylistId: String): Flow<List<SetVideoIdEntity>> = throw NotImplementedError()
    override fun addYouTubePlaylistItem(youtubePlaylistId: String, videoId: String): Flow<LocalResource<String>> = throw NotImplementedError()
}

open class FakePlaylistRepository(
    var downloadedPlaylists: List<PlaylistType> = emptyList(),
) : PlaylistRepository {
    override fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>> = flowOf(downloadedPlaylists)
    override fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>> = throw NotImplementedError()
    override fun getPlaylist(id: String): Flow<PlaylistEntity?> = throw NotImplementedError()
    override fun getLikedPlaylists(): Flow<List<PlaylistEntity>> = throw NotImplementedError()
    override suspend fun insertPlaylist(playlistEntity: PlaylistEntity) = throw NotImplementedError()
    override suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity) = throw NotImplementedError()
    override suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity) = throw NotImplementedError()
    override suspend fun updatePlaylistLiked(playlistId: String, likeStatus: Int) = throw NotImplementedError()
    override suspend fun updatePlaylistInLibrary(inLibrary: LocalDateTime, playlistId: String) = throw NotImplementedError()
    override suspend fun updatePlaylistDownloadState(playlistId: String, downloadState: Int) = throw NotImplementedError()
    override fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>> = throw NotImplementedError()
    override fun getRadio(radioId: String, defaultDescription: String, radioString: String, viewString: String, originalTrack: SongEntity?, artist: ArtistEntity?): Flow<Resource<Pair<PlaylistBrowse, String?>>> = throw NotImplementedError()
    override fun getRDATRadioData(radioId: String, viewString: String): Flow<Resource<Pair<PlaylistBrowse, String?>>> = throw NotImplementedError()
    override fun getFullPlaylistData(playlistId: String, viewString: String): Flow<Resource<PlaylistBrowse>> = throw NotImplementedError()
    override fun getPlaylistData(playlistId: String, viewString: String): Flow<Resource<Pair<PlaylistBrowse, String?>>> = throw NotImplementedError()
    override fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?> = throw NotImplementedError()
    override fun getMixedForYou(): Flow<List<PlaylistsResult>?> = throw NotImplementedError()
    override fun updateYourYouTubePlaylistTitle(playlistId: String, newTitle: String): Flow<Resource<String>> = throw NotImplementedError()
    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) = throw NotImplementedError()
    override fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?> = throw NotImplementedError()
    override suspend fun deleteAllYourYouTubePlaylist() = throw NotImplementedError()
    override fun getChartPlaylist(): Flow<Resource<List<ChartItem>>> = throw NotImplementedError()
}
