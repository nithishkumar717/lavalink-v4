package lavalink.server.yuna

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.time.ZonedDateTime
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * YUNA's music recommender, hosted inside the Lavalink process.
 * YUNA supplies candidate metadata and session signals; Lavalink scores, ranks,
 * and selects a candidate. Playback still uses the normal Lavalink player API.
 */
@RestController
@RequestMapping("/v4/yuna")
class YunaRecommendationRestHandler {
    @PostMapping("/rank")
    fun rank(@RequestBody request: YunaRankRequest): YunaRankResponse {
        if (request.candidates.size > MAX_CANDIDATES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At most $MAX_CANDIDATES candidates are supported")
        }
        if (request.candidates.isEmpty()) return YunaRankResponse(emptyList(), null)

        val scored = request.candidates.map { candidate ->
            candidate to YunaRanker.score(candidate, request.session, request.userStats)
        }

        val targetLanguage = request.session.userLockedLanguage
            ?: request.session.hardLanguage
            ?: request.session.activeLanguage.takeIf { request.session.languageEstablished }
        val gated = scored.map { (candidate, score) ->
            val language = candidate.language.normalized()
            val target = targetLanguage.normalized()
            val hardMismatch = !target.isNullOrBlank() && target !in setOf("unknown", "auto", "english") &&
                language != target && !candidate.languageConfirmed
            candidate to if (hardMismatch) REJECT_SCORE else score
        }

        val blended = YunaRanker.blendSessionSimilarity(gated, request.session.recentTracks)
        val played = request.session.playedTrackIds.toSet()
        val diversityAdjusted = blended.mapNotNull { (candidate, rawScore) ->
            if (candidate.title.isBlank() || rawScore <= -100.0) return@mapNotNull null
            val artist = normalizeArtist(candidate.primaryArtist())
            val id = candidate.id.trim()
            val titleKey = normalizeTitle(candidate.title)
            if ((id.isNotEmpty() && id in played) || (candidate.url.isNotEmpty() && candidate.url in played) ||
                (titleKey.isNotEmpty() && titleKey in played)) return@mapNotNull null

            val recent = request.session.recentArtists.map(::normalizeArtist)
            val penalty = when {
                recent.lastOrNull() == artist && artist.isNotEmpty() -> 40.0
                recent.takeLast(2).contains(artist) && artist.isNotEmpty() -> 25.0
                else -> 0.0
            }
            candidate to (rawScore - penalty)
        }.sortedByDescending { it.second }

        val selected = chooseWithBandit(
            diversityAdjusted,
            request.session,
            request.exploreRate.coerceIn(0.0, 1.0),
            request.temperature.coerceAtLeast(1.0),
            request.randomSeed
        )
        val ranked = diversityAdjusted.map { (candidate, score) -> YunaRankedCandidate(candidate, score) }
        return YunaRankResponse(ranked, selected?.let { YunaRankedCandidate(it.first, it.second) })
    }

    private fun chooseWithBandit(
        candidates: List<Pair<YunaTrack, Double>>,
        session: YunaSessionContext,
        exploreRate: Double,
        temperature: Double,
        seed: Long?
    ): Pair<YunaTrack, Double>? {
        val top = candidates.take(12)
        if (top.isEmpty()) return null
        val random = seed?.let(::Random) ?: Random()
        if (top.size > 1 && random.nextDouble() < exploreRate) {
            val lastArtist = session.recentArtists.lastOrNull()?.let(::normalizeArtist)
            val explore = top.drop(1).filter { (track, score) ->
                score > 0.0 && (lastArtist.isNullOrEmpty() || normalizeArtist(track.primaryArtist()) != lastArtist)
            }
            if (explore.isNotEmpty()) return explore[random.nextInt(explore.size)]
        }

        val pool = top.take(6).filter { it.second > 0.0 }
        if (pool.size <= 1) return top.first()
        val maxScore = pool.first().second
        val weights = pool.map { (_, score) -> exp(((score - maxScore) / temperature).coerceAtLeast(-10.0)) }
        val total = weights.sum()
        var pick = random.nextDouble() * total
        for (index in pool.indices) {
            pick -= weights[index]
            if (pick <= 0.0) return pool[index]
        }
        return pool.last()
    }

