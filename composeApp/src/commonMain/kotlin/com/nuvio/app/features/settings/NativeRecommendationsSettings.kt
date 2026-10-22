package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.ai.NATIVE_DECADE_OPTIONS
import com.nuvio.app.features.ai.NativeRecommendationRow
import com.nuvio.app.features.ai.NativeRecommendationsRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Genre ids (TMDB movie ids) offered in the taste pickers, paired with their label. */
private val TasteGenres: List<Pair<Int, StringResource>> = listOf(
    28 to Res.string.native_rec_genre_28,
    12 to Res.string.native_rec_genre_12,
    16 to Res.string.native_rec_genre_16,
    35 to Res.string.native_rec_genre_35,
    80 to Res.string.native_rec_genre_80,
    99 to Res.string.native_rec_genre_99,
    18 to Res.string.native_rec_genre_18,
    10751 to Res.string.native_rec_genre_10751,
    14 to Res.string.native_rec_genre_14,
    36 to Res.string.native_rec_genre_36,
    27 to Res.string.native_rec_genre_27,
    9648 to Res.string.native_rec_genre_9648,
    10749 to Res.string.native_rec_genre_10749,
    878 to Res.string.native_rec_genre_878,
    53 to Res.string.native_rec_genre_53,
    10752 to Res.string.native_rec_genre_10752,
)

