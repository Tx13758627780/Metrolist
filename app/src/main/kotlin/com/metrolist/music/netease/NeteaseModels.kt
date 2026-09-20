package com.metrolist.music.netease

import com.metrolist.innertubex.InnerTubeHttpException

data class NeteaseUser(val id: Long, val nickname: String, val avatarUrl: String?)

data class NeteasePlaylist(val id: Long, val name: String, val trackCount: Int)

data class SyncTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val durationMs: Long? = null,
    val coverUrl: String? = null,
)

data class MatchCandidate(val track: SyncTrack, val score: Int)

enum class MatchStatus {
    MATCHED, SYNCED, ALREADY_EXISTS, NEEDS_REVIEW, NOT_FOUND, FAILED, IGNORED,
    // Written before the request: a killed process must reconcile, never blindly resend.
    ADDING, ADD_UNCONFIRMED, SOURCE_REMOVED,
}

data class MatchResult(val status: MatchStatus, val candidates: List<MatchCandidate>)

class NeteaseException(val code: Int) : Exception("NetEase API status $code")

class SyncException(val reason: String) : Exception(reason)

fun Throwable.safeSyncError(): String = when (this) {
    is InnerTubeHttpException -> when (status.value) {
        401 -> "YOUTUBE_LOGIN_REQUIRED"
        403 -> "YOUTUBE_FORBIDDEN_OR_REGION_RESTRICTED"
        404 -> "TARGET_UNAVAILABLE"
        429 -> "RATE_LIMITED"
        else -> "YOUTUBE_HTTP_${status.value}"
    }
    is NeteaseException -> when (code) {
        301, 302, 401 -> "NETEASE_LOGIN_REQUIRED"
        403 -> "NETEASE_FORBIDDEN"
        429 -> "RATE_LIMITED"
        else -> "NETEASE_API_$code"
    }
    is SyncException -> reason
    else -> "NETWORK_OR_SERVICE_ERROR"
}
