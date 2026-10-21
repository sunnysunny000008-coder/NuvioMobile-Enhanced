package com.nuvio.app.features.ai

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Recommendation shelves produced by TMDB; no Gemini API key or AI calls are required. */
enum class NativeRecommendationRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val mediaType: TmdbEntityMediaType,
    val filters: Map<String, String> = emptyMap(),
) {
    ForYou("for_you", "For You", "Personalized from your Nuvio viewing history and saved genres", TmdbEntityMediaType.MOVIE),
    BecauseWatched("because_watched", "Because You Watched", "Find more titles related to your recent watches", TmdbEntityMediaType.MOVIE),
    PersonalizedGenreCombinations("personalized_genres", "Your Genre Combinations", "Genre pairings learned from your saved titles", TmdbEntityMediaType.MOVIE),
    KeywordBased("keyword_based", "Based on Your Interests", "Themes and keywords from titles you watch", TmdbEntityMediaType.MOVIE),
    Decade1970("decade_1970", "From the 1970s", "Popular movies released in the 1970s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1970-01-01", "primary_release_date.lte" to "1979-12-31")),
    Decade1980("decade_1980", "From the 1980s", "Popular movies released in the 1980s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1980-01-01", "primary_release_date.lte" to "1989-12-31")),
    Decade1990("decade_1990", "From the 1990s", "Popular movies released in the 1990s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1990-01-01", "primary_release_date.lte" to "1999-12-31")),
    Decade2000("decade_2000", "From the 2000s", "Popular movies released in the 2000s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2000-01-01", "primary_release_date.lte" to "2009-12-31")),
    Decade2010("decade_2010", "From the 2010s", "Popular movies released in the 2010s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2010-01-01", "primary_release_date.lte" to "2019-12-31")),
    Decade2020("decade_2020", "From the 2020s", "Popular movies released in the 2020s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2020-01-01", "primary_release_date.lte" to "2029-12-31")),
    QuickWatches("quick_watches", "Quick Watches", "Movies under 100 minutes", TmdbEntityMediaType.MOVIE, mapOf("with_runtime.lte" to "100", "with_runtime.gte" to "1", "vote_count.gte" to "50")),
    FinishedSeries("finished_series", "Finished Series to Binge", "Completed TV series, sorted by popularity", TmdbEntityMediaType.TV, mapOf("with_status" to "3", "vote_count.gte" to "30")),
    ScifiMystery("genre_scifi_mystery", "Sci-Fi Mysteries", "Science fiction meets mystery", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "878,9648", "vote_count.gte" to "50")),
    CrimeThriller("genre_crime_thriller", "Crime & Thrillers", "Tense crime stories and thrillers", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "80,53", "vote_count.gte" to "50")),
    FantasyAdventure("genre_fantasy_adventure", "Fantasy Adventures", "Fantasy worlds and epic adventures", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "14,12", "vote_count.gte" to "50")),
    ComedyRomance("genre_comedy_romance", "Romantic Comedies", "Lighthearted romance picks", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "35,10749", "vote_count.gte" to "50"));

    companion object {
        fun fromKey(key: String): NativeRecommendationRow? = values().firstOrNull { it.key == key }
    }
}

data class NativeRecommendationsUiState(
    val enabled: Boolean = false,
    val enabledRows: Set<NativeRecommendationRow> = NativeRecommendationRow.values().toSet(),
    /** Target movie share for mixed movie/TV shelves; the remaining share is TV series. */
    val movieRatioPercent: Int = 50,
    val sections: List<HomeCatalogSection> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastRefreshedAtMs: Long? = null,
)