internal fun LazyListScope.nativeRecommendationsSettingsContent(isTablet: Boolean) {
    item {
        val state by remember {
            NativeRecommendationsRepository.ensureLoaded()
            NativeRecommendationsRepository.uiState
        }.collectAsStateWithLifecycle()
        val prefs = state.prefs

        Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 20.dp else 16.dp)) {
            SettingsSection(title = stringResource(Res.string.native_rec_section_main), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.native_rec_enable),
                        description = stringResource(Res.string.native_rec_enable_desc),
                        checked = state.enabled,
                        isTablet = isTablet,
                        onCheckedChange = NativeRecommendationsRepository::setEnabled,
                    )
                    if (state.enabled) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.native_rec_refresh),
                            description = stringResource(
                                if (state.isLoading) Res.string.native_rec_refreshing else Res.string.native_rec_refresh_desc,
                            ),
                            icon = Icons.Rounded.Refresh,
                            enabled = !state.isLoading,
                            isTablet = isTablet,
                            trailingContent = {
                                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            },
                            onClick = { NativeRecommendationsRepository.refresh(force = true) },
                        )
                    }
                }
            }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (state.enabled) {
                SettingsSection(title = stringResource(Res.string.native_rec_section_rows), isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        NativeRecommendationRow.values().forEachIndexed { index, row ->
                            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                            SettingsSwitchRow(
                                title = row.title,
                                description = row.subtitle,
                                checked = row in state.enabledRows,
                                isTablet = isTablet,
                                onCheckedChange = { checked -> NativeRecommendationsRepository.setRowEnabled(row, checked) },
                            )
                        }
                    }
                }

                SettingsSection(title = stringResource(Res.string.native_rec_section_mix), isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        NativeSingleChoice(
                            title = stringResource(Res.string.native_rec_mix_title),
                            description = stringResource(Res.string.native_rec_mix_desc),
                            options = listOf(
                                0 to stringResource(Res.string.native_rec_mix_movies_only),
                                25 to stringResource(Res.string.native_rec_mix_mostly_movies),
                                50 to stringResource(Res.string.native_rec_mix_half),
                                75 to stringResource(Res.string.native_rec_mix_mostly_series),
                                100 to stringResource(Res.string.native_rec_mix_series_only),
                            ),
                            selected = prefs.seriesPercent,
                            onSelect = { value -> NativeRecommendationsRepository.updatePreferences { it.copy(seriesPercent = value) } },
                        )
                    }
                }

                SettingsSection(title = stringResource(Res.string.native_rec_section_taste), isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.native_rec_learn),
                            description = stringResource(Res.string.native_rec_learn_desc) + if (prefs.learnFromHistory) {
                                " " + stringResource(if (state.learnedFromHistory) Res.string.native_rec_learn_active else Res.string.native_rec_learn_waiting)
                            } else "",
                            checked = prefs.learnFromHistory,
                            isTablet = isTablet,
                            onCheckedChange = { checked -> NativeRecommendationsRepository.updatePreferences { it.copy(learnFromHistory = checked) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.native_rec_hide_watched),
                            description = stringResource(Res.string.native_rec_hide_watched_desc),
                            checked = prefs.hideWatched,
                            isTablet = isTablet,
                            onCheckedChange = { checked -> NativeRecommendationsRepository.updatePreferences { it.copy(hideWatched = checked) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.native_rec_shuffle_titles),
                            description = stringResource(Res.string.native_rec_shuffle_titles_desc),
                            checked = prefs.shuffleTitles,
                            isTablet = isTablet,
                            onCheckedChange = { checked -> NativeRecommendationsRepository.updatePreferences { it.copy(shuffleTitles = checked) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        val genreOptions = TasteGenres.map { (id, label) -> id to stringResource(label) }
                        NativeMultiChoice(
                            title = stringResource(Res.string.native_rec_liked_genres),
                            description = stringResource(Res.string.native_rec_liked_genres_desc),
                            options = genreOptions,
                            selected = prefs.likedGenreIds,
                            onToggle = { id, on ->
                                NativeRecommendationsRepository.updatePreferences {
                                    it.copy(
                                        likedGenreIds = if (on) it.likedGenreIds + id else it.likedGenreIds - id,
                                        dislikedGenreIds = if (on) it.dislikedGenreIds - id else it.dislikedGenreIds,
                                    )
                                }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        NativeMultiChoice(
                            title = stringResource(Res.string.native_rec_disliked_genres),
                            description = stringResource(Res.string.native_rec_disliked_genres_desc),
                            options = genreOptions,
                            selected = prefs.dislikedGenreIds,
                            onToggle = { id, on ->
                                NativeRecommendationsRepository.updatePreferences {
                                    it.copy(
                                        dislikedGenreIds = if (on) it.dislikedGenreIds + id else it.dislikedGenreIds - id,
                                        likedGenreIds = if (on) it.likedGenreIds - id else it.likedGenreIds,
                                    )
                                }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.native_rec_hidden_titles),
                            description = if (state.notInterestedCount > 0) {
                                stringResource(Res.string.native_rec_hidden_titles_desc_count, state.notInterestedCount)
                            } else {
                                stringResource(Res.string.native_rec_hidden_titles_desc_empty)
                            },
                            icon = Icons.Rounded.VisibilityOff,
                            enabled = state.notInterestedCount > 0,
                            isTablet = isTablet,
                            onClick = NativeRecommendationsRepository::clearNotInterested,
                        )
                    }
                }

                SettingsSection(title = stringResource(Res.string.native_rec_section_filters), isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.native_rec_hide_mature),
                            description = stringResource(Res.string.native_rec_hide_mature_desc),
                            checked = prefs.hideMature,
                            isTablet = isTablet,
                            onCheckedChange = { checked -> NativeRecommendationsRepository.updatePreferences { it.copy(hideMature = checked) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        NativeMultiChoice(
                            title = stringResource(Res.string.native_rec_discovery),
                            description = stringResource(Res.string.native_rec_discovery_desc),
                            options = listOf(
                                "popular" to stringResource(Res.string.native_rec_discovery_popular),
                                "balanced" to stringResource(Res.string.native_rec_discovery_balanced),
                                "hidden" to stringResource(Res.string.native_rec_discovery_hidden),
                                "acclaimed" to stringResource(Res.string.native_rec_discovery_acclaimed),
                                "fresh" to stringResource(Res.string.native_rec_discovery_fresh),
                            ),
                            selected = prefs.discoveries,
                            onToggle = { id, on ->
                                NativeRecommendationsRepository.updatePreferences {
                                    val next = if (on) it.discoveries + id else it.discoveries - id
                                    // Keep at least one style so the rows always know how to sort.
                                    if (next.isEmpty()) it else it.copy(discoveries = next)
                                }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        NativeMultiChoice(
                            title = stringResource(Res.string.native_rec_era),
                            description = stringResource(Res.string.native_rec_era_desc) + " " + stringResource(Res.string.native_rec_multi_hint),
                            options = listOf(
                                "classic" to stringResource(Res.string.native_rec_era_classic),
                                "80s90s" to stringResource(Res.string.native_rec_era_80s90s),
                                "2000s" to stringResource(Res.string.native_rec_era_2000s),
                                "2010s" to stringResource(Res.string.native_rec_era_2010s),
                                "2020s" to stringResource(Res.string.native_rec_era_2020s),
                            ),
                            selected = prefs.releaseEras,
                            onToggle = { id, on ->
                                NativeRecommendationsRepository.updatePreferences { it.copy(releaseEras = if (on) it.releaseEras + id else it.releaseEras - id) }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        NativeMultiChoice(
                            title = stringResource(Res.string.native_rec_language),
                            description = stringResource(Res.string.native_rec_multi_hint),
                            options = listOf(
                                "en" to stringResource(Res.string.native_rec_language_en),
                                "ko" to stringResource(Res.string.native_rec_language_ko),
                                "ja" to stringResource(Res.string.native_rec_language_ja),
                                "hi" to stringResource(Res.string.native_rec_language_hi),
                                "bn" to stringResource(Res.string.native_rec_language_bn),
                                "fr" to stringResource(Res.string.native_rec_language_fr),
                                "es" to stringResource(Res.string.native_rec_language_es),
                            ),
                            selected = prefs.languages,
                            onToggle = { id, on ->
                                NativeRecommendationsRepository.updatePreferences { it.copy(languages = if (on) it.languages + id else it.languages - id) }
                            },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        NativeSingleChoice(
                            title = stringResource(Res.string.native_rec_rating),
                            options = listOf(
                                "0" to stringResource(Res.string.native_rec_rating_any),
                                "5" to "5+", "6" to "6+", "7" to "7+", "8" to "8+",
                            ),
                            selected = prefs.minimumRating,
                            onSelect = { value -> NativeRecommendationsRepository.updatePreferences { it.copy(minimumRating = value) } },
                        )
                    }
                }

                if (NativeRecommendationRow.Decades in state.enabledRows) {
                    SettingsSection(title = stringResource(Res.string.native_rec_section_decades), isTablet = isTablet) {
                        SettingsGroup(isTablet = isTablet) {
                            NativeMultiChoice(
                                title = stringResource(Res.string.native_rec_decades),
                                description = stringResource(Res.string.native_rec_decades_desc),
                                options = NATIVE_DECADE_OPTIONS.map { it to stringResource(Res.string.native_rec_decade_label, it) },
                                selected = prefs.decades,
                                onToggle = { decade, on ->
                                    NativeRecommendationsRepository.updatePreferences { current ->
                                        val next = if (on) current.decades + decade else current.decades - decade
                                        // The row needs at least one decade to be meaningful.
                                        if (next.isEmpty()) current else current.copy(decades = next)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> NativeSingleChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        FlowRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) ->
                FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> NativeMultiChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T, Boolean) -> Unit,
    description: String? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        FlowRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) ->
                val isSelected = key in selected
                FilterChip(selected = isSelected, onClick = { onToggle(key, !isSelected) }, label = { Text(label) })
            }
        }
    }
}
