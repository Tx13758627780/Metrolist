package com.metrolist.music.netease

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface NeteaseSource {
    suspend fun tracks(binding: NeteaseBinding): List<SyncTrack>
}

interface NeteaseSyncTarget {
    suspend fun tracks(binding: NeteaseBinding): List<SyncTrack>
    suspend fun search(query: String): List<SyncTrack>
    suspend fun addIfAbsent(binding: NeteaseBinding, videoId: String): Boolean
}

class NeteaseSyncEngine(
    private val dao: NeteaseSyncDao,
    private val source: NeteaseSource,
    private val target: NeteaseSyncTarget,
) {
    suspend fun preview(binding: NeteaseBinding, progress: (Int, Int) -> Unit = { _, _ -> }) {
        dao.putBinding(binding.copy(previewReady = false))
        val songs = source.tracks(binding).distinctBy { it.id }
        val remote = target.tracks(binding)
        val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
        val previous = dao.mappings(binding.neteasePlaylistId, binding.youtubePlaylistId).associateBy { it.neteaseSongId }
        val sourceIds = songs.mapTo(mutableSetOf()) { it.id }
        previous.values.filter { it.neteaseSongId !in sourceIds && it.status in setOf("MATCHED", "NEEDS_REVIEW", "NOT_FOUND", "FAILED") }
            .forEach { dao.record(it.copy(status = "SOURCE_REMOVED", updatedAt = System.currentTimeMillis())) }
        songs.forEachIndexed { index, song ->
            progress(index + 1, songs.size)
            val old = previous[song.id]
            // Add Only leaves a deliberate deletion on YouTube alone once this source ID was synced.
            if (old?.status in setOf("SYNCED", "ALREADY_EXISTS", "IGNORED")) return@forEachIndexed
            var mapping = (old ?: NeteaseSyncMapping(
                neteasePlaylistId = binding.neteasePlaylistId, youtubePlaylistId = binding.youtubePlaylistId,
                neteaseSongId = song.id, neteaseTitle = song.title, neteaseArtists = song.artists.joinToString(" / "),
                neteaseAlbum = song.album, durationMs = song.durationMs, coverUrl = song.coverUrl,
                status = MatchStatus.NOT_FOUND.name,
            )).copy(neteaseTitle = song.title, neteaseArtists = song.artists.joinToString(" / "),
                neteaseAlbum = song.album, durationMs = song.durationMs, coverUrl = song.coverUrl)
            try {
                mapping = when {
                    old?.status in setOf("MATCHED", "ADDING", "ADD_UNCONFIRMED") && old?.youtubeVideoId in remoteIds ->
                        mapping.copy(status = "ALREADY_EXISTS", errorCode = null)
                    old?.status in setOf("ADDING", "ADD_UNCONFIRMED") -> mapping.copy(status = "ADD_UNCONFIRMED", errorCode = "VERIFY_TARGET_BEFORE_RETRY")
                    song.title.isBlank() || song.artists.isEmpty() -> mapping.copy(status = "FAILED", errorCode = "SOURCE_TRACK_UNAVAILABLE")
                    else -> {
                        val existing = SongMatcher.match(song, remote)
                        if (existing.status == MatchStatus.MATCHED) {
                            mapping.withMatch(existing, "ALREADY_EXISTS")
                        } else if (old?.status == "MATCHED" && old.youtubeVideoId != null &&
                            old.neteaseTitle == mapping.neteaseTitle && old.neteaseArtists == mapping.neteaseArtists &&
                            old.neteaseAlbum == mapping.neteaseAlbum && old.durationMs == mapping.durationMs) {
                            mapping
                        } else {
                            val query = "${song.title} ${song.artists.first()}"
                            val candidates = target.search(query).take(10)
                            var match = SongMatcher.match(song, candidates)
                            if (match.status != MatchStatus.MATCHED && song.album.isNotBlank()) {
                                match = SongMatcher.match(song, candidates + target.search("$query ${song.album}").take(10))
                            }
                            mapping.withMatch(match, match.status.name)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mapping = mapping.copy(status = "FAILED", errorCode = error.safeSyncError())
            }
            dao.record(mapping.copy(updatedAt = System.currentTimeMillis()))
        }
        dao.putBinding(binding.copy(previewReady = true, sourceTrackCount = songs.size, targetTrackCount = remote.size))
    }

    suspend fun sync(binding: NeteaseBinding, progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (!binding.previewReady) throw SyncException("PREVIEW_REQUIRED")
        // Re-read both lists before any write; entries removed since preview are never added.
        val sourceIds = source.tracks(binding).mapTo(mutableSetOf()) { it.id }
        val remote = target.tracks(binding).toMutableList()
        val rows = dao.mappings(binding.neteasePlaylistId, binding.youtubePlaylistId)
            .filter { it.status == "MATCHED" && it.neteaseSongId in sourceIds && it.youtubeVideoId != null }
        rows.forEachIndexed { index, row ->
            progress(index + 1, rows.size)
            val song = SyncTrack(row.neteaseSongId, row.neteaseTitle, row.neteaseArtists.split(" / "), row.neteaseAlbum, row.durationMs)
            if (remote.any { it.id == row.youtubeVideoId } || SongMatcher.match(song, remote).status == MatchStatus.MATCHED) {
                dao.record(row.copy(status = "ALREADY_EXISTS", updatedAt = System.currentTimeMillis()))
                return@forEachIndexed
            }
            dao.record(row.copy(status = "ADDING", updatedAt = System.currentTimeMillis()))
            try {
                val added = target.addIfAbsent(binding, requireNotNull(row.youtubeVideoId))
                dao.record(row.copy(status = if (added) "SYNCED" else "ALREADY_EXISTS", errorCode = null, updatedAt = System.currentTimeMillis()))
                remote += song.copy(id = requireNotNull(row.youtubeVideoId))
            } catch (cancelled: CancellationException) {
                // ADDING survives process death/cancellation and is reconciled by the next preview.
                throw cancelled
            } catch (error: Exception) {
                dao.record(row.copy(status = "ADD_UNCONFIRMED", errorCode = error.safeSyncError(), updatedAt = System.currentTimeMillis()))
            }
        }
        dao.putBinding(binding.copy(lastSyncAt = System.currentTimeMillis(), previewReady = false))
    }

    suspend fun allowRetry(binding: NeteaseBinding, songId: String) {
        val row = dao.mappings(binding.neteasePlaylistId, binding.youtubePlaylistId).firstOrNull {
            it.neteaseSongId == songId && it.status in setOf("ADDING", "ADD_UNCONFIRMED")
        } ?: return
        val remote = target.tracks(binding)
        dao.record(row.copy(
            status = if (remote.any { it.id == row.youtubeVideoId }) "ALREADY_EXISTS" else "MATCHED",
            errorCode = null, updatedAt = System.currentTimeMillis(),
        ))
        dao.putBinding(binding.copy(previewReady = false))
    }

    private fun NeteaseSyncMapping.withMatch(match: MatchResult, status: String): NeteaseSyncMapping {
        val best = match.candidates.firstOrNull()
        return copy(
            youtubeVideoId = best?.track?.id?.takeIf { status in setOf("MATCHED", "ALREADY_EXISTS") }, youtubeTitle = best?.track?.title,
            youtubeArtists = best?.track?.artists?.joinToString(" / "), matchScore = best?.score ?: 0,
            status = status, errorCode = null,
            candidatesJson = buildJsonArray {
                match.candidates.forEach { candidate -> add(buildJsonObject {
                    put("videoId", candidate.track.id); put("title", candidate.track.title)
                    put("artists", candidate.track.artists.joinToString(" / ")); put("album", candidate.track.album)
                    put("durationMs", candidate.track.durationMs); put("score", candidate.score)
                }) }
            }.toString(),
        )
    }
}