object NativeRecommendationsRepository {
    private const val KEY_ENABLED = "native_recommendations_enabled"
    private const val KEY_ROWS = "native_recommendations_rows"
    private const val KEY_ROW_VERSION = "native_recommendations_rows_version"
    private const val KEY_MOVIE_RATIO = "native_recommendations_movie_ratio"
    private const val REFRESH_TTL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_WATCHED_SEEDS = 4
    private const val MAX_KEYWORD_ROWS = 2
    private const val MAX_GENRE_COMBINATIONS = 3
    private const val ROW_LIMIT = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NativeRecommendationsUiState())
    val uiState: StateFlow<NativeRecommendationsUiState> = _uiState.asStateFlow()
    private var loaded = false
    private var refreshJob: Job? = null
    private var lastTasteSignature: String? = null

    fun onProfileChanged() {
        refreshJob?.cancel()
        refreshJob = null
        loaded = false
        lastTasteSignature = null
        _uiState.value = NativeRecommendationsUiState()
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val storedRows = AiSettingsStorage.load(KEY_ROWS)
        val parsedRows = storedRows?.split(',')?.mapNotNull(NativeRecommendationRow::fromKey)?.toSet()
            ?: NativeRecommendationRow.values().toSet()
        // One-time migration: enable the new personalized shelves for existing installs.
        val rowVersion = AiSettingsStorage.load(KEY_ROW_VERSION)?.toIntOrNull() ?: 0
        val enabledRows = if (rowVersion < 2) {
            (parsedRows + setOf(
                NativeRecommendationRow.ForYou,
                NativeRecommendationRow.BecauseWatched,
                NativeRecommendationRow.PersonalizedGenreCombinations,
                NativeRecommendationRow.KeywordBased,
            )).also { migrated ->
                AiSettingsStorage.save(KEY_ROWS, migrated.joinToString(",") { it.key })
                AiSettingsStorage.save(KEY_ROW_VERSION, "2")
            }
        } else parsedRows
        _uiState.value = NativeRecommendationsUiState(
            enabled = AiSettingsStorage.load(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
            enabledRows = enabledRows,
            movieRatioPercent = AiSettingsStorage.load(KEY_MOVIE_RATIO)?.toIntOrNull()
                ?.takeIf { it in setOf(50, 60, 70) } ?: 50,
        )
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        AiSettingsStorage.save(KEY_ENABLED, enabled.toString())
        _uiState.value = _uiState.value.copy(enabled = enabled)
        if (enabled) refresh(force = true)
    }

    fun setMovieRatioPercent(percent: Int) {
        ensureLoaded()
        val normalized = percent.takeIf { it in setOf(50, 60, 70) } ?: 50
        if (_uiState.value.movieRatioPercent == normalized) return
        AiSettingsStorage.save(KEY_MOVIE_RATIO, normalized.toString())
        _uiState.value = _uiState.value.copy(movieRatioPercent = normalized)
        if (_uiState.value.enabled) refresh(force = true)
    }

    fun setRowEnabled(row: NativeRecommendationRow, enabled: Boolean) {
        ensureLoaded()
        val rows = if (enabled) _uiState.value.enabledRows + row else _uiState.value.enabledRows - row
        AiSettingsStorage.save(KEY_ROWS, rows.joinToString(",") { it.key })
        _uiState.value = _uiState.value.copy(
            enabledRows = rows,
            sections = _uiState.value.sections.filterNot { section ->
                section.key.startsWith("native_${row.key}") && !enabled
            },
        )
        if (enabled && _uiState.value.enabled) refresh(force = true)
    }

    /** Called when Home opens or taste signals change. Cached rows are reused for up to six hours. */
    fun refresh(force: Boolean = false) {
        ensureLoaded()
        val state = _uiState.value
        if (!state.enabled) return
        val settings = TmdbSettingsRepository.snapshot()
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) {
            _uiState.value = state.copy(errorMessage = "Configure a TMDB API key to use native recommendations.")
            return
        }

        refreshJob?.cancel()
        refreshJob = scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val profile = withContext(Dispatchers.Default) { AiTasteProfileBuilder.build() }
                val signature = buildString {
                    profile.watched.forEach { append(it.id.orEmpty()).append(':').append(it.name).append('|') }
                    append('#')
                    profile.topGenres.forEach { append(it.lowercase()).append('|') }
                    append('#')
                    profile.saved.forEach { append(it.name.lowercase()).append('|') }
                }
                val now = Clock.System.now().toEpochMilliseconds()
                val current = _uiState.value
                if (!force && signature == lastTasteSignature && current.sections.isNotEmpty() &&
                    current.lastRefreshedAtMs?.let { now - it < REFRESH_TTL_MS } == true
                ) {
                    _uiState.value = current.copy(isLoading = false)
                    return@launch
                }

                val sections = buildSections(
                    current.enabledRows,
                    profile,
                    settings.language,
                    forceRefresh = force,
                    movieRatioPercent = current.movieRatioPercent,
                )
                lastTasteSignature = signature
                _uiState.value = _uiState.value.copy(
                    sections = sections,
                    isLoading = false,
                    errorMessage = if (sections.isEmpty()) "No recommendations found yet. Watch or save a few titles, or check your TMDB settings." else null,
                    lastRefreshedAtMs = now,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Native recommendations failed.",
                )
            }
        }
    }

    private suspend fun buildSections(
        enabledRows: Set<NativeRecommendationRow>,
        profile: TasteProfile,
        language: String,
        forceRefresh: Boolean,
        movieRatioPercent: Int,
    ): List<HomeCatalogSection> {
        val excluded = profile.excludedNames
        val seen = linkedSetOf<String>()
        val sections = mutableListOf<HomeCatalogSection>()

        // Learn genres from actual watched titles first, then supplement with genres on saved titles.
        val genreSignals = linkedMapOf<Int, Pair<String, Int>>()
        profile.watched.take(12).forEach { seed ->
            TmdbMetadataService.fetchNativeGenres(seed.id.orEmpty(), seed.type, language, forceRefresh)
                .forEach { genre ->
                    val old = genreSignals[genre.id]
                    genreSignals[genre.id] = genre.name to ((old?.second ?: 0) + 2)
                }
        }
        profile.topGenres.forEach { name ->
            val id = genreId(name) ?: return@forEach
            val old = genreSignals[id]
            genreSignals[id] = name to ((old?.second ?: 0) + 1)
        }
        val tasteGenres = genreSignals.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Pair<String, Int>>> { it.value.second }.thenBy { it.value.first })
            .map { it.value.first to it.key }

        suspend fun addSection(
            row: NativeRecommendationRow,
            suffix: String = "",
            title: String = row.title,
            subtitle: String = row.subtitle,
            items: List<MetaPreview>,
        ) {
            val filtered = items.asSequence()
                .filter { it.name.isNotBlank() }
                .filterNot { normalizeAiTitle(it.name) in excluded }
                .filter { seen.add("${it.type}:${it.id}") }
                .distinctBy { "${it.type}:${it.id}" }
                .take(ROW_LIMIT)
                .toList()
            if (filtered.isEmpty()) return
            sections += HomeCatalogSection(
                key = "native_${row.key}${suffix.takeIf { it.isNotBlank() }?.let { "_$it" }.orEmpty()}",
                title = title,
                subtitle = subtitle,
                addonName = "TMDB",
                target = CatalogTarget.Addon("", row.mediaType.value, row.key),
                items = filtered,
                availableItemCount = filtered.size,
            )
        }

        for (row in NativeRecommendationRow.values().toList().shuffled()) {
            if (row !in enabledRows) continue
            when (row) {
                NativeRecommendationRow.ForYou -> {
                    val genreId = tasteGenres.firstOrNull()?.second ?: profile.topGenres.firstNotNullOfOrNull(::genreId)
                    val candidates = mutableListOf<MetaPreview>()
                    // Related titles are the strongest native signal; Discover adds breadth.
                    profile.watched.take(2).forEach { seed ->
                        candidates += TmdbMetadataService.fetchNativeRelated(seed.id.orEmpty(), seed.type, language, forceRefresh)
                    }
                    if (genreId != null) {
                        candidates += fetchBothMedia(mapOf("with_genres" to genreId.toString(), "vote_count.gte" to "30"), language, forceRefresh, movieRatioPercent)
                    } else if (candidates.isEmpty()) {
                        // A new profile with no history still gets a useful starting shelf.
                        candidates += fetchBothMedia(mapOf("vote_count.gte" to "100", "vote_average.gte" to "6.5"), language, forceRefresh, movieRatioPercent)
                    }
                    addSection(row, items = candidates)
                }
                NativeRecommendationRow.BecauseWatched -> {
                    profile.watched.take(MAX_WATCHED_SEEDS).forEach { seed ->
                        val related = TmdbMetadataService.fetchNativeRelated(seed.id.orEmpty(), seed.type, language, forceRefresh)
                        addSection(
                            row = row,
                            suffix = normalizeAiTitle(seed.name).take(28).ifBlank { "recent" },
                            title = "Because You Watched ${seed.name}",
                            subtitle = "More like ${seed.name}",
                            items = related.shuffled(),
                        )
                    }
                }
                NativeRecommendationRow.PersonalizedGenreCombinations -> {
                    val genres = (tasteGenres + profile.topGenres.mapNotNull { name -> genreId(name)?.let { name to it } })
                        .distinctBy { it.second }.take(4)
                    val combinations = if (genres.size >= 2) {
                        genres.indices.flatMap { first -> ((first + 1) until genres.size).map { second -> genres[first] to genres[second] } }
                            .shuffled()
                            .take(MAX_GENRE_COMBINATIONS)
                    } else emptyList()
                    combinations.forEach { (first, second) ->
                        val filters = mapOf("with_genres" to "${first.second},${second.second}", "vote_count.gte" to "20")
                        addSection(
                            row = row,
                            suffix = "${first.second}_${second.second}",
                            title = "${first.first} + ${second.first}",
                            subtitle = "A combination based on your saved titles",
                            items = fetchBothMedia(filters, language, forceRefresh, movieRatioPercent),
                        )
                    }
                }
                NativeRecommendationRow.KeywordBased -> {
                    val keywordCounts = linkedMapOf<Int, Pair<String, Int>>()
                    profile.watched.take(8).forEach { seed ->
                        TmdbMetadataService.fetchNativeKeywords(seed.id.orEmpty(), seed.type, language, forceRefresh).forEach { keyword ->
                            val previous = keywordCounts[keyword.id]
                            keywordCounts[keyword.id] = keyword.name to ((previous?.second ?: 0) + 1)
                        }
                    }
                    val rankedKeywords = keywordCounts.entries
                        .sortedWith(compareByDescending<Map.Entry<Int, Pair<String, Int>>> { it.value.second }.thenBy { it.value.first })
                        .filter { it.value.second >= 2 }
                        .ifEmpty { keywordCounts.entries.sortedByDescending { it.value.second } }
                        .take(MAX_KEYWORD_ROWS)
                    rankedKeywords.forEach { (id, pair) ->
                        addSection(
                            row = row,
                            suffix = id.toString(),
                            title = "Because You Like ${pair.first}",
                            subtitle = "A theme found in your viewing history",
                            items = fetchBothMedia(mapOf("with_keywords" to id.toString(), "vote_count.gte" to "20"), language, forceRefresh, movieRatioPercent),
                        )
                    }
                }
                else -> {
                    val items = TmdbMetadataService.fetchNativeDiscoverRow(row.mediaType, row.filters, language, forceRefresh = forceRefresh)
                        .shuffled()
                    addSection(row, items = items)
                }
            }
        }
        return sections
    }

    private suspend fun fetchBothMedia(
        filters: Map<String, String>,
        language: String,
        forceRefresh: Boolean,
        movieRatioPercent: Int = _uiState.value.movieRatioPercent,
    ): List<MetaPreview> {
        val movies = TmdbMetadataService.fetchNativeDiscoverRow(TmdbEntityMediaType.MOVIE, filters, language, limit = ROW_LIMIT, forceRefresh = forceRefresh)
        val shows = TmdbMetadataService.fetchNativeDiscoverRow(TmdbEntityMediaType.TV, filters, language, limit = ROW_LIMIT, forceRefresh = forceRefresh)
        // Shuffle both pools and interleave them according to the user's preferred ratio.
        // If one pool runs short, the other fills the remaining positions.
        val moviePool = movies.shuffled().toMutableList()
        val showPool = shows.shuffled().toMutableList()
        val mixed = mutableListOf<MetaPreview>()
        val movieShare = movieRatioPercent.coerceIn(0, 100) / 100.0
        var moviesTaken = 0
        var showsTaken = 0
        while (moviePool.isNotEmpty() || showPool.isNotEmpty()) {
            val total = moviesTaken + showsTaken
            val shouldTakeMovie = moviePool.isNotEmpty() && (
                showPool.isEmpty() || moviesTaken.toDouble() < movieShare * (total + 1)
                )
            if (shouldTakeMovie) {
                mixed += moviePool.removeAt(0)
                moviesTaken++
            } else if (showPool.isNotEmpty()) {
                mixed += showPool.removeAt(0)
                showsTaken++
            } else if (moviePool.isNotEmpty()) {
                mixed += moviePool.removeAt(0)
                moviesTaken++
            }
        }
        return mixed.distinctBy { "${it.type}:${it.id}" }
    }

    private fun genreId(name: String): Int? = when (name.trim().lowercase().replace('-', ' ').replace('_', ' ')) {
        "action" -> 28
        "adventure" -> 12
        "animation" -> 16
        "comedy" -> 35
        "crime" -> 80
        "documentary" -> 99
        "drama" -> 18
        "family" -> 10751
        "fantasy" -> 14
        "history" -> 36
        "horror" -> 27
        "music" -> 10402
        "mystery" -> 9648
        "romance" -> 10749
        "science fiction", "sci fi", "scifi" -> 878
        "tv movie", "tv movies" -> 10770
        "thriller" -> 53
        "war" -> 10752
        "western" -> 37
        else -> null
    }
}
