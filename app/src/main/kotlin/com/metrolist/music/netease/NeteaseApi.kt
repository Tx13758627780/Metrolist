// Playlist metadata protocol adapted from Suxiaoqinx/Netease_url (MIT).
// See app/src/main/assets/licenses/Netease_url.txt.
package com.metrolist.music.netease

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeteaseApi internal constructor(private val client: HttpClient) {
    @Inject constructor() : this(HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response -> response.status.value == 429 || response.status.value in 500..599 }
            retryOnExceptionIf { _, cause -> cause is IOException }
            exponentialDelay(maxDelayMs = 30_000)
        }
    })

    private suspend fun request(path: String, cookie: String, fields: Map<String, String> = emptyMap()): JsonObject {
        val response = client.submitForm(
            url = "https://music.163.com/api/$path",
            formParameters = Parameters.build { fields.forEach { (key, value) -> append(key, value) } },
        ) {
            header("Cookie", "$cookie; os=pc; appver=2.10.2")
            header("Referer", "https://music.163.com/")
            header("User-Agent", "Mozilla/5.0 NeteaseMusicDesktop/2.10.2.200154")
        }
        if (response.status.value != 200) throw NeteaseException(response.status.value)
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val code = result.number("code").toInt()
        if (code != 200) throw NeteaseException(code)
        return result
    }

    suspend fun getUser(cookie: String): NeteaseUser {
        val result = request("w/nuser/account/get", cookie)
        val profile = result["profile"] as? JsonObject ?: throw NeteaseException(301)
        val id = profile.number("userId")
        if (id <= 0) throw NeteaseException(301)
        return NeteaseUser(id, profile.text("nickname"), profile.text("avatarUrl").ifBlank { null })
    }

    suspend fun getUserPlaylists(userId: Long, cookie: String): List<NeteasePlaylist> {
        require(userId > 0)
        val playlists = mutableListOf<NeteasePlaylist>()
        var offset = 0
        repeat(100) {
            val page = request("user/playlist", cookie,
                mapOf("uid" to "$userId", "limit" to "100", "offset" to "$offset", "includeVideo" to "false"))
            val items = page.objects("playlist")
            playlists += items.map { NeteasePlaylist(it.number("id"), it.text("name"), it.number("trackCount").toInt()) }
            if (page["more"]?.jsonPrimitive?.content != "true") return playlists.distinctBy { it.id }
            if (items.isEmpty()) throw SyncException("INCOMPLETE_SOURCE_PLAYLISTS")
            offset += items.size
        }
        throw SyncException("SOURCE_PAGINATION_LIMIT")
    }

    suspend fun getPlaylist(id: Long, cookie: String): Pair<NeteasePlaylist, List<Long>> {
        require(id > 0)
        val data = request("v6/playlist/detail", cookie, mapOf("id" to "$id", "n" to "100000", "s" to "0"))
        val playlist = data["playlist"] as? JsonObject ?: throw SyncException("SOURCE_PLAYLIST_UNAVAILABLE")
        val ids = playlist.objects("trackIds").map { it.number("id") }.distinct()
        val count = playlist.number("trackCount").toInt()
        if (count > ids.size) throw SyncException("INCOMPLETE_SOURCE_PLAYLIST")
        return NeteasePlaylist(id, playlist.text("name"), count) to ids
    }

    suspend fun getSongDetail(ids: List<Long>, cookie: String): List<SyncTrack> {
        if (ids.isEmpty()) return emptyList()
        require(ids.size <= 100 && ids.all { it > 0 })
        val data = buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id); put("v", 0) }) } }
        return request("v3/song/detail", cookie, mapOf("c" to data.toString())).objects("songs").map { song ->
            val album = song["al"] as? JsonObject
            SyncTrack(
                id = song.number("id").toString(), title = song.text("name"),
                artists = song.objects("ar").map { it.text("name") },
                album = album?.text("name").orEmpty(),
                durationMs = song.number("dt").takeIf { it > 0 },
                coverUrl = album?.text("picUrl")?.ifBlank { null },
            )
        }
    }

    suspend fun getPlaylistTracks(id: Long, cookie: String): List<SyncTrack> {
        val (_, ids) = getPlaylist(id, cookie)
        val tracks = ids.chunked(100).flatMap { getSongDetail(it, cookie) }.associateBy { it.id }
        // Preserve unavailable IDs so the sync log can explain omissions rather than silently dropping them.
        return ids.map { tracks["$it"] ?: SyncTrack("$it", "", emptyList()) }
    }
}

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }
