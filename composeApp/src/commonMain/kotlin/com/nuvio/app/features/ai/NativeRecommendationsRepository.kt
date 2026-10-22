package com.nuvio.app.features.ai

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Recommendation shelves produced by TMDB Discover filters; no AI/API key for Gemini is needed. */
enum class NativeRecommendationRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val mediaType: TmdbEntityMediaType,
    val filters: Map<String, String>,
) {
    val catalogKey: String get() = "native_rec_$key"
    Decade1970("decade_1970", "From the 1970s", "Popular movies released in the 1970s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1970-01-01", "primary_release_date.lte" to "1979-12-31")),
    Decade1980("decade_1980", "From the 1980s", "Popular movies released in the 1980s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1980-01-01", "primary_release_date.lte" to "1989-12-31")),
    Decade1990("decade_1990", "From the 1990s", "Popular movies released in the 1990s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "1990-01-01", "primary_release_date.lte" to "1999-12-31")),
    Decade2000("decade_2000", "From the 2000s", "Popular movies released in the 2000s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2000-01-01", "primary_release_date.lte" to "2009-12-31")),
    Decade2010("decade_2010", "From the 2010s", "Popular movies released in the 2010s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2010-01-01", "primary_release_date.lte" to "2019-12-31")),
    Decade2020("decade_2020", "From the 2020s", "Popular movies released in the 2020s", TmdbEntityMediaType.MOVIE, mapOf("primary_release_date.gte" to "2020-01-01", "primary_release_date.lte" to "2029-12-31")),
    QuickWatches("quick_watches", "Quick watches", "Movies under 100 minutes", TmdbEntityMediaType.MOVIE, mapOf("with_runtime.lte" to "100", "with_runtime.gte" to "1", "vote_count.gte" to "50")),
    FinishedSeries("finished_series", "Finished series to binge", "Completed TV series, sorted by popularity", TmdbEntityMediaType.TV, mapOf("with_status" to "3", "vote_count.gte" to "30")),
    ScifiMystery("genre_scifi_mystery", "Sci-fi mysteries", "Science fiction meets mystery", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "878,9648", "vote_count.gte" to "50")),
    CrimeThriller("genre_crime_thriller", "Crime & thrillers", "Tense crime stories and thrillers", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "80,53", "vote_count.gte" to "50")),
    FantasyAdventure("genre_fantasy_adventure", "Fantasy adventures", "Fantasy worlds and epic adventures", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "14,12", "vote_count.gte" to "50")),
    ComedyRomance("genre_comedy_romance", "Romantic comedies", "Lighthearted romance picks", TmdbEntityMediaType.MOVIE, mapOf("with_genres" to "35,10749", "vote_count.gte" to "50"));

    companion object {
        fun fromKey(key: String): NativeRecommendationRow? = values().firstOrNull { it.key == key }
    }
}

