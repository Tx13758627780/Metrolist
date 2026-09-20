package com.metrolist.music.netease

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeteaseAuth @Inject constructor(private val api: NeteaseApi, private val cookies: NeteaseCookieStore) {
    // QR login can hand its Set-Cookie values to this same validated, encrypted session path.
    suspend fun login(rawCookie: String): NeteaseUser {
        val cookie = normalizeCookie(rawCookie)
        val user = api.getUser(cookie)
        cookies.write(cookie)
        return user
    }

    suspend fun currentUser(): NeteaseUser = api.getUser(cookie())
    fun cookie(): String = cookies.read() ?: throw NeteaseException(301)
    fun logout() = cookies.clear()

    companion object {
        internal fun normalizeCookie(raw: String): String {
            if (raw.length > 16_384 || raw.any { it == '\r' || it == '\n' || it.code < 32 }) {
                throw SyncException("INVALID_COOKIE")
            }
            val values = raw.split(';').mapNotNull {
                val parts = it.trim().split('=', limit = 2)
                if (parts.size == 2 && parts[0] in setOf("MUSIC_U", "__csrf", "NMTID") && parts[1].isNotBlank()) {
                    parts[0] to parts[1]
                } else null
            }.toMap()
            if (values["MUSIC_U"].isNullOrBlank()) throw SyncException("INVALID_COOKIE")
            return values.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }
    }
}
