package com.nuvio.app.features.ai

import com.nuvio.app.features.tmdb.NativeDiscoverCandidate
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.isEpisode
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * A watched title together with what the recommender knows about it.
 *
 * [weight] is how strongly the title should steer recommendations (recency x how much of it was
 * actually watched). [recommendations] is TMDB's "viewers also watched" list; it is only loaded for
 * the strongest seeds, so it is empty for the rest.
 */
internal data class TasteSeed(
    val title: TasteTitle,
    val keywordIds: List<Int>,
    val weight: Double = 1.0,
    val recommendations: List<NativeDiscoverCandidate> = emptyList(),
)

/** How much of a title the viewer watched: 0..1, plus distinct finished episodes for a series. */
internal data class TasteEngagement(val completion: Double, val completedEpisodes: Int)

/** A title the viewer explicitly hid with "Not interested", with the genres it carried. */
internal data class NotInterestedEntry(val id: String, val name: String, val genreIds: List<Int>) {
    companion object {
        private const val ENTRY_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = "|"

        /** Ids and normalized names never contain `;` or `|`, so a flat string round-trips safely. */
        fun encode(entries: List<NotInterestedEntry>): String = entries.joinToString(ENTRY_SEPARATOR) { entry ->
            listOf(entry.id, normalizeAiTitle(entry.name), entry.genreIds.joinToString(",")).joinToString(FIELD_SEPARATOR)
        }

        fun decode(raw: String?): List<NotInterestedEntry> = raw.orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { chunk ->
                val parts = chunk.split(FIELD_SEPARATOR)
                if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
                NotInterestedEntry(
                    id = parts[0],
                    name = parts[1],
                    genreIds = parts[2].split(",").mapNotNull { it.trim().toIntOrNull() },
                )
            }
    }
}

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
        excludedIds: Set<String> = emptySet(),
        boosts: Map<String, Double> = emptyMap(),
    ): List<NativeDiscoverCandidate> = candidates
        .asSequence()
        .filterNot { candidate -> candidate.preview.id in excludedIds }
        .filterNot { candidate -> candidate.genreIds.any { canonicalGenre(it) in disliked } }
        .filterNot { candidate -> normalizeAiTitle(candidate.preview.name) in excludedNames }
        .distinctBy { it.preview.id }
        .toList()
        .sortedByDescending { score(it, weights, liked) + (boosts[it.preview.id] ?: 0.0) }

    // ---- Engagement-weighted taste ----------------------------------------------------------

    private const val MIN_ENGAGEMENT = 0.35
    private const val EPISODE_DEPTH_STEP = 0.1
    private const val MAX_EPISODE_DEPTH_STEPS = 5

    /**
     * How strongly a watched title should count as a taste signal. A title watched to the end counts
     * fully; one abandoned at 20% counts about half. Series the viewer kept coming back to (several
     * finished episodes) count up to 1.5x.
     */
    fun engagementWeight(completion: Double, completedEpisodes: Int, isSeries: Boolean): Double {
        val base = MIN_ENGAGEMENT + (1.0 - MIN_ENGAGEMENT) * completion.coerceIn(0.0, 1.0)
        val depth = if (isSeries) (completedEpisodes - 1).coerceIn(0, MAX_EPISODE_DEPTH_STEPS) * EPISODE_DEPTH_STEP else 0.0
        return base + depth
    }

    // ---- "Because you watched" co-occurrence --------------------------------------------------

    private const val CO_OCCURRENCE_BOOST = 1.0

    /**
     * Rewards titles that TMDB recommends for several of the viewer's seeds. Each pool is a
     * (seed weight, that seed's recommendations in relevance order) pair; a title earns the seed
     * weight once per pool it appears in, a little less the further down the list it sits.
     */
    fun coOccurrenceBoosts(pools: List<Pair<Double, List<NativeDiscoverCandidate>>>): Map<String, Double> {
        val boosts = HashMap<String, Double>()
        pools.forEach { (weight, list) ->
            val unique = list.distinctBy { it.preview.id }
            unique.forEachIndexed { index, candidate ->
                val relevance = 1.0 - 0.5 * index / unique.size
                boosts[candidate.preview.id] = (boosts[candidate.preview.id] ?: 0.0) + weight * CO_OCCURRENCE_BOOST * relevance
            }
        }
        return boosts
    }

    // ---- Diversity (MMR re-ranking) ----------------------------------------------------------

    private const val DIVERSITY_LAMBDA = 0.7
    private const val FRANCHISE_MIN_LENGTH = 5

    private fun jaccard(a: Set<Int>, b: Set<Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / (a.size + b.size - shared)
    }

    private fun franchiseKey(name: String): String =
        normalizeAiTitle(name.trim().lowercase().removePrefix("the ").removePrefix("an ").removePrefix("a "))

    /** True when one title is a prefix of the other ("Avatar" / "Avatar The Way of Water"). */
    internal fun sameFranchise(a: String, b: String): Boolean {
        val keyA = franchiseKey(a)
        val keyB = franchiseKey(b)
        if (keyA.length < FRANCHISE_MIN_LENGTH || keyB.length < FRANCHISE_MIN_LENGTH) return false
        return keyA.startsWith(keyB) || keyB.startsWith(keyA)
    }

    /**
     * Re-orders the best [count] titles with Maximal Marginal Relevance so a row is not ten near
     * copies of one genre mix or one franchise. [ranked] must already be sorted best-first; titles
     * beyond [count] keep their original order behind the re-ordered head.
     */
    fun diversify(
        ranked: List<NativeDiscoverCandidate>,
        scoreOf: (NativeDiscoverCandidate) -> Double,
        count: Int,
        lambda: Double = DIVERSITY_LAMBDA,
    ): List<NativeDiscoverCandidate> {
        if (ranked.size <= 2 || count <= 1) return ranked
        val pool = ranked.take(minOf(ranked.size, count * 2))
        val raw = pool.map(scoreOf)
        val low = raw.min()
        val span = (raw.max() - low).takeIf { it > 1e-9 } ?: 1.0
        val relevance = raw.map { (it - low) / span }
        val genreSets = pool.map { candidate -> candidate.genreIds.map(::canonicalGenre).toSet() }

        val remaining = pool.indices.toMutableList()
        val picked = ArrayList<Int>()
        while (picked.size < count && remaining.isNotEmpty()) {
            var bestIndex = remaining.first()
            var bestValue = Double.NEGATIVE_INFINITY
            for (index in remaining) {
                val similarity = picked.maxOfOrNull { other ->
                    if (sameFranchise(pool[index].preview.name, pool[other].preview.name)) 1.0
                    else jaccard(genreSets[index], genreSets[other])
                } ?: 0.0
                val value = lambda * relevance[index] - (1.0 - lambda) * similarity
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            picked += bestIndex
            remaining.remove(bestIndex)
        }
        val pickedSet = picked.toSet()
        val head = picked.map { pool[it] }
        val tail = ranked.filterIndexed { index, _ -> index >= pool.size || index !in pickedSet }
        return head + tail
    }

    // ---- Cross-row de-duplication -------------------------------------------------------------

    /**
     * Stops the same title showing up on several rows. Rows are processed in on-screen order, so the
     * first row keeps its picks and later rows give up duplicates and backfill from their reserve.
     * A row that would drop below [minimum] titles keeps its original picks rather than ending up
     * with a nearly empty shelf.
     */
    fun <T> dedupeAcrossRows(
        rows: List<List<T>>,
        idOf: (T) -> String,
        limit: Int,
        minimum: Int,
    ): List<List<T>> {
        val used = HashSet<String>()
        return rows.map { row ->
            val fresh = row.filter { idOf(it) !in used }.take(limit)
            val chosen = if (fresh.size >= minOf(minimum, row.size)) fresh else row.take(limit)
            chosen.forEach { used += idOf(it) }
            chosen
        }
    }

    // ---- "Not interested" ----------------------------------------------------------------------

    private const val NOT_INTERESTED_PENALTY = 0.25
    private const val NOT_INTERESTED_FLOOR = -1.0

    /** Each hidden title nudges its genres down a little, never below [NOT_INTERESTED_FLOOR]. */
    fun applyNotInterested(weights: Map<Int, Double>, hiddenGenres: List<List<Int>>): Map<Int, Double> {
        if (hiddenGenres.isEmpty()) return weights
        val adjusted = HashMap(weights)
        hiddenGenres.forEach { genres ->
            genres.map(::canonicalGenre).distinct().forEach { id ->
                adjusted[id] = max(NOT_INTERESTED_FLOOR, (adjusted[id] ?: 0.0) - NOT_INTERESTED_PENALTY)
            }
        }
        return adjusted
    }

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
    /** Only the strongest seeds get a /recommendations request, to keep the request count down. */
    private const val MAX_RECOMMENDATION_SEEDS = 6
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

    /**
     * Completion per title id, from watch progress and the watched list. Titles that appear in
     * neither are treated as fully watched by the caller.
     */
    private fun engagementById(): Map<String, TasteEngagement> {
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        val completion = HashMap<String, Double>()
        val episodes = HashMap<String, MutableSet<String>>()

        WatchedRepository.uiState.value.items.forEach { item ->
            completion[item.id] = 1.0
            if (item.isEpisode) episodes.getOrPut(item.id) { HashSet() }.add("${item.season}:${item.episode}")
        }
        WatchProgressRepository.uiState.value.entries.forEach { entry ->
            val fraction = when {
                entry.isEffectivelyCompleted -> 1.0
                entry.durationMs > 0 -> (entry.lastPositionMs.toDouble() / entry.durationMs).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            completion[entry.parentMetaId] = max(completion[entry.parentMetaId] ?: 0.0, fraction)
            if (entry.isEffectivelyCompleted && entry.seasonNumber != null) {
                episodes.getOrPut(entry.parentMetaId) { HashSet() }.add("${entry.seasonNumber}:${entry.episodeNumber}")
            }
        }
        return completion.mapValues { (id, value) -> TasteEngagement(value, episodes[id]?.size ?: 0) }
    }

    /**
     * [recommendationPage] picks which page of TMDB's /recommendations list the strongest seeds
     * contribute, so a manual refresh can surface different titles.
     */
    suspend fun signals(language: String, force: Boolean, recommendationPage: Int = 1): NativeTasteSignals {
        val profile = AiTasteProfileBuilder.build()
        val engagement = engagementById()
        val now = Clock.System.now().toEpochMilliseconds()
        val fingerprint = profile.watched.joinToString("|") { title ->
            val seen = title.id?.let(engagement::get)
            "${title.id ?: title.name}:${((seen?.completion ?: 1.0) * 10).roundToInt()}:${seen?.completedEpisodes ?: 0}"
        } + "#p$recommendationPage"
        if (!force && fingerprint == cachedFingerprint && now - cachedAtMs < CACHE_TTL_MS) return cached

        val seedTitles = profile.watched.filter { !it.id.isNullOrBlank() }.take(MAX_SEEDS)
        val seedWeights = seedTitles.mapIndexed { index, title ->
            val seen = title.id?.let(engagement::get)
            RECENCY_DECAY.pow(index) * NativeRanking.engagementWeight(
                completion = seen?.completion ?: 1.0,
                completedEpisodes = seen?.completedEpisodes ?: 0,
                isSeries = title.type == "series",
            )
        }
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

        suspend fun loadRecommendations(title: TasteTitle): List<NativeDiscoverCandidate> = gate.withPermit {
            val id = title.id ?: return@withPermit emptyList()
            val page = runCatching {
                TmdbMetadataService.fetchNativeRecommendationCandidates(id, title.type, language, recommendationPage, force)
            }.getOrDefault(emptyList())
            if (page.isNotEmpty() || recommendationPage <= 1) page
            else runCatching {
                TmdbMetadataService.fetchNativeRecommendationCandidates(id, title.type, language, 1, force)
            }.getOrDefault(emptyList())
        }

        val recommendationSeeds = seedWeights.withIndex()
            .sortedByDescending { it.value }
            .take(MAX_RECOMMENDATION_SEEDS)
            .map { it.index }
            .toSet()

        val (liked, disliked, recommendations) = coroutineScope {
            val likedJobs = seedTitles.map { async { it to load(it) } }
            val dislikedJobs = abandoned.map { async { it to load(it) } }
            val recommendationJobs = recommendationSeeds.map { index -> async { index to loadRecommendations(seedTitles[index]) } }
            Triple(likedJobs.awaitAll(), dislikedJobs.awaitAll(), recommendationJobs.awaitAll().toMap())
        }

        val raw = HashMap<Int, Double>()
        val keywordRaw = HashMap<Int, Double>()
        val seeds = ArrayList<TasteSeed>()
        liked.forEachIndexed { index, (title, loaded) ->
            val weight = seedWeights[index]
            loaded.first.forEach { genre ->
                val id = NativeRanking.canonicalGenre(genre)
                raw[id] = (raw[id] ?: 0.0) + weight
            }
            loaded.second.forEach { keyword -> keywordRaw[keyword] = (keywordRaw[keyword] ?: 0.0) + weight }
            seeds += TasteSeed(title, loaded.second.take(6), weight, recommendations[index].orEmpty())
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
