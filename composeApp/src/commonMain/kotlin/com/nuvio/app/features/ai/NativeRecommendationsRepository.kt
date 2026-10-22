package com.nuvio.app.features.ai

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.NativeDiscoverCandidate
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlin.time.Clock

/** How a native row picks its titles. */
enum class NativeRowKind { ForYou, BecauseWatched, HiddenGems, Fresh, Decades, QuickWatches, FinishedSeries, Genre }

/** Recommendation shelves produced by TMDB Discover; no AI/API key for Gemini is needed. */
enum class NativeRecommendationRow(
    val key: String,
    val title: String,
    val subtitle: String,
    internal val kind: NativeRowKind,
    /** When set the row only ever contains this media type, whatever the movie/series ratio says. */
    internal val mediaOnly: TmdbEntityMediaType? = null,
    internal val movieGenres: List<Int> = emptyList(),
    internal val tvGenres: List<Int> = emptyList(),
) {
    ForYou("for_you", "Picked for you", "Built from the genres you like and what you actually watch", NativeRowKind.ForYou),
    BecauseWatched("because_watched", "Because of your watch history", "Titles that share themes with what you finished", NativeRowKind.BecauseWatched),
    HiddenGems("hidden_gems", "Hidden gems for you", "Well rated, lesser known, in your favourite genres", NativeRowKind.HiddenGems),
    Fresh("fresh", "New & fresh", "Recent releases in your favourite genres", NativeRowKind.Fresh),
    Decades("decades", "Through the decades", "One row mixing the decades you pick", NativeRowKind.Decades),
    QuickWatches("quick_watches", "Quick watches", "Movies from 60 to 100 minutes", NativeRowKind.QuickWatches, mediaOnly = TmdbEntityMediaType.MOVIE),
    FinishedSeries("finished_series", "Finished series to binge", "Completed series with nothing left to wait for", NativeRowKind.FinishedSeries, mediaOnly = TmdbEntityMediaType.TV),
    ScifiMystery("genre_scifi_mystery", "Sci-fi mysteries", "Science fiction meets mystery", NativeRowKind.Genre, movieGenres = listOf(878, 9648), tvGenres = listOf(10765, 9648)),
    CrimeThriller("genre_crime_thriller", "Crime & thrillers", "Tense crime stories and thrillers", NativeRowKind.Genre, movieGenres = listOf(80, 53), tvGenres = listOf(80, 9648)),
    FantasyAdventure("genre_fantasy_adventure", "Fantasy adventures", "Fantasy worlds and epic adventures", NativeRowKind.Genre, movieGenres = listOf(14, 12), tvGenres = listOf(10765, 10759)),
    ComedyRomance("genre_comedy_romance", "Romantic comedies", "Lighthearted romance picks", NativeRowKind.Genre, movieGenres = listOf(35, 10749), tvGenres = listOf(35, 18));

    val catalogKey: String get() = "native_rec_$key"

    companion object {
        fun fromKey(key: String): NativeRecommendationRow? = values().firstOrNull { it.key == key }

        /** Rows that existed before the single "decades" row; their saved toggles map onto it. */
        internal fun fromStoredKey(key: String): NativeRecommendationRow? =
            if (key.startsWith("decade_")) Decades else fromKey(key)
    }
}

/** Which titles a TMDB row may hold. ALL is the home mix; the others back the built-in categories. */
enum class NativeScope(
    /** Share of series in the row: 0 = movies only, 100 = series only. Null keeps the viewer's mix. */
    val seriesPercent: Int?,
) { ALL(null), MOVIES(0), TV(100), ANIME(50) }

/** Everything the viewer can tune about native recommendations. Saved per profile. */
data class NativeRecommendationPreferences(
    val likedGenreIds: Set<Int> = emptySet(),
    val dislikedGenreIds: Set<Int> = emptySet(),
    /** classic | 80s90s | 2000s | 2010s | 2020s. Empty means any era. */
    val releaseEras: Set<String> = emptySet(),
    /** Original-language codes such as "ko" or "en". Empty means any language. */
    val languages: Set<String> = emptySet(),
    val minimumRating: String = "0",
    /** Any of popular | balanced | hidden | acclaimed | fresh. Empty behaves like popular. */
    val discoveries: Set<String> = setOf("popular"),
    /** Share of series in mixed rows: 0, 25, 50, 75 or 100. */
    val seriesPercent: Int = 25,
    val hideMature: Boolean = true,
    val hideWatched: Boolean = true,
    val learnFromHistory: Boolean = true,
    /** Decade start years used by the "Through the decades" row, e.g. 1980. */
    val decades: Set<Int> = setOf(1980, 1990, 2000, 2010),
    /** Shuffles the titles inside each TMDB row. The order is picked once per app start. */
    val shuffleTitles: Boolean = false,
)

