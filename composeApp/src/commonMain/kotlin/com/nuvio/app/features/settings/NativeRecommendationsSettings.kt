package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.ai.NativeRecommendationRow
import com.nuvio.app.features.ai.NativeRecommendationsRepository

internal fun LazyListScope.nativeRecommendationsSettingsContent(isTablet: Boolean) {
    item {
        val state by remember { NativeRecommendationsRepository.ensureLoaded(); NativeRecommendationsRepository.uiState }.collectAsStateWithLifecycle()
        var taste by remember { mutableStateOf(NativeRecommendationsRepository.tastePreferences()) }
        val genres = listOf("28" to "Action", "12" to "Adventure", "16" to "Animation", "35" to "Comedy", "80" to "Crime", "18" to "Drama", "14" to "Fantasy", "27" to "Horror", "9648" to "Mystery", "10749" to "Romance", "878" to "Sci-Fi", "53" to "Thriller")
        Column {
            SettingsSection(title = "NATIVE TMDB RECOMMENDATIONS", isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(title = "Native TMDB Recommendations", description = "Movie and series discovery powered by TMDB. Independent of Gemini.", icon = Icons.Rounded.Theaters, isTablet = isTablet, onClick = { NativeRecommendationsRepository.refresh() })
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(title = "Enable native recommendations", description = "Show enabled native rows on Home.", checked = state.enabled, isTablet = isTablet, onCheckedChange = NativeRecommendationsRepository::setEnabled)
                    if (state.enabled) NativeRecommendationRow.values().forEach { row ->
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(title = row.title, description = row.subtitle, checked = row in state.enabledRows, isTablet = isTablet, onCheckedChange = { checked -> NativeRecommendationsRepository.setRowEnabled(row, checked) })
                    }
                }
            }
            SettingsSection(title = "BUILD MY TASTE PROFILE", isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    Text("Genres you enjoy", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                    genres.forEach { (id, name) ->
                        SettingsSwitchRow(title = name, description = null, checked = id in taste.genreIds, isTablet = isTablet, onCheckedChange = { checked -> taste = taste.copy(genreIds = if (checked) taste.genreIds + id else taste.genreIds - id) })
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRowCompat(title = "Release era", value = taste.releaseEra, options = listOf("any" to "Any era", "classic" to "Before 1980", "80s90s" to "1980s–1990s", "2000s" to "2000s", "2010s" to "2010s", "2020s" to "2020s"), isTablet = isTablet, onChange = { taste = taste.copy(releaseEra = it) })
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRowCompat(title = "Original language", value = taste.language, options = listOf("any" to "Any language", "en" to "English", "ko" to "Korean", "ja" to "Japanese", "hi" to "Hindi", "bn" to "Bengali", "fr" to "French", "es" to "Spanish"), isTablet = isTablet, onChange = { taste = taste.copy(language = it) })
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRowCompat(title = "Minimum TMDB rating", value = taste.minimumRating, options = listOf("0" to "Any rating", "5" to "5+", "6" to "6+", "7" to "7+", "8" to "8+"), isTablet = isTablet, onChange = { taste = taste.copy(minimumRating = it) })
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRowCompat(title = "Discovery style", value = taste.discovery, options = listOf("popular" to "Popular hits", "hidden" to "Hidden gems"), isTablet = isTablet, onChange = { taste = taste.copy(discovery = it) })
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(title = "Apply taste profile", description = "Save preferences and refresh recommendations.", icon = Icons.Rounded.Theaters, isTablet = isTablet, onClick = { NativeRecommendationsRepository.saveTastePreferences(taste) })
                }
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceRowCompat(title: String, value: String, options: List<Pair<String, String>>, isTablet: Boolean, onChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow {
            options.forEach { (key, label) ->
                androidx.compose.material3.FilterChip(selected = value == key, onClick = { onChange(key) }, label = { Text(label) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
    }
}