    companion object {
        private const val MAX_CANDIDATES = 200
        private const val REJECT_SCORE = -9999.0
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class YunaRankRequest(
    val candidates: List<YunaTrack> = emptyList(),
    val session: YunaSessionContext = YunaSessionContext(),
    val userStats: Map<String, YunaArtistStats> = emptyMap(),
    val exploreRate: Double = 0.10,
    val temperature: Double = 12.0,
    val randomSeed: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YunaTrack(
    @JsonAlias("yt_video_id", "spotify_id") val id: String = "",
    val title: String = "",
    val artist: String = "",
    val uploader: String = "",
    val channel: String = "",
    @JsonAlias("duration") val durationSeconds: Double = 0.0,
    @JsonAlias("view_count") val viewCount: Double = 0.0,
    @JsonAlias("like_count") val likeCount: Double = 0.0,
    @JsonAlias("channel_is_verified") val channelIsVerified: Boolean = false,
    val language: String = "unknown",
    @JsonAlias("language_source") val languageSource: String = "",
    @JsonAlias("language_confirmed") val languageConfirmed: Boolean = false,
    val mood: String = "auto",
    @JsonAlias("album_name") val albumName: String = "",
    val url: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YunaArtistStats(val plays: Double = 0.0, val skips: Double = 0.0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YunaSessionContext(
    @JsonAlias("recent_tracks") val recentTracks: List<YunaTrack> = emptyList(),
    @JsonAlias("recent_artists") val recentArtists: List<String> = emptyList(),
    @JsonAlias("active_language") val activeLanguage: String = "unknown",
    @JsonAlias("language_established") val languageEstablished: Boolean = false,
    @JsonAlias("hard_language") val hardLanguage: String? = null,
    @JsonAlias("user_locked_language") val userLockedLanguage: String? = null,
    @JsonAlias("target_mood") val targetMood: String = "auto",
    @JsonAlias("mood_counts") val moodCounts: Map<String, Int> = emptyMap(),
    @JsonAlias("skipped_history") val skippedHistory: List<String> = emptyList(),
    @JsonAlias("played_track_ids") val playedTrackIds: List<String> = emptyList()
)

data class YunaRankedCandidate(val candidate: YunaTrack, val score: Double)
data class YunaRankResponse(val ranked: List<YunaRankedCandidate>, val selected: YunaRankedCandidate?)

/** Port of YUNA's active 26-feature ranker, deterministic text embeddings, and
 * contextual bandit. The Python source uses a LightGBM booster only when a
 * trained model file exists; this deployment has no booster file, so its
 * active scoring path is the same weighted fallback implemented here.
 */
private object YunaRanker {
    private val weights = doubleArrayOf(
        0.0, 35.0, 30.0, -40.0, 15.0, 2.0, 3.0, 10.0, 25.0, -15.0,
        -30.0, 60.0, -250.0, -25.0, 45.0, 20.0, 10.0, 0.0, -40.0,
        0.0, 0.0, 0.0, 5.0, 25.0, 20.0, -30.0
    )
    private val pureAudio = listOf("official audio", "audio song", "original audio", "album track", "studio version", "original sound track", "official track", "lyric video", "lyrical video", "lyrical")
    private val theatrical = listOf("video song", "full video song", "full video", "movie video", "film version", "movie version", "theatrical version")
    private val majorLabels = listOf("sony music", "sonymusic", "t-series", "tseries", "zee music", "saregama", "think music", "aditya music", "lahari", "vevo", "yrf", "tips", "speed records", "muzik247", "warner", "universal")
    private val tokensPattern = Regex("[\\p{L}\\p{N}]+")

    fun score(track: YunaTrack, session: YunaSessionContext, userStats: Map<String, YunaArtistStats>): Double {
        val title = track.title.lowercase(Locale.ROOT)
        val uploader = (track.uploader.ifBlank { track.channel.ifBlank { track.artist } }).lowercase(Locale.ROOT)
        val artist = normalizeArtist(uploader)
        val duration = track.durationSeconds.coerceAtLeast(0.0)
        val views = track.viewCount.coerceAtLeast(0.0)
        val likes = track.likeCount.coerceAtLeast(0.0)
        val recent = session.recentArtists.map(::normalizeArtist)
        val language = track.language.normalized() ?: "unknown"
        val active = session.activeLanguage.normalized() ?: "unknown"
        val targetMood = session.targetMood.normalized() ?: "auto"
        val mood = track.mood.normalized() ?: "auto"
        val totalMoods = session.moodCounts.values.sum()
        val skipped = normalizeTitle(track.title).let { normalized ->
            session.skippedHistory.any { entry ->
                val old = normalizeTitle(entry)
                normalized.isNotEmpty() && old.isNotEmpty() && (normalized.contains(old) || old.contains(normalized))
            }
        }
        val stats = userStats[artist] ?: userStats.entries.firstOrNull { normalizeArtist(it.key) == artist }?.value
        val features = doubleArrayOf(
            duration,
            if ("- topic" in uploader) 1.0 else 0.0,
            if (pureAudio.any(title::contains) || "lyrical" in title) 1.0 else 0.0,
            if (theatrical.any(title::contains)) 1.0 else 0.0,
            if (track.channelIsVerified) 1.0 else 0.0,
            ln(views + 1.0),
            ln(likes + 1.0),
            if (views > 0.0) likes / views else 0.0,
            if (recent.lastOrNull() == artist) 1.0 else 0.0,
            recent.count { it == artist }.toDouble(),
            if (recent.takeLast(2).contains(artist)) 1.0 else 0.0,
            if (active !in setOf("unknown", "auto") && language == active) 1.0 else 0.0,
            if (active !in setOf("unknown", "auto") && language !in setOf("unknown", "auto", active)) 1.0 else 0.0,
            if (language in setOf("unknown", "auto")) 1.0 else 0.0,
            if (active !in setOf("unknown", "auto") && track.languageSource == "explicit_version_tag" && language == active) 1.0
            else if (active !in setOf("unknown", "auto") && Regex("[\\(\\[\\{/\\-_|]\\s*${Regex.escape(active)}").containsMatchIn(title)) 1.0 else 0.0,
            if (targetMood != "auto" && mood == targetMood) 1.0 else 0.0,
            if (totalMoods > 0 && mood in session.moodCounts) session.moodCounts[mood]!!.toDouble() / totalMoods else 0.0,
            session.recentTracks.size.toDouble(),
            if (skipped) 1.0 else 0.0,
            ZonedDateTime.now().hour / 24.0,
            if (ZonedDateTime.now().dayOfWeek.value >= 6) 1.0 else 0.0,
            title.trim().split(Regex("\\s+")).count { it.isNotBlank() }.toDouble(),
            if (listOf("feat", "ft.", "featuring").any(title::contains)) 1.0 else 0.0,
            if (majorLabels.any(uploader::contains)) 1.0 else 0.0,
            ln((stats?.plays ?: 0.0) + 1.0),
            if ((stats?.plays ?: 0.0) > 0.0) (stats?.skips ?: 0.0) / stats!!.plays else 0.0
        )
        return features.indices.sumOf { features[it] * weights[it] }
    }

    fun blendSessionSimilarity(scored: List<Pair<YunaTrack, Double>>, recentTracks: List<YunaTrack>): List<Pair<YunaTrack, Double>> {
        if (recentTracks.isEmpty() || scored.isEmpty()) return scored
        val session = sessionVector(recentTracks.takeLast(10))
        if (norm(session) < 1e-6) return scored
        val usable = scored.map { it.second }.filter { it > -100.0 }
        if (usable.isEmpty()) return scored
        val low = usable.minOrNull()!!
        val high = usable.maxOrNull()!!
        return scored.map { (track, score) ->
            if (score <= -100.0) track to score
            else {
                val modelScore = if (high - low < 1e-6) 0.5 else (score - low) / (high - low)
                val semantic = (cosine(session, embed(track)) + 1.0).coerceIn(0.0, 2.0) / 2.0
                track to 100.0 * (0.82 * modelScore + 0.18 * semantic)
            }
        }
    }

    private fun sessionVector(tracks: List<YunaTrack>): DoubleArray {
        val vector = DoubleArray(128)
        var decay = 1.0
        var total = 0.0
        for (track in tracks.asReversed()) {
            val item = embed(track)
            for (i in vector.indices) vector[i] += decay * item[i]
            total += decay
            decay *= 0.75
        }
        if (total > 0) for (i in vector.indices) vector[i] /= total
        return normalizeVector(vector)
    }

    private fun embed(track: YunaTrack): DoubleArray {
        val vector = DoubleArray(128)
        addText(vector, "title", track.title, 1.7, charNgrams = true)
        addText(vector, "artist", track.uploader.ifBlank { track.channel.ifBlank { track.artist } }, 1.25)
        addText(vector, "album", track.albumName, 0.55)
        addText(vector, "language", track.language, 1.0)
        addText(vector, "mood", track.mood, 0.9)
        vector[0] = if (track.durationSeconds > 0.0) 0.25 * kotlin.math.tanh(track.durationSeconds / 240.0) else 0.0
        return normalizeVector(vector)
    }

    private fun addText(vector: DoubleArray, field: String, text: String, weight: Double, charNgrams: Boolean = false) {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val tokens = tokensPattern.findAll(normalized).map { it.value }.toList()
        for (token in tokens) addFeature(vector, "$field:word:$token", weight)
        for ((left, right) in tokens.zipWithNext()) addFeature(vector, "$field:pair:$left $right", weight * 0.65)
        if (charNgrams) for (token in tokens.filter { it.length >= 3 }) for (size in 3..4) {
            for (start in 0..token.length - size) addFeature(vector, "title:char:${token.substring(start, start + size)}", weight * 0.12)
        }
    }

    private fun addFeature(vector: DoubleArray, feature: String, weight: Double) {
        val digest = MessageDigest.getInstance("SHA-256").digest(feature.toByteArray(Charsets.UTF_8))
        val firstEight = digest.copyOfRange(0, 8)
        val unsigned = ByteBuffer.wrap(firstEight).order(ByteOrder.LITTLE_ENDIAN).long
        val slot = 8 + java.lang.Long.remainderUnsigned(unsigned, 120L).toInt()
        vector[slot] += if (digest[7].toInt() and 0x80 != 0) weight else -weight
    }

    private fun normalizeVector(vector: DoubleArray): DoubleArray {
        val magnitude = norm(vector)
        return if (magnitude > 1e-6) DoubleArray(vector.size) { vector[it] / magnitude } else vector
    }

    private fun norm(vector: DoubleArray): Double = sqrt(vector.sumOf { it * it })
    private fun cosine(left: DoubleArray, right: DoubleArray): Double = left.indices.sumOf { left[it] * right[it] }
}

private fun normalizeArtist(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s*-\\s*topic\\b"), " ")
    .replace(Regex("\\bvevo\\b"), " ")
    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
    .trim().replace(Regex("\\s+"), " ")

private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\(\\[].*?[\\)\\]]"), " ")
    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
    .trim().replace(Regex("\\s+"), " ")

private fun String?.normalized(): String? = this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

private fun YunaTrack.primaryArtist(): String = uploader.ifBlank { channel.ifBlank { artist } }
