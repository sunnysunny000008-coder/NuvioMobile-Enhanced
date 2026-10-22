package com.nuvio.app.features.ai

import com.nuvio.app.features.tmdb.NativeDiscoverCandidate
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Clock

/** A watched title together with the TMDB keyword ids that describe it. */
internal data class TasteSeed(val title: TasteTitle, val keywordIds: List<Int>)

/**
 * What the native recommender has learned about the viewer.
 *
 * [genreWeights] is keyed by canonical genre id (see [NativeRanking.canonicalGenre]). Positive
 * values come from what the viewer finished or kept; negative values come from titles they
 * opened and abandoned.
 */
internal data class NativeTasteSignals(
    val genreWeights: Map<Int, Double> = emptyMap(),
    val topKeywordIds: List<Int> = emptyList(),
    val seeds: List<TasteSeed> = emptyList(),
    val watchedNames: Set<String> = emptySet(),
) {
    val hasHistory: Boolean get() = seeds.isNotEmpty()

    companion object {
        val EMPTY = NativeTasteSignals()
    }
}

/** Pure scoring / mixing helpers. Kept free of I/O so they can be unit tested. */
internal object NativeRanking {
    /** TMDB uses different genre ids for TV; fold them onto the movie ids so one weight map works. */
    fun canonicalGenre(id: Int): Int = when (id) {
        10759 -> 28      // Action & Adventure
        10765 -> 878     // Sci-Fi & Fantasy
        10768 -> 10752   // War & Politics
        else -> id
    }

    /** Best-effort translation of a movie genre id to the TV genre list; null when TV has no match. */
    fun tvGenre(movieGenreId: Int): Int? = when (movieGenreId) {
        28, 12 -> 10759
        14, 878 -> 10765
        10752 -> 10768
        53 -> 9648
        10749 -> 18
        16, 35, 80, 99, 18, 10751, 9648, 37 -> movieGenreId
        else -> null
    }

    fun score(
        candidate: NativeDiscoverCandidate,
        weights: Map<Int, Double>,
        liked: Set<Int>,
    ): Double {
        val genres = candidate.genreIds.map(::canonicalGenre).distinct()
        val taste = genres.sumOf { (weights[it] ?: 0.0) + if (it in liked) 1.0 else 0.0 }
        val quality = candidate.voteAverage / 10.0
        val confidence = log10(candidate.voteCount.toDouble() + 1.0).coerceAtMost(5.0) / 10.0
        return taste * 2.0 + quality + confidence
    }

    fun rank(
        candidates: List<NativeDiscoverCandidate>,
        weights: Map<Int, Double>,
        liked: Set<Int>,
        disliked: Set<Int>,
        excludedNames: Set<String>,
    ): List<NativeDiscoverCandidate> = candidates
        .asSequence()
        .filterNot { candidate -> candidate.genreIds.any { canonicalGenre(it) in disliked } }
        .filterNot { candidate -> normalizeAiTitle(candidate.preview.name) in excludedNames }
        .distinctBy { it.preview.id }
        .toList()
        .sortedByDescending { score(it, weights, liked) }

    /** Splits [total] slots into (movies, series) for a series share of 0..100. */
    fun split(total: Int, seriesPercent: Int): Pair<Int, Int> {
        val series = (total * seriesPercent.coerceIn(0, 100) / 100.0).let { kotlin.math.round(it).toInt() }
        return (total - series) to series
    }

    /** Spreads two lists evenly instead of putting one media type first. */
    fun <T> interleave(first: List<T>, second: List<T>): List<T> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val out = ArrayList<T>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            val takeFirst = when {
                i >= first.size -> false
                j >= second.size -> true
                else -> i.toDouble() / first.size <= j.toDouble() / second.size
            }
            if (takeFirst) out += first[i++] else out += second[j++]
        }
        return out
    }

    /** Round-robins several lists (used to blend decades). */
    fun <T> roundRobin(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var index = 0
        while (true) {
            var added = false
            lists.forEach { list -> if (index < list.size) { out += list[index]; added = true } }
            if (!added) break
            index++
        }
        return out
    }

    /** Genres the viewer is most likely to enjoy, explicit likes first, never including dislikes. */
    fun topGenres(weights: Map<Int, Double>, liked: Set<Int>, disliked: Set<Int>, count: Int): List<Int> {
        val scores = HashMap<Int, Double>()
        weights.forEach { (id, w) -> if (w > 0.2) scores[id] = w }
        liked.forEach { id -> scores[id] = (scores[id] ?: 0.0) + 1.0 }
        return scores.entries
            .filter { it.key !in disliked }
            .sortedByDescending { it.value }
            .take(count)
            .map { it.key }
    }
}