data class NativeRecommendationsUiState(
    val enabled: Boolean = false,
    val enabledRows: Set<NativeRecommendationRow> = NativeRecommendationRow.values().toSet(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

object NativeRecommendationsRepository {
    private const val KEY_ENABLED = "native_recommendations_enabled"
    private const val KEY_ROWS = "native_recommendations_rows"
    private const val KEY_GENRES = "native_taste_genres"
    private const val KEY_ERA = "native_taste_era"
    private const val KEY_LANGUAGE = "native_taste_language"
    private const val KEY_RATING = "native_taste_rating"
    private const val KEY_DISCOVERY = "native_taste_discovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NativeRecommendationsUiState())
    val uiState: StateFlow<NativeRecommendationsUiState> = _uiState.asStateFlow()
    private var loaded = false

    data class TastePreferences(
        val genreIds: Set<String> = emptySet(),
        val releaseEra: String = "any",
        val language: String = "any",
        val minimumRating: String = "0",
        val discovery: String = "popular",
    )

    fun tastePreferences(): TastePreferences = TastePreferences(
        genreIds = AiSettingsStorage.load(KEY_GENRES).orEmpty().split(",").filter { it.isNotBlank() }.toSet(),
        releaseEra = AiSettingsStorage.load(KEY_ERA) ?: "any",
        language = AiSettingsStorage.load(KEY_LANGUAGE) ?: "any",
        minimumRating = AiSettingsStorage.load(KEY_RATING) ?: "0",
        discovery = AiSettingsStorage.load(KEY_DISCOVERY) ?: "popular",
    )

    fun saveTastePreferences(value: TastePreferences) {
        AiSettingsStorage.save(KEY_GENRES, value.genreIds.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_ERA, value.releaseEra)
        AiSettingsStorage.save(KEY_LANGUAGE, value.language)
        AiSettingsStorage.save(KEY_RATING, value.minimumRating)
        AiSettingsStorage.save(KEY_DISCOVERY, value.discovery)
        refresh()
    }

    fun onProfileChanged() {
        loaded = false
        _uiState.value = NativeRecommendationsUiState()
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _uiState.value = NativeRecommendationsUiState(
            enabled = AiSettingsStorage.load(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
            enabledRows = AiSettingsStorage.load(KEY_ROWS)?.split(',')?.mapNotNull(NativeRecommendationRow::fromKey)?.toSet()
                ?: NativeRecommendationRow.values().toSet(),
        )
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        AiSettingsStorage.save(KEY_ENABLED, enabled.toString())
        _uiState.value = _uiState.value.copy(enabled = enabled)
        HomeCatalogSettingsRepository.refreshNativeRecommendationCatalogs()
        if (enabled) refresh()
    }

    fun setRowEnabled(row: NativeRecommendationRow, enabled: Boolean) {
        ensureLoaded()
        val rows = if (enabled) _uiState.value.enabledRows + row else _uiState.value.enabledRows - row
        AiSettingsStorage.save(KEY_ROWS, rows.joinToString(",") { it.key })
        _uiState.value = _uiState.value.copy(enabledRows = rows, sections = _uiState.value.sections.filter { section ->
            NativeRecommendationRow.fromKey(section.key.removePrefix("native_"))?.let { it in rows } == true
        })
        HomeCatalogSettingsRepository.refreshNativeRecommendationCatalogs()
        if (enabled && _uiState.value.enabled) refresh()
    }

    fun refresh() {
        ensureLoaded()
        val state = _uiState.value
        if (!state.enabled) return
        val settings = TmdbSettingsRepository.snapshot()
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) {
            _uiState.value = state.copy(errorMessage = "Configure a TMDB API key to use native recommendations.")
            return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val sections = state.enabledRows.toList().mapNotNull { row ->
                    val taste = tastePreferences()
                    val filters = row.filters.toMutableMap()
                    if (taste.genreIds.isNotEmpty() && row.mediaType == TmdbEntityMediaType.MOVIE) filters["with_genres"] = taste.genreIds.joinToString(",")
                    if (taste.minimumRating != "0") filters["vote_average.gte"] = taste.minimumRating
                    if (taste.releaseEra != "any" && row.mediaType == TmdbEntityMediaType.MOVIE) {
                        val bounds = when (taste.releaseEra) {
                            "classic" -> "1900-01-01" to "1979-12-31"
                            "80s90s" -> "1980-01-01" to "1999-12-31"
                            "2000s" -> "2000-01-01" to "2009-12-31"
                            "2010s" -> "2010-01-01" to "2019-12-31"
                            "2020s" -> "2020-01-01" to "2029-12-31"
                            else -> null
                        }
                        bounds?.let { filters["primary_release_date.gte"] = it.first; filters["primary_release_date.lte"] = it.second }
                    }
                    if (taste.language != "any") filters["with_original_language"] = taste.language
                    if (taste.discovery == "hidden") { filters["sort_by"] = "vote_average.desc"; filters["vote_count.gte"] = "20"; filters["vote_count.lte"] = "500" }
                    else if (taste.discovery == "popular") filters["sort_by"] = "popularity.desc"
                    val items = TmdbMetadataService.fetchNativeDiscoverRow(row.mediaType, filters, settings.language)
                    if (items.isEmpty()) null else HomeCatalogSection(
                        key = "native_${row.key}", title = row.title, subtitle = row.subtitle,
                        addonName = "TMDB", target = CatalogTarget.Addon("", row.mediaType.value, row.key),
                        items = items, availableItemCount = items.size,
                    )
                }
                _uiState.value = _uiState.value.copy(sections = sections, isLoading = false,
                    errorMessage = if (sections.isEmpty()) "No native recommendations found. Check your TMDB settings and try again." else null)
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message ?: "Native recommendations failed.")
            }
        }
    }
}
