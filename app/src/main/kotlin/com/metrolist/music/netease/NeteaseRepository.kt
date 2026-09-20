package com.metrolist.music.netease

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.utils.SyncUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeteaseRepository @Inject constructor(
    val auth: NeteaseAuth,
    private val api: NeteaseApi,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) {
    val dao get() = database.neteaseSyncDao
    private val mutex = Mutex()
    private val source = object : NeteaseSource {
        override suspend fun tracks(binding: NeteaseBinding): List<SyncTrack> {
            if (auth.currentUser().id != binding.neteaseUserId) throw SyncException("SOURCE_ACCOUNT_CHANGED")
            return api.getPlaylistTracks(binding.neteasePlaylistId, auth.cookie())
        }
    }
    private val target = object : NeteaseSyncTarget {
        override suspend fun tracks(binding: NeteaseBinding): List<SyncTrack> {
            requireYoutubeLogin()
            val page = YouTube.playlist(binding.youtubePlaylistId).completed().getOrThrow()
            if (!page.playlist.isEditable) throw SyncException("TARGET_NOT_EDITABLE")
            return page.songs.map { it.toSyncTrack() }
        }

        override suspend fun search(query: String): List<SyncTrack> {
            delay(400)
            return YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
                .items.filterIsInstance<SongItem>().take(10).map { it.toSyncTrack() }
        }

        override suspend fun addIfAbsent(binding: NeteaseBinding, videoId: String): Boolean =
            syncUtils.addToPlaylistIfAbsentSuspend(binding.youtubePlaylistId, binding.localPlaylistId, videoId)
    }
    private val engine = NeteaseSyncEngine(dao, source, target)

    private fun requireYoutubeLogin() {
        if (YouTube.cookie.isNullOrBlank()) throw SyncException("YOUTUBE_LOGIN_REQUIRED")
    }

    suspend fun getUserPlaylists(): List<NeteasePlaylist> {
        val user = auth.currentUser()
        return api.getUserPlaylists(user.id, auth.cookie())
    }

    suspend fun getTargetPlaylists(): List<PlaylistItem> {
        requireYoutubeLogin()
        return YouTube.library("FEmusic_liked_playlists").completed().getOrThrow().items
            .filterIsInstance<PlaylistItem>().filter { it.isEditable && !it.isPodcast && it.id !in setOf("LM", "SE") }
    }

    suspend fun bind(sourcePlaylist: NeteasePlaylist, targetPlaylist: PlaylistItem) = mutex.withLock {
        val user = auth.currentUser()
        requireYoutubeLogin()
        val remote = YouTube.playlist(targetPlaylist.id).completed().getOrThrow()
        if (!remote.playlist.isEditable) throw SyncException("TARGET_NOT_EDITABLE")
        val local = database.playlistByBrowseId(targetPlaylist.id).first()?.playlist ?: PlaylistEntity(
            name = targetPlaylist.title, browseId = targetPlaylist.id, bookmarkedAt = LocalDateTime.now(),
        ).also { database.insert(it) }
        dao.putBinding(NeteaseBinding(
            neteaseUserId = user.id, neteasePlaylistId = sourcePlaylist.id, neteasePlaylistName = sourcePlaylist.name,
            youtubePlaylistId = targetPlaylist.id, youtubePlaylistName = targetPlaylist.title, localPlaylistId = local.id,
        ))
    }

    suspend fun preview(progress: (Int, Int) -> Unit) = mutex.withLock {
        engine.preview(dao.binding() ?: throw SyncException("BINDING_REQUIRED"), progress)
    }

    suspend fun sync(progress: (Int, Int) -> Unit) = mutex.withLock {
        val binding = dao.binding() ?: throw SyncException("BINDING_REQUIRED")
        engine.sync(binding, progress)
        try {
            syncUtils.syncPlaylistSuspend(binding.youtubePlaylistId, binding.localPlaylistId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Remote success is already durable; a local refresh must not turn into another add.
        }
    }

    suspend fun login(cookie: String): NeteaseUser = mutex.withLock { auth.login(cookie) }
    suspend fun allowRetry(songId: String) = mutex.withLock {
        engine.allowRetry(dao.binding() ?: throw SyncException("BINDING_REQUIRED"), songId)
    }
    suspend fun logout() = mutex.withLock { auth.logout(); dao.removeBinding() }
}

private fun SongItem.toSyncTrack() = SyncTrack(
    id, title, artists.map { it.name }, album?.name.orEmpty(), duration?.toLong()?.times(1000), thumbnail,
)
