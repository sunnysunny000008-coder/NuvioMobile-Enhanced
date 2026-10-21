package com.nuvio.app.features.ai

/**
 * Small, local-only taste profile for Native TMDB recommendations.
 * Kept separate from AI/Gemini preferences and existing Home catalog settings.
 */
internal object NativeTasteBuilderRepository {
    private const val KEY_SELECTED_GENRES = "native_taste_builder_genres"

    val availableGenres: List<String> = listOf(
        "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
        "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance",
        "Science Fiction", "Thriller", "War", "Western",
    )

    fun selectedGenres(): List<String> {
        val stored = AiSettingsStorage.load(KEY_SELECTED_GENRES)
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        return availableGenres.filter { it in stored }
    }

    fun setSelectedGenres(genres: Set<String>) {
        val valid = availableGenres.filter { it in genres }
        AiSettingsStorage.save(KEY_SELECTED_GENRES, valid.joinToString("|"))
    }
}
