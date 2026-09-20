package com.metrolist.music.netease

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NeteaseSyncEngineTest {
    private val porto = SyncTrack("netease-porto", "Porto Girl", listOf("Artist A"), "Album X", 222_000)
    private val smile = SyncTrack("netease-smile", "Die With A Smile", listOf("Lady Gaga", "Bruno Mars"), "Die With A Smile", 251_000)
    private val binding = NeteaseBinding(neteaseUserId = 42, neteasePlaylistId = 123,
        neteasePlaylistName = "Source", youtubePlaylistId = "PLtarget", youtubePlaylistName = "Target", localPlaylistId = "LPtarget")
    private lateinit var database: InternalDatabase
    private lateinit var engine: NeteaseSyncEngine
    private lateinit var context: Context
    private var sourceTracks = listOf(porto)
    private val remoteTracks = mutableListOf<SyncTrack>()
    private val catalog = mutableListOf(porto.copy(id = "yt-porto"), smile.copy(id = "yt-smile"))
    private var writes = 0
    private var failAfterCommit = false
    private var rejectId: String? = null
    private var cancelAdd = false

    private val source = object : NeteaseSource {
        override suspend fun tracks(binding: NeteaseBinding) = sourceTracks
    }
    private val target = object : NeteaseSyncTarget {
        override suspend fun tracks(binding: NeteaseBinding) = remoteTracks.toList()
        override suspend fun search(query: String) = catalog.filter { query.startsWith(it.title) }
        override suspend fun addIfAbsent(binding: NeteaseBinding, videoId: String): Boolean {
            if (cancelAdd) throw CancellationException()
            if (videoId == rejectId) throw Exception("Forbidden: secret response")
            if (remoteTracks.any { it.id == videoId }) return false
            remoteTracks += catalog.first { it.id == videoId }
            writes++
            if (failAfterCommit) throw Exception("Timeout")
            return true
        }
    }

    private fun open() {
        database = Room.databaseBuilder(context, InternalDatabase::class.java, "netease-test.db").build()
        engine = NeteaseSyncEngine(database.neteaseSyncDao, source, target)
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("netease-test.db")
        open()
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("netease-test.db") }

    private suspend fun previewAndSync() {
        engine.preview(binding)
        engine.sync(requireNotNull(database.neteaseSyncDao.binding()))
    }
    private suspend fun rows() = database.neteaseSyncDao.mappings(123, "PLtarget")

    @Test fun `Porto Girl added once then newly added Die With A Smile survives restart`() = runBlocking {
        engine.preview(binding)
        assertEquals(0, writes)
        assertEquals("MATCHED", rows().single().status)
        engine.sync(requireNotNull(database.neteaseSyncDao.binding()))
        assertEquals(listOf("yt-porto"), remoteTracks.map { it.id })
        assertEquals("SYNCED", rows().single().status)
        previewAndSync()
        assertEquals(1, writes)
        database.close()
        open()
        assertEquals("SYNCED", rows().single().status)
        sourceTracks = listOf(porto, smile)
        previewAndSync()
        assertEquals(2, writes)
        assertEquals(setOf("yt-porto", "yt-smile"), remoteTracks.map { it.id }.toSet())
    }

    @Test fun `existing metadata prevents duplicate before any mapping exists`() = runBlocking {
        remoteTracks += porto.copy(id = "alternate-upload")
        previewAndSync()
        assertEquals(0, writes)
        assertEquals("ALREADY_EXISTS", rows().single().status)
        assertEquals("alternate-upload", rows().single().youtubeVideoId)
    }

    @Test fun `no result and low confidence persist without adding`() = runBlocking {
        catalog.clear()
        engine.preview(binding)
        assertEquals("NOT_FOUND", rows().single().status)
        catalog += porto.copy(id = "uncertain", durationMs = null)
        engine.preview(binding)
        assertEquals("NEEDS_REVIEW", rows().single().status)
        assertTrue(rows().single().candidatesJson.contains("uncertain"))
        engine.sync(requireNotNull(database.neteaseSyncDao.binding()))
        assertEquals(0, writes)
    }

    @Test fun `single track failure does not abort later tracks`() = runBlocking {
        sourceTracks = listOf(porto, smile)
        rejectId = "yt-porto"
        previewAndSync()
        assertEquals("ADD_UNCONFIRMED", rows().first { it.neteaseSongId == porto.id }.status)
        assertEquals("SYNCED", rows().first { it.neteaseSongId == smile.id }.status)
        assertEquals("NETWORK_OR_SERVICE_ERROR", rows().first { it.neteaseSongId == porto.id }.errorCode)
    }

    @Test fun `timeout after remote commit reconciles without another add`() = runBlocking {
        failAfterCommit = true
        previewAndSync()
        assertEquals("ADD_UNCONFIRMED", rows().single().status)
        database.close()
        open()
        previewAndSync()
        assertEquals(1, writes)
        assertEquals("ALREADY_EXISTS", rows().single().status)
    }

    @Test fun `cancelled write stays durable and is not automatically retried`() = runBlocking {
        cancelAdd = true
        engine.preview(binding)
        try { engine.sync(requireNotNull(database.neteaseSyncDao.binding())); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals("ADDING", rows().single().status)
        cancelAdd = false
        previewAndSync()
        assertEquals(0, writes)
        assertEquals("ADD_UNCONFIRMED", rows().single().status)
    }

    @Test fun `source deletion preserves extra target songs and removes pending write`() = runBlocking {
        engine.preview(binding)
        sourceTracks = emptyList()
        remoteTracks += smile.copy(id = "unrelated-extra")
        engine.sync(requireNotNull(database.neteaseSyncDao.binding()))
        assertEquals(0, writes)
        assertEquals("unrelated-extra", remoteTracks.single().id)
    }

    @Test fun `target change between preview and sync prevents another add`() = runBlocking {
        engine.preview(binding)
        remoteTracks += porto.copy(id = "yt-porto")
        engine.sync(requireNotNull(database.neteaseSyncDao.binding()))
        assertEquals(0, writes)
        assertEquals("ALREADY_EXISTS", rows().single().status)
    }

    @Test fun `explicit recovery permits failed additions only after checking remote`() = runBlocking {
        rejectId = "yt-porto"
        previewAndSync()
        assertEquals("ADD_UNCONFIRMED", rows().single().status)
        rejectId = null
        engine.allowRetry(binding, porto.id)
        assertEquals(0, writes)
        assertFalse(requireNotNull(database.neteaseSyncDao.binding()).previewReady)
        previewAndSync()
        assertEquals(1, writes)
    }

    @Test fun `uncertain candidate already in target never becomes a confirmed mapping`() = runBlocking {
        catalog.clear()
        val uncertain = porto.copy(id = "uncertain", durationMs = null)
        catalog += uncertain
        remoteTracks += uncertain
        engine.preview(binding)
        assertEquals("NEEDS_REVIEW", rows().single().status)
        assertNull(rows().single().youtubeVideoId)
        engine.preview(binding)
        assertEquals("NEEDS_REVIEW", rows().single().status)
        assertEquals(0, writes)
    }

    @Test fun `unavailable song metadata is refreshed when source recovers`() = runBlocking {
        sourceTracks = listOf(porto.copy(title = "", artists = emptyList(), durationMs = null))
        engine.preview(binding)
        assertEquals("FAILED", rows().single().status)
        sourceTracks = listOf(porto)
        previewAndSync()
        assertEquals("Porto Girl", rows().single().neteaseTitle)
        assertEquals(222_000L, rows().single().durationMs)
        assertEquals(1, writes)
    }
}
