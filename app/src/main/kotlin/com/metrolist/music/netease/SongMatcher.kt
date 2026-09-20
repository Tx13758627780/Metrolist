package com.metrolist.music.netease

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object SongMatcher {
    private val versions = listOf(
        "\\blive\\b|现场", "\\bremix\\b|混音", "\\b(acoustic|unplugged)\\b|不插电",
        "\\b(instrumental|karaoke)\\b|伴奏", "\\bcover\\b|翻唱", "\\bremaster(?:ed)?\\b|重制",
        "\\bextended\\b", "\\bsped[ -]?up\\b", "\\bslowed\\b", "\\bnightcore\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun similarity(left: String, right: String): Double {
        val a = normalize(left).take(300)
        val b = normalize(right).take(300)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) {
                next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = next
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    fun score(source: SyncTrack, candidate: SyncTrack): Int {
        val title = similarity(source.title, candidate.title)
        val artist = source.artists.map { expected ->
            candidate.artists.maxOfOrNull { similarity(expected, it) } ?: 0.0
        }.average().takeUnless { it.isNaN() } ?: 0.0
        val durationDelta = if ((source.durationMs ?: 0) > 0 && (candidate.durationMs ?: 0) > 0) {
            abs(source.durationMs!! - candidate.durationMs!!)
        } else null
        val duration = when {
            durationDelta == null -> 0.0
            durationDelta <= 3_000 -> 1.0
            durationDelta <= 5_000 -> 0.95
            durationDelta <= 10_000 -> 0.7
            durationDelta <= 20_000 -> 0.3
            else -> 0.0
        }
        val sameVersion = versions.all {
            it.containsMatchIn(source.title + " " + source.album) ==
                it.containsMatchIn(candidate.title + " " + candidate.album)
        }
        val weighted = (40 * title + 30 * artist + 15 * duration +
            10 * similarity(source.album, candidate.album) + if (sameVersion) 5 else 0).roundToInt()
        // A high aggregate score must never hide conflicting artists, editions or duration.
        val ceiling = when {
            !sameVersion || (durationDelta != null && durationDelta > 30_000) -> 69
            title < 0.9 || artist < 0.95 || durationDelta == null || durationDelta > 10_000 -> 84
            else -> 100
        }
        return weighted.coerceIn(0, ceiling)
    }

    fun match(source: SyncTrack, candidates: List<SyncTrack>): MatchResult {
        val ranked = candidates.distinctBy { it.id }.map { MatchCandidate(it, score(source, it)) }
            .sortedByDescending { it.score }.take(10)
        val best = ranked.firstOrNull()
        // Multiple uploads of identical metadata are acceptable; materially different near ties need review.
        val ambiguous = best != null && ranked.drop(1).any {
            best.score - it.score < 4 &&
                (normalize(best.track.title) != normalize(it.track.title) ||
                    normalize(best.track.album) != normalize(it.track.album) ||
                    best.track.artists.map(::normalize) != it.track.artists.map(::normalize))
        }
        return MatchResult(when {
            best == null || best.score < 70 -> MatchStatus.NOT_FOUND
            best.score < 85 || ambiguous -> MatchStatus.NEEDS_REVIEW
            else -> MatchStatus.MATCHED
        }, ranked)
    }
}