internal object NativeTasteEngine {
    private const val MAX_SEEDS = 12
    private const val MAX_ABANDONED = 5
    private const val RECENCY_DECAY = 0.92
    private const val ABANDONED_WEIGHT = 0.5
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val ABANDONED_MIN_AGE_MS = 3 * 24 * 60 * 60 * 1000L

    private var cachedFingerprint: String? = null
    private var cachedAtMs: Long = 0L
    private var cached: NativeTasteSignals = NativeTasteSignals.EMPTY

    fun invalidate() {
        cachedFingerprint = null
        cached = NativeTasteSignals.EMPTY
    }

    suspend fun signals(language: String, force: Boolean): NativeTasteSignals {
        val profile = AiTasteProfileBuilder.build()
        val now = Clock.System.now().toEpochMilliseconds()
        val fingerprint = profile.watched.joinToString("|") { it.id ?: it.name }
        if (!force && fingerprint == cachedFingerprint && now - cachedAtMs < CACHE_TTL_MS) return cached

        val seedTitles = profile.watched.filter { !it.id.isNullOrBlank() }.take(MAX_SEEDS)
        val watchedIds = profile.watched.mapNotNull { it.id }.toSet()
        val abandoned = WatchProgressRepository.uiState.value.entries
            .asSequence()
            .filter { entry ->
                !entry.isCompleted && entry.durationMs > 0 &&
                    entry.lastPositionMs.toDouble() / entry.durationMs < 0.08 &&
                    entry.parentMetaId !in watchedIds &&
                    now - entry.lastUpdatedEpochMs > ABANDONED_MIN_AGE_MS
            }
            .distinctBy { it.parentMetaId }
            .take(MAX_ABANDONED)
            .map { TasteTitle(it.title, null, if (it.parentMetaType.equals("movie", true)) "movie" else "series", it.parentMetaId) }
            .toList()

        val gate = Semaphore(4)
        suspend fun load(title: TasteTitle): Pair<List<Int>, List<Int>> = gate.withPermit {
            val id = title.id ?: return@withPermit emptyList<Int>() to emptyList()
            val genres = runCatching { TmdbMetadataService.fetchNativeGenres(id, title.type, language, force) }
                .getOrDefault(emptyList()).map { it.id }
            val keywords = runCatching { TmdbMetadataService.fetchNativeKeywords(id, title.type, language, force) }
                .getOrDefault(emptyList()).map { it.id }
            genres to keywords
        }

        val (liked, disliked) = coroutineScope {
            val likedJobs = seedTitles.map { async { it to load(it) } }
            val dislikedJobs = abandoned.map { async { it to load(it) } }
            likedJobs.awaitAll() to dislikedJobs.awaitAll()
        }

        val raw = HashMap<Int, Double>()
        val keywordRaw = HashMap<Int, Double>()
        val seeds = ArrayList<TasteSeed>()
        liked.forEachIndexed { index, (title, loaded) ->
            val weight = RECENCY_DECAY.pow(index)
            loaded.first.forEach { genre ->
                val id = NativeRanking.canonicalGenre(genre)
                raw[id] = (raw[id] ?: 0.0) + weight
            }
            loaded.second.forEach { keyword -> keywordRaw[keyword] = (keywordRaw[keyword] ?: 0.0) + weight }
            seeds += TasteSeed(title, loaded.second.take(6))
        }
        val peak = raw.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val weights = HashMap<Int, Double>()
        raw.forEach { (id, value) -> weights[id] = value / peak }
        disliked.forEach { (_, loaded) ->
            loaded.first.forEach { genre ->
                val id = NativeRanking.canonicalGenre(genre)
                weights[id] = (weights[id] ?: 0.0) - ABANDONED_WEIGHT
            }
        }

        val result = NativeTasteSignals(
            genreWeights = weights,
            topKeywordIds = keywordRaw.entries.sortedByDescending { it.value }.take(8).map { it.key },
            seeds = seeds,
            watchedNames = profile.watched.mapTo(HashSet()) { normalizeAiTitle(it.name) },
        )
        cached = result
        cachedFingerprint = fingerprint
        cachedAtMs = now
        return result
    }
}
