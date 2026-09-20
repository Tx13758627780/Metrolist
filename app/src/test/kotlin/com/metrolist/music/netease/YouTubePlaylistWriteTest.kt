package com.metrolist.music.netease

import com.metrolist.music.utils.requirePlaylistAddSuccess
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaylistWriteTest {
    @Test fun `successful edit acknowledgement is accepted`() {
        requirePlaylistAddSuccess("""{"status":"STATUS_SUCCEEDED"}""")
    }

    @Test fun `HTTP 200 with failed or missing edit status is not success`() {
        for (body in listOf("{}", """{"status":"STATUS_FAILED","error":"private server data"}""")) {
            val error = assertThrows(SyncException::class.java) { requirePlaylistAddSuccess(body) }
            assertEquals("YOUTUBE_ADD_NOT_CONFIRMED", error.safeSyncError())
        }
    }
}
