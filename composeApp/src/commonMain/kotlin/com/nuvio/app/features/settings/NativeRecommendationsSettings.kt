package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
internal fun NativeRecommendationsCollapsibleRow(isTablet: Boolean) {
    val state by remember {
        NativeRecommendationsRepository.ensureLoaded()
        NativeRecommendationsRepository.uiState
    }.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Column {
        SettingsNavigationRow(
            title = "Native TMDB Recommendations",
            description = "Personalized TMDB shelves using this profile's viewing history. No Gemini required.",
            icon = Icons.Rounded.AutoAwesome,
            isTablet = isTablet,
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Enable native recommendations",
                    description = "Adds TMDB-powered rows to Home independently of Gemini.",
                    checked = state.enabled,
                    isTablet = isTablet,
                    onCheckedChange = NativeRecommendationsRepository::setEnabled,
                )
                NativeRecommendationRow.values().forEach { row ->
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = row.title,
                        description = row.subtitle,
                        checked = row in state.enabledRows,
                        isTablet = isTablet,
                        onCheckedChange = { NativeRecommendationsRepository.setRowEnabled(row, it) },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                TextButton(
                    onClick = { NativeRecommendationsRepository.refresh(force = true) },
                    enabled = state.enabled && !state.isLoading,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                    Text(text = if (state.isLoading) "Refreshing…" else "Refresh recommendations")
                }
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                Text(
                    text = "Personalized rows use this Nuvio profile's watched titles and saved library first. " +
                        "Connected trackers are optional. Results are cached for up to 6 hours; use Refresh now to rebuild them.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