val NATIVE_DECADE_OPTIONS: List<Int> = listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020)

data class NativeRecommendationsUiState(
    val enabled: Boolean = false,
    val enabledRows: Set<NativeRecommendationRow> = NativeRecommendationRow.values().toSet(),
    val prefs: NativeRecommendationPreferences = NativeRecommendationPreferences(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedEpochMs: Long? = null,
    /** True once watch history gave the recommender real taste signals. */
    val learnedFromHistory: Boolean = false,
    /** Titles the viewer hid with "Not interested". */
    val notInterestedCount: Int = 0,
    /** Rows for the built-in movies, TV and anime categories; the home mix lives in [sections]. */
    val scopedSections: Map<NativeScope, List<HomeCatalogSection>> = emptyMap(),
)

object NativeRecommendationsRepository {
    private const val KEY_ENABLED = "native_recommendations_enabled"
    private const val KEY_ROWS = "native_recommendations_rows"
    private const val KEY_GENRES = "native_taste_genres"
    private const val KEY_DISLIKED = "native_taste_disliked_genres"
    private const val KEY_ERA = "native_taste_era"
    private const val KEY_LANGUAGE = "native_taste_language"
    private const val KEY_RATING = "native_taste_rating"
    private const val KEY_DISCOVERY = "native_taste_discovery"
    private const val KEY_SERIES_PERCENT = "native_series_percent"
    private const val KEY_HIDE_MATURE = "native_hide_mature"
    private const val KEY_HIDE_WATCHED = "native_hide_watched"
    private const val KEY_LEARN = "native_learn_history"
    private const val KEY_DECADES = "native_decades"
    private const val KEY_SHUFFLE_TITLES = "native_shuffle_titles"

    /** Fixed for this app run, so the shuffled order holds until the next app start. */
    private val launchShuffleSeed: Long = Random.nextLong()
    private const val KEY_NOT_INTERESTED = "native_not_interested"

    private const val ANIMATION_GENRE_ID = 16
    private const val ROW_LIMIT = 20
    /** Extra titles each row fetches so cross-row de-duplication can backfill without coming up short. */
    private const val ROW_RESERVE = 8
    private const val MIN_ROW_ITEMS = 6
    private const val MAX_NOT_INTERESTED = 200
    private const val MAX_VARIANTS = 6
    private const val REFRESH_DEBOUNCE_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NativeRecommendationsUiState())
    val uiState: StateFlow<NativeRecommendationsUiState> = _uiState.asStateFlow()
    private var loaded = false
    private var refreshJob: Job? = null
    private var debounceJob: Job? = null

    /** Each manual refresh moves to the next TMDB result page, so the rows actually change. */
    private var refreshNonce = 0

    /** Titles hidden with "Not interested". Replaced, never mutated, so refresh can read it safely. */
    @Volatile
    private var notInterested: List<NotInterestedEntry> = emptyList()

    /** TMDB genre ids of the titles currently on screen, so hiding one can lower its genres. */
    @Volatile
    private var shownGenres: Map<String, List<Int>> = emptyMap()

    fun onProfileChanged() {
        refreshJob?.cancel()
        debounceJob?.cancel()
        NativeTasteEngine.invalidate()
        loaded = false
        notInterested = emptyList()
        shownGenres = emptyMap()
        _uiState.value = NativeRecommendationsUiState()
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val storedRows = AiSettingsStorage.load(KEY_ROWS)
            ?.split(',')?.mapNotNull { NativeRecommendationRow.fromStoredKey(it.trim()) }?.toSet()
        notInterested = NotInterestedEntry.decode(AiSettingsStorage.load(KEY_NOT_INTERESTED)).takeLast(MAX_NOT_INTERESTED)
        _uiState.value = NativeRecommendationsUiState(
            enabled = AiSettingsStorage.load(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
            enabledRows = storedRows ?: NativeRecommendationRow.values().toSet(),
            prefs = loadPreferences(),
            notInterestedCount = notInterested.size,
        )
    }

    // ---------------------------------------------------------------------------------------
    // "Not interested"
    // ---------------------------------------------------------------------------------------

    /** True when [previewId] is currently shown on one of the native rows. */
    fun isRecommended(previewId: String): Boolean {
        val state = _uiState.value
        return state.enabled && state.sections.any { section -> section.items.any { it.id == previewId } }
    }

    /**
     * Hides [preview] from every native row for good and lowers the genres it carried. The card
     * disappears right away; the genre nudge shows up the next time the rows are rebuilt.
     */
    fun markNotInterested(preview: MetaPreview) {
        ensureLoaded()
        val entry = NotInterestedEntry(preview.id, preview.name, shownGenres[preview.id].orEmpty())
        notInterested = (notInterested.filterNot { it.id == entry.id } + entry).takeLast(MAX_NOT_INTERESTED)
        AiSettingsStorage.save(KEY_NOT_INTERESTED, NotInterestedEntry.encode(notInterested))
        _uiState.value = _uiState.value.copy(
            sections = _uiState.value.sections.map { section ->
                val remaining = section.items.filterNot { it.id == preview.id }
                section.copy(items = remaining, availableItemCount = remaining.size)
            },
            scopedSections = _uiState.value.scopedSections.mapValues { (_, scoped) ->
                scoped.map { section ->
                    val remaining = section.items.filterNot { it.id == preview.id }
                    section.copy(items = remaining, availableItemCount = remaining.size)
                }
            },
            notInterestedCount = notInterested.size,
        )
    }

    /** Brings every hidden title back and rebuilds the rows. */
    fun clearNotInterested() {
        ensureLoaded()
        if (notInterested.isEmpty()) return
        notInterested = emptyList()
        AiSettingsStorage.save(KEY_NOT_INTERESTED, "")
        _uiState.value = _uiState.value.copy(notInterestedCount = 0)
        refresh()
    }

    private fun loadPreferences(): NativeRecommendationPreferences {
        val defaults = NativeRecommendationPreferences()
        fun ints(key: String) = AiSettingsStorage.load(key).orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return NativeRecommendationPreferences(
            likedGenreIds = ints(KEY_GENRES),
            dislikedGenreIds = ints(KEY_DISLIKED),
            releaseEras = AiSettingsStorage.load(KEY_ERA)?.let(::words) ?: defaults.releaseEras,
            languages = AiSettingsStorage.load(KEY_LANGUAGE)?.let(::words) ?: defaults.languages,
            minimumRating = AiSettingsStorage.load(KEY_RATING) ?: defaults.minimumRating,
            discoveries = AiSettingsStorage.load(KEY_DISCOVERY)?.let(::words)?.takeIf { it.isNotEmpty() } ?: defaults.discoveries,
            seriesPercent = AiSettingsStorage.load(KEY_SERIES_PERCENT)?.toIntOrNull()?.coerceIn(0, 100) ?: defaults.seriesPercent,
            hideMature = AiSettingsStorage.load(KEY_HIDE_MATURE)?.toBooleanStrictOrNull() ?: defaults.hideMature,
            hideWatched = AiSettingsStorage.load(KEY_HIDE_WATCHED)?.toBooleanStrictOrNull() ?: defaults.hideWatched,
            learnFromHistory = AiSettingsStorage.load(KEY_LEARN)?.toBooleanStrictOrNull() ?: defaults.learnFromHistory,
            decades = AiSettingsStorage.load(KEY_DECADES)?.let { stored -> ints(KEY_DECADES).takeIf { stored.isNotBlank() } } ?: defaults.decades,
            shuffleTitles = AiSettingsStorage.load(KEY_SHUFFLE_TITLES)?.toBooleanStrictOrNull() ?: defaults.shuffleTitles,
        )
    }

    /**
     * Keeps the recommender's order unless the viewer asked for shuffled titles. The shuffle uses
     * a seed fixed at app start, so each row keeps one order until the app is restarted.
     */
    private fun <T> orderTitles(row: NativeRecommendationRow, items: List<T>, shuffle: Boolean): List<T> =
        if (shuffle) items.shuffled(Random(launchShuffleSeed + row.ordinal)) else items

    /** Rows for one built-in category; the home mix is returned for [NativeScope.ALL]. */
    fun sectionsFor(targetScope: NativeScope): List<HomeCatalogSection> =
        if (targetScope == NativeScope.ALL) _uiState.value.sections
        else _uiState.value.scopedSections[targetScope].orEmpty()

    /** The rows for one built-in category, built with the same taste profile and hidden titles. */
    private suspend fun buildScopedSections(
        targetScope: NativeScope,
        snapshot: NativeRecommendationsUiState,
        learned: NativeTasteSignals,
        language: String,
        force: Boolean,
    ): List<HomeCatalogSection> {
        val prefs = snapshot.prefs.copy(seriesPercent = targetScope.seriesPercent ?: snapshot.prefs.seriesPercent)
        val hidden = notInterested
        val signals = learned.copy(
            genreWeights = NativeRanking.applyNotInterested(learned.genreWeights, hidden.map { it.genreIds }),
        )
        val context = RowContext(
            prefs = prefs,
            signals = signals,
            blockedIds = hidden.mapTo(HashSet()) { it.id },
            blockedNames = hidden.map { normalizeAiTitle(it.name) }.filterTo(HashSet()) { it.isNotEmpty() },
            language = language,
            page = 1 + refreshNonce % 3,
            force = force,
            liked = prefs.likedGenreIds.map(NativeRanking::canonicalGenre).toSet(),
            disliked = prefs.dislikedGenreIds.map(NativeRanking::canonicalGenre).toSet(),
            today = isoDate(Clock.System.now().toEpochMilliseconds()),
            gate = Semaphore(5),
            scope = targetScope,
        )
        val built = coroutineScope {
            snapshot.enabledRows.sortedBy { it.ordinal }.map { row ->
                async {
                    try {
                        buildRow(row, context)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val deduped = NativeRanking.dedupeAcrossRows(built.map { it.items }, { it.id }, ROW_LIMIT, MIN_ROW_ITEMS)
        return built.zip(deduped).mapNotNull { (row, items) ->
            if (items.isEmpty()) null
            else toSection(row.row, orderTitles(row.row, items, prefs.shuffleTitles), context)
        }.withoutHiddenTitles()
    }

    /**
     * Anime is TMDB animation (genre 16) from Japan: Japanese-language films and Japanese-origin
     * series. Other scopes pass the query through unchanged.
     */
    private fun scopedFilters(
        media: TmdbEntityMediaType,
        filters: Map<String, String>,
        ctx: RowContext,
    ): Map<String, String> {
        if (ctx.scope != NativeScope.ANIME) return filters
        val scoped = filters.toMutableMap()
        val genres = scoped["with_genres"]
        scoped["with_genres"] = if (genres == null || '|' in genres) ANIMATION_GENRE_ID.toString()
        else "$ANIMATION_GENRE_ID,$genres"
        scoped.remove("with_original_language")
        if (media == TmdbEntityMediaType.MOVIE) scoped["with_original_language"] = "ja"
        else scoped["with_origin_country"] = "JP"
        return scoped
    }

    /** Comma separated choices; the old single-choice value "any" means no restriction. */
    private fun words(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "any" }.toSet()

    private fun savePreferences(value: NativeRecommendationPreferences) {
        AiSettingsStorage.save(KEY_GENRES, value.likedGenreIds.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_DISLIKED, value.dislikedGenreIds.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_ERA, value.releaseEras.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_LANGUAGE, value.languages.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_RATING, value.minimumRating)
        AiSettingsStorage.save(KEY_DISCOVERY, value.discoveries.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_SERIES_PERCENT, value.seriesPercent.toString())
        AiSettingsStorage.save(KEY_HIDE_MATURE, value.hideMature.toString())
        AiSettingsStorage.save(KEY_HIDE_WATCHED, value.hideWatched.toString())
        AiSettingsStorage.save(KEY_LEARN, value.learnFromHistory.toString())
        AiSettingsStorage.save(KEY_DECADES, value.decades.sorted().joinToString(","))
        AiSettingsStorage.save(KEY_SHUFFLE_TITLES, value.shuffleTitles.toString())
    }

    /** Saves immediately and refreshes shortly after, so a burst of taps causes one reload. */
    fun updatePreferences(transform: (NativeRecommendationPreferences) -> NativeRecommendationPreferences) {
        ensureLoaded()
        val current = _uiState.value.prefs
        val next = transform(current)
        if (next == current) return
        savePreferences(next)
        _uiState.value = _uiState.value.copy(prefs = next)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            refresh()
        }
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

    /**
     * Rebuilds every enabled row. With [force] the TMDB caches are bypassed, the taste profile is
     * re-read from watch history, and the recommender moves on to a fresh page of results.
     */
    fun refresh(force: Boolean = false) {
        ensureLoaded()
        if (!_uiState.value.enabled) return
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Configure a TMDB API key to use native recommendations.")
            return
        }
        if (force) {
            refreshNonce++
            NativeTasteEngine.invalidate()
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val snapshot = _uiState.value
                val prefs = snapshot.prefs
                val language = TmdbSettingsRepository.snapshot().language
                val learned = if (prefs.learnFromHistory) {
                    NativeTasteEngine.signals(language, force, recommendationPage = 1 + refreshNonce % 2)
                } else NativeTasteSignals.EMPTY
                // Hidden titles always count, even with history learning off: they are explicit feedback.
                val hidden = notInterested
                val signals = learned.copy(
                    genreWeights = NativeRanking.applyNotInterested(learned.genreWeights, hidden.map { it.genreIds }),
                )
                val context = RowContext(
                    prefs = prefs,
                    signals = signals,
                    blockedIds = hidden.mapTo(HashSet()) { it.id },
                    blockedNames = hidden.map { normalizeAiTitle(it.name) }.filterTo(HashSet()) { it.isNotEmpty() },
                    language = language,
                    page = 1 + refreshNonce % 3,
                    force = force,
                    liked = prefs.likedGenreIds.map(NativeRanking::canonicalGenre).toSet(),
                    disliked = prefs.dislikedGenreIds.map(NativeRanking::canonicalGenre).toSet(),
                    today = isoDate(Clock.System.now().toEpochMilliseconds()),
                    gate = Semaphore(5),
                )
                val built = coroutineScope {
                    snapshot.enabledRows.sortedBy { it.ordinal }.map { row ->
                        async {
                            try {
                                buildRow(row, context)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Throwable) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                // Rows are in on-screen order; earlier rows keep their titles, later ones backfill.
                val deduped = NativeRanking.dedupeAcrossRows(built.map { it.items }, { it.id }, ROW_LIMIT, MIN_ROW_ITEMS)
                val sections = built.zip(deduped).mapNotNull { (row, items) ->
                    if (items.isEmpty()) null else toSection(row.row, orderTitles(row.row, items, prefs.shuffleTitles), context)
                }
                shownGenres = built.fold(HashMap<String, List<Int>>()) { acc, row -> acc.also { it.putAll(row.genres) } }
                _uiState.value = _uiState.value.copy(
                    sections = sections.withoutHiddenTitles(),
                    isLoading = false,
                    learnedFromHistory = signals.hasHistory,
                    lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
                    errorMessage = if (sections.isEmpty()) "No native recommendations found. Loosen the filters or check your TMDB settings." else null,
                )
                // The built-in categories get their own rows: same taste, limited to their media.
                val scopedSections = NativeScope.values()
                    .filter { it != NativeScope.ALL }
                    .associateWith { targetScope -> buildScopedSections(targetScope, snapshot, learned, language, force) }
                _uiState.value = _uiState.value.copy(scopedSections = scopedSections)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message ?: "Native recommendations failed.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Row building
    // ---------------------------------------------------------------------------------------

    private class RowContext(
        val prefs: NativeRecommendationPreferences,
        val signals: NativeTasteSignals,
        val blockedIds: Set<String>,
        val blockedNames: Set<String>,
        val language: String,
        val page: Int,
        val force: Boolean,
        val liked: Set<Int>,
        val disliked: Set<Int>,
        val today: String,
        val gate: Semaphore,
        val scope: NativeScope = NativeScope.ALL,
    ) {
        val topGenres: List<Int> = NativeRanking.topGenres(signals.genreWeights, liked, disliked, 3)

        /** Anime rows keep only animated titles, so mixed pools are trimmed here. */
        fun accepts(candidate: NativeDiscoverCandidate): Boolean =
            scope != NativeScope.ANIME || ANIMATION_GENRE_ID in candidate.genreIds
        val excludedNames: Set<String> =
            (if (prefs.hideWatched) signals.watchedNames else emptySet()) + blockedNames
    }

    /** A row's picks before cross-row de-duplication, plus the genres of every pick. */
    private class BuiltRow(
        val row: NativeRecommendationRow,
        val items: List<MetaPreview>,
        val genres: Map<String, List<Int>>,
    )

    private suspend fun buildRow(row: NativeRecommendationRow, ctx: RowContext): BuiltRow? {
        val share = ctx.prefs.seriesPercent
        if (row.mediaOnly == TmdbEntityMediaType.TV && share == 0) return null
        if (row.mediaOnly == TmdbEntityMediaType.MOVIE && share == 100) return null
        if (row.kind == NativeRowKind.BecauseWatched && !ctx.signals.hasHistory) return null
        if (row.kind == NativeRowKind.Decades && ctx.prefs.decades.isEmpty()) return null

        // Fetch a little more than the row shows so de-duplication against earlier rows can backfill.
        val limit = ROW_LIMIT + ROW_RESERVE
        val (movieSlots, seriesSlots) = when (row.mediaOnly) {
            TmdbEntityMediaType.MOVIE -> limit to 0
            TmdbEntityMediaType.TV -> 0 to limit
            null -> NativeRanking.split(limit, share)
        }
        val (movies, series) = coroutineScope {
            val movieJob = async { if (movieSlots > 0) rankedFor(row, TmdbEntityMediaType.MOVIE, ctx) else emptyList() }
            val seriesJob = async {
                if (seriesSlots > 0) dropMatureSeries(rankedFor(row, TmdbEntityMediaType.TV, ctx), seriesSlots + 6, ctx) else emptyList()
            }
            movieJob.await() to seriesJob.await()
        }

        // Respect the ratio, but top up from whichever side has spare titles so rows never end short.
        var pickedMovies = movies.take(movieSlots)
        var pickedSeries = series.take(seriesSlots)
        var missing = limit - pickedMovies.size - pickedSeries.size
        if (missing > 0) {
            val extraMovies = movies.drop(pickedMovies.size).take(missing)
            missing -= extraMovies.size
            val extraSeries = series.drop(pickedSeries.size).take(missing)
            pickedMovies = pickedMovies + extraMovies
            pickedSeries = pickedSeries + extraSeries
        }
        val items: List<MetaPreview> = NativeRanking
            .interleave(pickedMovies.map { it.preview }, pickedSeries.map { it.preview })
            .take(limit)
        if (items.isEmpty()) return null
        val genres = (pickedMovies + pickedSeries).associate { it.preview.id to it.genreIds }
        return BuiltRow(row, items, genres)
    }

    /** Re-applies hidden titles, in case one was hidden while this refresh was still running. */
    private fun List<HomeCatalogSection>.withoutHiddenTitles(): List<HomeCatalogSection> {
        val hiddenIds = notInterested.mapTo(HashSet()) { it.id }
        if (hiddenIds.isEmpty()) return this
        return map { section ->
            val remaining = section.items.filterNot { it.id in hiddenIds }
            section.copy(items = remaining, availableItemCount = remaining.size)
        }
    }

    private fun toSection(row: NativeRecommendationRow, items: List<MetaPreview>, ctx: RowContext): HomeCatalogSection {
        val seed = if (row.kind == NativeRowKind.BecauseWatched) leadingSeed(items, ctx) else null
        return HomeCatalogSection(
            key = "native_${row.key}",
            title = seed?.let { "Because you watched ${it.title.name}" } ?: row.title,
            subtitle = if (seed != null) "People who watched it also watched these" else row.subtitle,
            addonName = "TMDB",
            target = CatalogTarget.TmdbRecommendations(row.key, (row.mediaOnly ?: TmdbEntityMediaType.MOVIE).value, ctx.scope),
            items = items,
            availableItemCount = items.size,
        )
    }

    /** The watched title whose TMDB recommendations account for most of the shown row, if any. */
    private fun leadingSeed(items: List<MetaPreview>, ctx: RowContext): TasteSeed? {
        val shown = items.mapTo(HashSet()) { it.id }
        return ctx.signals.seeds
            .map { seed -> seed to seed.recommendations.count { it.preview.id in shown } * seed.weight }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun dropMatureSeries(
        ranked: List<NativeDiscoverCandidate>,
        needed: Int,
        ctx: RowContext,
    ): List<NativeDiscoverCandidate> {
        if (!ctx.prefs.hideMature || ranked.isEmpty()) return ranked
        val window = ranked.take(needed + 6)
        val verdicts = coroutineScope {
            window.map { candidate ->
                async {
                    val id = candidate.preview.id.removePrefix("tmdb:").toIntOrNull()
                    id != null && ctx.gate.withPermit { TmdbMetadataService.isNativeTvMature(id) }
                }
            }.awaitAll()
        }
        return window.filterIndexed { index, _ -> !verdicts[index] } + ranked.drop(window.size)
    }

    private suspend fun rankedFor(
        row: NativeRecommendationRow,
        media: TmdbEntityMediaType,
        ctx: RowContext,
    ): List<NativeDiscoverCandidate> {
        val weights = ctx.signals.genreWeights
        val excluded = if (row.kind == NativeRowKind.BecauseWatched) {
            ctx.excludedNames + ctx.signals.seeds.map { normalizeAiTitle(it.title.name) }
        } else ctx.excludedNames

        // Titles TMDB recommends for several of the viewer's seeds get a boost (BecauseWatched only).
        var boosts: Map<String, Double> = emptyMap()

        fun rank(pool: List<NativeDiscoverCandidate>) =
            NativeRanking.rank(pool.filter { ctx.accepts(it) }, weights, ctx.liked, ctx.disliked, excluded, ctx.blockedIds, boosts)

        val pool: List<NativeDiscoverCandidate> = when (row.kind) {
            NativeRowKind.ForYou -> {
                val pools = mutableListOf<List<NativeDiscoverCandidate>>()
                genreList(media, ctx.topGenres).takeIf { it.isNotEmpty() }?.let { genres ->
                    pools += fetchAll(media, ctx, null, false, mapOf("with_genres" to genres.joinToString("|")))
                }
                ctx.signals.topKeywordIds.take(4).takeIf { it.isNotEmpty() }?.let { keywords ->
                    pools += fetchAll(media, ctx, null, false, mapOf("with_keywords" to keywords.joinToString("|")))
                }
                if (pools.isEmpty()) pools += fetchAll(media, ctx, null, false, emptyMap())
                pools.flatten()
            }
            NativeRowKind.BecauseWatched -> {
                // TMDB's own "viewers also watched" lists are the main source; keyword Discover only
                // tops the row up when they come back thin (or a seed has no TMDB id).
                val wanted = if (media == TmdbEntityMediaType.MOVIE) "movie" else "series"
                val pools = ctx.signals.seeds.map { seed -> seed.weight to seed.recommendations.filter { it.preview.type == wanted } }
                boosts = NativeRanking.coOccurrenceBoosts(pools)
                val recommended = pools.flatMap { it.second }
                val keywordPool = if (recommended.distinctBy { it.preview.id }.size >= ROW_LIMIT) emptyList() else {
                    ctx.signals.seeds.filter { it.keywordIds.isNotEmpty() }.take(3).flatMap { seed ->
                        fetchAll(media, ctx, null, false, mapOf("with_keywords" to seed.keywordIds.take(4).joinToString("|")))
                    }
                }
                recommended + keywordPool
            }
            NativeRowKind.HiddenGems -> {
                val genres = genreList(media, ctx.topGenres)
                fetchAll(media, ctx, listOf("hidden"), false, if (genres.isEmpty()) emptyMap() else mapOf("with_genres" to genres.joinToString("|")))
            }
            NativeRowKind.Fresh -> {
                val genres = genreList(media, ctx.topGenres)
                fetchAll(media, ctx, listOf("fresh"), true, if (genres.isEmpty()) emptyMap() else mapOf("with_genres" to genres.joinToString("|")))
            }
            NativeRowKind.Decades -> {
                val styles = styleList(ctx).filter { it != "fresh" }.ifEmpty { listOf("popular") }
                val decades = ctx.prefs.decades.sorted()
                val perDecade = (ROW_LIMIT + decades.size - 1) / decades.size + 1
                val key = dateKey(media)
                val perDecadeLists = coroutineScope {
                    decades.map { decade ->
                        async {
                            val extra = mapOf("$key.gte" to "$decade-01-01", "$key.lte" to "${decade + 9}-12-31")
                            rank(fetchAll(media, ctx, styles, true, extra)).take(perDecade)
                        }
                    }.awaitAll()
                }
                return NativeRanking.roundRobin(perDecadeLists)
            }
            NativeRowKind.QuickWatches ->
                fetchAll(media, ctx, null, false, mapOf("with_runtime.gte" to "60", "with_runtime.lte" to "100"))
            NativeRowKind.FinishedSeries ->
                fetchAll(media, ctx, null, false, mapOf("with_status" to "3"))
            NativeRowKind.Genre -> {
                val ids = if (media == TmdbEntityMediaType.MOVIE) row.movieGenres else row.tvGenres
                // A genre the viewer dislikes makes the whole shelf pointless.
                if (ids.any { NativeRanking.canonicalGenre(it) in ctx.disliked }) emptyList()
                else fetchAll(media, ctx, null, false, mapOf("with_genres" to ids.joinToString(",")))
            }
        }
        // Re-order the head of the row so it is not ten near copies of one genre mix or franchise.
        val ranked = rank(pool)
        return NativeRanking.diversify(
            ranked = ranked,
            scoreOf = { NativeRanking.score(it, weights, ctx.liked) + (boosts[it.preview.id] ?: 0.0) },
            count = ROW_LIMIT + ROW_RESERVE,
        )
    }

    /** Movie genre ids -> ids valid for [media]; TV drops genres it has no equivalent for. */
    private fun genreList(media: TmdbEntityMediaType, movieGenreIds: List<Int>): List<Int> =
        if (media == TmdbEntityMediaType.MOVIE) movieGenreIds else movieGenreIds.mapNotNull(NativeRanking::tvGenre).distinct()

    private fun dateKey(media: TmdbEntityMediaType) =
        if (media == TmdbEntityMediaType.MOVIE) "primary_release_date" else "first_air_date"

    /** Filters shared by every row: language, rating, discovery style, era, mature content, dislikes. */
    private fun baseFilters(
        media: TmdbEntityMediaType,
        ctx: RowContext,
        style: String,
        language: String?,
        era: String?,
    ): Map<String, String> {
        val prefs = ctx.prefs
        val isMovie = media == TmdbEntityMediaType.MOVIE
        val dateField = dateKey(media)
        val filters = linkedMapOf("include_adult" to "false")
        if (language != null) filters["with_original_language"] = language

        var minRating = prefs.minimumRating.toDoubleOrNull() ?: 0.0
        val minVotes: Int
        when (style) {
            "balanced" -> {
                filters["sort_by"] = "popularity.desc"
                minVotes = if (isMovie) 300 else 100
                minRating = maxOf(minRating, 6.5)
            }
            "hidden" -> {
                filters["sort_by"] = "vote_average.desc"
                minVotes = if (isMovie) 50 else 30
                filters["vote_count.lte"] = (if (isMovie) 1000 else 400).toString()
                minRating = maxOf(minRating, 6.8)
            }
            "acclaimed" -> {
                filters["sort_by"] = "vote_average.desc"
                minVotes = if (isMovie) 2000 else 800
                minRating = maxOf(minRating, 7.5)
            }
            "fresh" -> {
                filters["sort_by"] = "$dateField.desc"
                minVotes = if (isMovie) 30 else 20
                val year = ctx.today.take(4).toIntOrNull() ?: 2025
                filters["$dateField.gte"] = "${year - 2}-01-01"
                filters["$dateField.lte"] = ctx.today
            }
            else -> {
                filters["sort_by"] = "popularity.desc"
                minVotes = if (isMovie) 100 else 50
            }
        }
        filters["vote_count.gte"] = minVotes.toString()
        if (minRating > 0) filters["vote_average.gte"] = minRating.toString()

        if (era != null && style != "fresh") {
            eraBounds(era)?.let { (from, to) ->
                filters["$dateField.gte"] = from
                filters["$dateField.lte"] = to
            }
        }
        if (prefs.hideMature && isMovie) {
            filters["certification_country"] = "US"
            filters["certification.lte"] = "PG-13"
        }
        val avoid = genreList(media, ctx.prefs.dislikedGenreIds.toList())
        if (avoid.isNotEmpty()) filters["without_genres"] = avoid.joinToString("|")
        return filters
    }

    private fun eraBounds(era: String): Pair<String, String>? = when (era) {
        "classic" -> "1900-01-01" to "1979-12-31"
        "80s90s" -> "1980-01-01" to "1999-12-31"
        "2000s" -> "2000-01-01" to "2009-12-31"
        "2010s" -> "2010-01-01" to "2019-12-31"
        "2020s" -> "2020-01-01" to "2029-12-31"
        else -> null
    }

    private fun styleList(ctx: RowContext): List<String> =
        ctx.prefs.discoveries.sorted().ifEmpty { listOf("popular") }

    /**
     * TMDB accepts a single original language and a single date range per query, so several
     * languages / eras / styles become several queries whose results are merged and ranked together.
     * The combinations are capped, evenly spread, to keep the request count reasonable.
     */
    private fun variants(media: TmdbEntityMediaType, ctx: RowContext, styles: List<String>?, ignoreEra: Boolean): List<Map<String, String>> {
        val styleChoices = styles ?: styleList(ctx)
        val languages: List<String?> = ctx.prefs.languages.sorted().ifEmpty { listOf(null) }
        val eras: List<String?> = if (ignoreEra) listOf(null) else ctx.prefs.releaseEras.sorted().ifEmpty { listOf(null) }
        val all = styleChoices.flatMap { style -> languages.flatMap { lang -> eras.map { era -> baseFilters(media, ctx, style, lang, era) } } }
        if (all.size <= MAX_VARIANTS) return all
        return (0 until MAX_VARIANTS).map { all[it * all.size / MAX_VARIANTS] }
    }

    private suspend fun fetchAll(
        media: TmdbEntityMediaType,
        ctx: RowContext,
        styles: List<String>?,
        ignoreEra: Boolean,
        extra: Map<String, String>,
    ): List<NativeDiscoverCandidate> =
        variants(media, ctx, styles, ignoreEra).flatMap { fetchPage(media, scopedFilters(media, it + extra, ctx), ctx) }

    private suspend fun fetchPage(
        media: TmdbEntityMediaType,
        filters: Map<String, String>,
        ctx: RowContext,
    ): List<NativeDiscoverCandidate> = ctx.gate.withPermit {
        val result = TmdbMetadataService.fetchNativeDiscoverCandidates(media, filters, ctx.language, ctx.page, ctx.force)
        if (result.isEmpty() && ctx.page > 1) {
            TmdbMetadataService.fetchNativeDiscoverCandidates(media, filters, ctx.language, 1, ctx.force)
        } else result
    }
}

/** yyyy-MM-dd for an epoch timestamp (UTC), without pulling in a date library. */
internal fun isoDate(epochMs: Long): String {
    val days = epochMs.floorDiv(86_400_000L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
