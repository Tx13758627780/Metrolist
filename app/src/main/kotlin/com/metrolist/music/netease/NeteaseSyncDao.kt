package com.metrolist.music.netease

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "netease_binding")
data class NeteaseBinding(
    @PrimaryKey val id: Int = 1,
    val neteaseUserId: Long,
    val neteasePlaylistId: Long,
    val neteasePlaylistName: String,
    val youtubePlaylistId: String,
    val youtubePlaylistName: String,
    val localPlaylistId: String,
    val previewReady: Boolean = false,
    val sourceTrackCount: Int = 0,
    val targetTrackCount: Int = 0,
    val lastSyncAt: Long? = null,
)

@Entity(tableName = "netease_mapping", primaryKeys = ["neteasePlaylistId", "youtubePlaylistId", "neteaseSongId"])
data class NeteaseSyncMapping(
    val neteasePlaylistId: Long,
    val youtubePlaylistId: String,
    val neteaseSongId: String,
    val neteaseTitle: String,
    val neteaseArtists: String,
    val neteaseAlbum: String,
    val durationMs: Long?,
    val coverUrl: String?,
    val youtubeVideoId: String? = null,
    val youtubeTitle: String? = null,
    val youtubeArtists: String? = null,
    val matchScore: Int = 0,
    val status: String,
    val candidatesJson: String = "[]",
    val errorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "netease_sync_log")
data class NeteaseSyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val neteasePlaylistId: Long,
    val youtubePlaylistId: String,
    val neteaseSongId: String,
    val title: String,
    val status: String,
    val matchScore: Int,
    val errorCode: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

@Dao
abstract class NeteaseSyncDao {
    @Query("SELECT * FROM netease_binding WHERE id = 1")
    abstract fun observeBinding(): Flow<NeteaseBinding?>

    @Query("SELECT * FROM netease_binding WHERE id = 1")
    abstract suspend fun binding(): NeteaseBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putBinding(binding: NeteaseBinding)

    @Query("DELETE FROM netease_binding")
    abstract suspend fun removeBinding()

    @Query("SELECT * FROM netease_mapping WHERE neteasePlaylistId = :source AND youtubePlaylistId = :target ORDER BY createdAt, neteaseSongId")
    abstract suspend fun mappings(source: Long, target: String): List<NeteaseSyncMapping>

    @Query("SELECT * FROM netease_mapping WHERE neteasePlaylistId = :source AND youtubePlaylistId = :target ORDER BY createdAt, neteaseSongId")
    abstract fun observeMappings(source: Long, target: String): Flow<List<NeteaseSyncMapping>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMapping(mapping: NeteaseSyncMapping)

    @Insert
    abstract suspend fun insertLog(log: NeteaseSyncLog)

    @Query("SELECT * FROM netease_sync_log ORDER BY id DESC LIMIT 300")
    abstract fun observeLogs(): Flow<List<NeteaseSyncLog>>

    @Query("DELETE FROM netease_sync_log WHERE id NOT IN (SELECT id FROM netease_sync_log ORDER BY id DESC LIMIT 2000)")
    abstract suspend fun pruneLogs()

    @Transaction
    open suspend fun record(mapping: NeteaseSyncMapping) {
        putMapping(mapping)
        insertLog(NeteaseSyncLog(
            neteasePlaylistId = mapping.neteasePlaylistId, youtubePlaylistId = mapping.youtubePlaylistId,
            neteaseSongId = mapping.neteaseSongId, title = mapping.neteaseTitle,
            status = mapping.status, matchScore = mapping.matchScore, errorCode = mapping.errorCode,
        ))
        pruneLogs()
    }
}
