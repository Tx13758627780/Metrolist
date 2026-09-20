package com.metrolist.music.netease

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NeteaseApiTest {
    @Test fun `playlist reads track ids in batches and preserves unavailable ids`() = runBlocking {
        var detailRequests = 0
        val engine = MockEngine { request ->
            assertEquals("music.163.com", request.url.host)
            assertTrue(request.headers["Cookie"].orEmpty().contains("MUSIC_U=test"))
            val body = when (request.url.encodedPath) {
                "/api/v6/playlist/detail" -> """{"code":200,"playlist":{"name":"Test","trackCount":102,"trackIds":[${(1..102).joinToString { "{\"id\":$it}" }}]}}"""
                "/api/v3/song/detail" -> {
                    detailRequests++
                    if (detailRequests == 1) """{"code":200,"songs":[{"id":1,"name":"Porto Girl","dt":222000,"ar":[{"name":"Artist A"}],"al":{"name":"Album X","picUrl":"https://example.com/cover"}}]}"""
                    else """{"code":200,"songs":[]}"""
                }
                else -> error("Unexpected API request")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        HttpClient(engine).use { client ->
            val tracks = NeteaseApi(client).getPlaylistTracks(10, "MUSIC_U=test")
            assertEquals(102, tracks.size)
            assertEquals(2, detailRequests)
            assertEquals(222_000L, tracks.first().durationMs)
            assertEquals("Artist A", tracks.first().artists.single())
            assertEquals("102", tracks.last().id)
            assertTrue(tracks.last().title.isEmpty())
        }
    }

    @Test fun `truncated source list is rejected before syncing`() = runBlocking {
        val engine = MockEngine { respond("""{"code":200,"playlist":{"name":"Test","trackCount":20,"trackIds":[{"id":1}]}}""") }
        HttpClient(engine).use { client ->
            try { NeteaseApi(client).getPlaylistTracks(10, "MUSIC_U=test"); fail("Expected incomplete playlist") }
            catch (error: SyncException) { assertEquals("INCOMPLETE_SOURCE_PLAYLIST", error.reason) }
        }
    }

    @Test fun `expired session never becomes a successful login`() = runBlocking {
        HttpClient(MockEngine { respond("""{"code":200,"profile":null}""") }).use { client ->
            try { NeteaseApi(client).getUser("MUSIC_U=expired"); fail("Expected login failure") }
            catch (error: NeteaseException) { assertEquals(301, error.code) }
        }
    }

    @Test fun `pagination reads all user playlists`() = runBlocking {
        var requests = 0
        HttpClient(MockEngine {
            requests++
            respond("""{"code":200,"more":${requests == 1},"playlist":[{"id":$requests,"name":"Playlist $requests","trackCount":1}]}""")
        }).use { client ->
            assertEquals(listOf(1L, 2L), NeteaseApi(client).getUserPlaylists(42, "MUSIC_U=test").map { it.id })
        }
    }
}
