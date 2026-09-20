package com.metrolist.music.netease

import org.junit.Assert.*
import org.junit.Test

class SongMatcherTest {
    private val source = SyncTrack("163", "Porto Girl", listOf("Artist A"), "Album X", 222_000)

    @Test fun `studio wins over live remix cover and speed variants`() {
        val wrongVersions = listOf("Live", "Remix", "Cover", "Acoustic", "Instrumental", "Remastered",
            "Extended", "Sped Up", "Slowed", "Nightcore", "Karaoke", "现场", "翻唱", "伴奏", "混音", "重制")
            .mapIndexed { index, version -> source.copy(id = "$index", title = "Porto Girl ($version)") }
        val result = SongMatcher.match(source, wrongVersions + source.copy(id = "studio"))
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("studio", result.candidates.first().track.id)
        assertEquals(100, result.candidates.first().score)
        assertTrue(wrongVersions.all { SongMatcher.score(source, it) < 70 })
    }

    @Test fun `duration is used and missing duration cannot auto match`() {
        assertTrue(SongMatcher.score(source, source.copy(durationMs = 225_000)) >= 85)
        assertTrue(SongMatcher.score(source, source.copy(durationMs = 258_000)) < 70)
        assertEquals(MatchStatus.NEEDS_REVIEW, SongMatcher.match(source, listOf(source.copy(durationMs = null))).status)
    }

    @Test fun `different artists never auto match even with identical title`() {
        assertNotEquals(MatchStatus.MATCHED, SongMatcher.match(source, listOf(source.copy(artists = listOf("Artist B")))).status)
    }

    @Test fun `ambiguous editions require review and no candidates is not found`() {
        val withoutAlbum = source.copy(album = "")
        assertEquals(MatchStatus.NEEDS_REVIEW, SongMatcher.match(withoutAlbum,
            listOf(source.copy(id = "a"), source.copy(id = "b", album = "Compilation"))).status)
        assertEquals(MatchStatus.NOT_FOUND, SongMatcher.match(source, emptyList()).status)
    }

    @Test fun `unicode punctuation and diacritics normalize without discarding Chinese`() {
        val song = source.copy(title = "Café — 夜曲")
        assertEquals(100, SongMatcher.score(song, song.copy(title = "Cafe - 夜曲")))
        assertTrue(SongMatcher.score(song, song.copy(title = "Cafe - 另一首歌")) < 85)
    }

    @Test fun `cookie parser strips unrelated values and rejects header injection`() {
        assertEquals("MUSIC_U=example", NeteaseAuth.normalizeCookie("OTHER=ignored; MUSIC_U=example; Path=/"))
        assertThrows(SyncException::class.java) { NeteaseAuth.normalizeCookie("MUSIC_U=example\r\nInjected=yes") }
        assertThrows(SyncException::class.java) { NeteaseAuth.normalizeCookie("NMTID=anonymous") }
    }

    @Test fun `errors cannot expose response bodies or credentials`() {
        assertEquals("NETWORK_OR_SERVICE_ERROR", Exception("Cookie: MUSIC_U=secret; SAPISID=secret; Authorization: secret").safeSyncError())
        assertEquals("NETEASE_LOGIN_REQUIRED", NeteaseException(301).safeSyncError())
        assertEquals("RATE_LIMITED", com.metrolist.innertubex.InnerTubeHttpException(
            "search", io.ktor.http.HttpStatusCode.TooManyRequests,
        ).safeSyncError())
    }
}
