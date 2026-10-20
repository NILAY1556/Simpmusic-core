package com.maxrave.data.repository

import DatabaseDao
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.OfflineDashboardRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.isLive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest

class OfflineDashboardRepositoryImpl(
    private val databaseDao: DatabaseDao,
    private val songRepository: SongRepository,
    private val cacheRepository: CacheRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
) : OfflineDashboardRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getOfflineHomeShelves(): Flow<List<HomeItem>> =
        songRepository.getDownloadedSongs().flatMapLatest { downloadedSongs ->
            val cachedIds = cacheRepository.getFullyCachedSongIds()
            val cachedSongs = if (cachedIds.isNotEmpty()) {
                songRepository.getSongsByListVideoId(cachedIds).firstOrNull().orEmpty()
            } else {
                emptyList()
            }
            val offlinePool = (downloadedSongs.orEmpty() + cachedSongs)
                .distinctBy { it.videoId }
                .filter { song ->
                    song.videoId.isNotBlank() &&
                        song.title.isNotBlank() &&
                        song.durationSeconds > 0 &&
                        !song.isLive
                }

            combine(
                databaseDao.getDownloadedAlbums(cachedIds),
                databaseDao.getDownloadedArtists(cachedIds),
                localPlaylistRepository.getAllLocalPlaylists(),
                playlistRepository.getAllDownloadedPlaylist(),
            ) { albums, artists, localPlaylists, downloadedPlaylists ->
                buildShelves(
                    offlinePool = offlinePool,
                    downloadedAlbums = albums,
                    downloadedArtists = artists,
                    localPlaylists = localPlaylists,
                    downloadedPlaylists = downloadedPlaylists,
                )
            }
        }

    override suspend fun getOfflineMixes(): List<Content> {
        val pool = getOfflinePool()
        if (pool.isEmpty()) return emptyList()
        return synthesizeMixes(pool)
    }

    private suspend fun getOfflinePool(): List<SongEntity> {
        val downloaded = songRepository.getDownloadedSongs().firstOrNull().orEmpty()
        val cachedIds = cacheRepository.getFullyCachedSongIds()
        val cached = if (cachedIds.isNotEmpty()) {
            songRepository.getSongsByListVideoId(cachedIds).firstOrNull().orEmpty()
        } else {
            emptyList()
        }
        return (downloaded + cached)
            .distinctBy { it.videoId }
            .filter { song ->
                song.videoId.isNotBlank() &&
                    song.title.isNotBlank() &&
                    song.durationSeconds > 0 &&
                    !song.isLive
            }
    }

    private suspend fun buildShelves(
        offlinePool: List<SongEntity>,
        downloadedAlbums: List<AlbumEntity>,
        downloadedArtists: List<ArtistEntity>,
        localPlaylists: List<LocalPlaylistEntity>,
        downloadedPlaylists: List<PlaylistType>,
    ): List<HomeItem> {
        if (offlinePool.isEmpty() && localPlaylists.isEmpty() && downloadedPlaylists.isEmpty()) {
            return emptyList()
        }

        val shelves = mutableListOf<HomeItem>()

        if (offlinePool.isNotEmpty()) {
            // Shelf 1: "Jump Back In"
            val events = databaseDao.getPlaybackEventsByOffset(0, 50)
            val offlineMap = offlinePool.associateBy { it.videoId }
            val recentOfflineSongs = events
                .mapNotNull { event -> offlineMap[event.videoId] }
                .distinctBy { it.videoId }

            if (recentOfflineSongs.isNotEmpty()) {
                shelves.add(
                    HomeItem(
                        title = "Jump Back In",
                        contents = recentOfflineSongs.map { it.toContent() },
                        subtitle = "Recent offline listening",
                    )
                )
            }

            // Shelf 2: "Quick Picks"
            val quickPicks = offlinePool
                .sortedByDescending { it.totalPlayTime }
                .take(20)
                .map { it.toContent() }

            if (quickPicks.isNotEmpty()) {
                shelves.add(
                    HomeItem(
                        title = "Quick Picks",
                        contents = quickPicks,
                        subtitle = "Most listened offline",
                        itemsPerColumn = 4,
                    )
                )
            }

            // Shelf 3: "Offline Mixes"
            val mixes = getOfflineMixes()
            if (mixes.isNotEmpty()) {
                shelves.add(
                    HomeItem(
                        title = "Mixed for you",
                        contents = mixes,
                        subtitle = "Offline radio mixes",
                    )
                )
            }

            // Shelf 4: "Downloaded Albums"
            val albumContents = downloadedAlbums.map { it.toContent() }
            if (albumContents.isNotEmpty()) {
                shelves.add(
                    HomeItem(
                        title = "Downloaded Albums",
                        contents = albumContents,
                        subtitle = "Offline albums",
                    )
                )
            }

            // Shelf 5: "Downloaded Artists"
            val artistContents = downloadedArtists.map { it.toContent() }
            if (artistContents.isNotEmpty()) {
                shelves.add(
                    HomeItem(
                        title = "Downloaded Artists",
                        contents = artistContents,
                        subtitle = "Artists in your library",
                    )
                )
            }
        }

        // Shelf 6: "Offline Playlists"
        val localContents = localPlaylists.map { it.toContent() }
        val downloadedPlaylistContents = downloadedPlaylists
            .filterIsInstance<PlaylistEntity>()
            .map { it.toContent() }
        val allPlaylists = (localContents + downloadedPlaylistContents)
            .distinctBy { it.playlistId }

        if (allPlaylists.isNotEmpty()) {
            shelves.add(
                HomeItem(
                    title = "Offline Playlists",
                    contents = allPlaylists,
                    subtitle = "Downloaded & local playlists",
                )
            )
        }

        return shelves
    }

    private fun synthesizeMixes(pool: List<SongEntity>): List<Content> {
        if (pool.isEmpty()) return emptyList()

        val artistPlayTimes = mutableMapOf<String, Long>()
        val artistSongCounts = mutableMapOf<String, Int>()
        val artistTopSong = mutableMapOf<String, SongEntity>()

        for (song in pool) {
            for (name in song.artistName.orEmpty()) {
                if (name.isNotBlank()) {
                    artistPlayTimes[name] = (artistPlayTimes[name] ?: 0L) + maxOf(0L, song.totalPlayTime)
                    artistSongCounts[name] = (artistSongCounts[name] ?: 0) + 1
                    if (!artistTopSong.containsKey(name) || (song.totalPlayTime > (artistTopSong[name]?.totalPlayTime ?: 0L))) {
                        artistTopSong[name] = song
                    }
                }
            }
        }

        val sortedArtists = artistPlayTimes.keys.sortedWith(
            compareByDescending<String> { artistPlayTimes[it] ?: 0L }
                .thenByDescending { artistSongCounts[it] ?: 0 }
                .thenBy { it }
        )

        val topSong = pool.maxByOrNull { it.totalPlayTime } ?: pool.first()
        val supermixThumb = topSong.thumbnails?.takeIf { it.isNotBlank() }?.let {
            listOf(Thumbnail(height = 544, url = it, width = 544))
        } ?: emptyList()

        val mixes = mutableListOf<Content>()

        // 1. Offline Supermix
        val topArtists = sortedArtists.take(3).map { Artist(id = "", name = it) }
        mixes.add(
            Content(
                album = null,
                artists = if (topArtists.isNotEmpty()) topArtists else null,
                description = "Offline radio mix",
                isExplicit = false,
                playlistId = "offline_mix_supermix",
                browseId = null,
                thumbnails = supermixThumb,
                title = "Offline Supermix",
                videoId = null,
                views = null,
                radio = "offline_mix_supermix",
            )
        )

        // 2. [Artist 1] & Similar
        if (sortedArtists.isNotEmpty()) {
            val artist1 = sortedArtists[0]
            val artist1Song = artistTopSong[artist1]
            val artist1Thumb = artist1Song?.thumbnails?.takeIf { it.isNotBlank() }?.let {
                listOf(Thumbnail(height = 544, url = it, width = 544))
            } ?: supermixThumb
            mixes.add(
                Content(
                    album = null,
                    artists = listOf(Artist(id = "", name = artist1)),
                    description = "Offline radio mix",
                    isExplicit = false,
                    playlistId = "offline_mix_${artist1.lowercase().replace(" ", "_")}",
                    browseId = null,
                    thumbnails = artist1Thumb,
                    title = "$artist1 & Similar",
                    videoId = null,
                    views = null,
                    radio = "offline_mix_${artist1.lowercase().replace(" ", "_")}",
                )
            )
        }

        // 3. [Artist 2] Mix
        if (sortedArtists.size >= 2) {
            val artist2 = sortedArtists[1]
            val artist2Song = artistTopSong[artist2]
            val artist2Thumb = artist2Song?.thumbnails?.takeIf { it.isNotBlank() }?.let {
                listOf(Thumbnail(height = 544, url = it, width = 544))
            } ?: supermixThumb
            mixes.add(
                Content(
                    album = null,
                    artists = listOf(Artist(id = "", name = artist2)),
                    description = "Offline radio mix",
                    isExplicit = false,
                    playlistId = "offline_mix_${artist2.lowercase().replace(" ", "_")}",
                    browseId = null,
                    thumbnails = artist2Thumb,
                    title = "$artist2 Mix",
                    videoId = null,
                    views = null,
                    radio = "offline_mix_${artist2.lowercase().replace(" ", "_")}",
                )
            )
        }

        // 4. Offline Discover
        val discoverSong = pool.minByOrNull { it.totalPlayTime } ?: pool.last()
        val discoverThumb = discoverSong.thumbnails?.takeIf { it.isNotBlank() }?.let {
            listOf(Thumbnail(height = 544, url = it, width = 544))
        } ?: supermixThumb
        mixes.add(
            Content(
                album = null,
                artists = null,
                description = "Offline radio mix",
                isExplicit = false,
                playlistId = "offline_mix_discover",
                browseId = null,
                thumbnails = discoverThumb,
                title = "Offline Discover",
                videoId = null,
                views = null,
                radio = "offline_mix_discover",
            )
        )

        return mixes
    }

    private fun SongEntity.toContent(): Content {
        val currentThumbs = thumbnails
        val isSong = currentThumbs != null && currentThumbs.contains("w544") && currentThumbs.contains("h544")
        val thumbUrl = currentThumbs ?: ""
        val thumb = if (isSong) {
            Thumbnail(height = 544, url = thumbUrl, width = 544)
        } else {
            Thumbnail(height = 720, url = thumbUrl, width = 1280)
        }
        val currentArtistNames = artistName
        val currentArtistIds = artistId
        val artistsList = currentArtistNames?.mapIndexed { index, name ->
            Artist(id = currentArtistIds?.getOrNull(index) ?: "", name = name)
        }
        return Content(
            album = albumId?.let { Album(id = it, name = albumName.orEmpty()) },
            artists = artistsList,
            description = null,
            isExplicit = isExplicit,
            playlistId = null,
            browseId = null,
            thumbnails = listOf(thumb),
            title = title,
            videoId = videoId,
            views = null,
            durationSeconds = durationSeconds,
            videoType = videoType,
            isLive = isLive,
        )
    }

    private fun AlbumEntity.toContent(): Content {
        val artistsList = artistName?.mapIndexed { index, name ->
            Artist(id = artistId?.getOrNull(index) ?: "", name = name)
        }
        val thumb = thumbnails?.takeIf { it.isNotBlank() }?.let {
            listOf(Thumbnail(height = 544, url = it, width = 544))
        } ?: emptyList()
        return Content(
            album = null,
            artists = artistsList,
            description = year ?: description?.takeIf { it.isNotBlank() },
            isExplicit = false,
            playlistId = null,
            browseId = browseId,
            thumbnails = thumb,
            title = title,
            videoId = null,
            views = null,
        )
    }

    private fun ArtistEntity.toContent(): Content {
        val thumb = thumbnails?.takeIf { it.isNotBlank() }?.let {
            listOf(Thumbnail(height = 544, url = it, width = 544))
        } ?: emptyList()
        return Content(
            album = null,
            artists = null,
            description = null,
            isExplicit = false,
            playlistId = null,
            browseId = channelId,
            thumbnails = thumb,
            title = name,
            videoId = null,
            views = null,
        )
    }

    private fun LocalPlaylistEntity.toContent(): Content {
        val thumb = thumbnail?.takeIf { it.isNotBlank() }?.let {
            listOf(Thumbnail(height = 544, url = it, width = 544))
        } ?: emptyList()
        val desc = tracks?.size?.let { "$it tracks" }
        return Content(
            album = null,
            artists = null,
            description = desc,
            isExplicit = false,
            playlistId = id.toString(),
            browseId = null,
            thumbnails = thumb,
            title = title,
            videoId = null,
            views = null,
        )
    }

    private fun PlaylistEntity.toContent(): Content {
        val thumb = if (thumbnails.isNotBlank()) {
            listOf(Thumbnail(height = 544, url = thumbnails, width = 544))
        } else {
            emptyList()
        }
        val desc = if (trackCount > 0) "$trackCount tracks" else description?.takeIf { it.isNotBlank() }
        val authorArtist = author?.takeIf { it.isNotBlank() }?.let { listOf(Artist(id = "", name = it)) }
        return Content(
            album = null,
            artists = authorArtist,
            description = desc,
            isExplicit = false,
            playlistId = id,
            browseId = null,
            thumbnails = thumb,
            title = title,
            videoId = null,
            views = null,
        )
    }
}
