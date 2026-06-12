package com.example.measureapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.measureapp.data.models.UnitType
import com.example.measureapp.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val unitType by viewModel.unitType.collectAsState()
    val hapticEnabled by viewModel.hapticEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val autoSaveEnabled by viewModel.autoSaveEnabled.collectAsState()
    val personDetectionEnabled by viewModel.personDetectionEnabled.collectAsState()
    val rectangleDetectionEnabled by viewModel.rectangleDetectionEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        SectionHeader("MEASUREMENT")

        SettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Units",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UnitType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitType.entries.size
                            ),
                            onClick = { viewModel.setUnitType(type) },
                            selected = unitType == type
                        ) {
                            Text(
                                when (type) {
                                    UnitType.METRIC -> "Metric (m, cm)"
                                    UnitType.IMPERIAL -> "Imperial (ft, in)"
                                }
                            )
                        }
                    }
                }
            }
            GroupDivider()
            ToggleRow(
                title = "Save to History",
                subtitle = "Automatically save measurements when you tap Done",
                checked = autoSaveEnabled,
                onCheckedChange = { viewModel.setAutoSave(it) }
            )
            GroupDivider()
            ToggleRow(
                title = "Person Height Detection",
                subtitle = "Use on-device AI to detect people and measure their height",
                checked = personDetectionEnabled,
                onCheckedChange = { viewModel.setPersonDetection(it) }
            )
            GroupDivider()
            ToggleRow(
                title = "Rectangle Detection",
                subtitle = "Auto-outline rectangular surfaces (experimental, can show false outlines)",
                checked = rectangleDetectionEnabled,
                onCheckedChange = { viewModel.setRectangleDetection(it) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionHeader("FEEDBACK")

        SettingsGroup {
            ToggleRow(
                title = "Haptic Feedback",
                subtitle = "Vibrate on snap and measurement events",
                checked = hapticEnabled,
                onCheckedChange = { viewModel.setHapticFeedback(it) }
            )
            GroupDivider()
            ToggleRow(
                title = "Sounds",
                subtitle = "Play sounds on measurement events",
                checked = soundEnabled,
                onCheckedChange = { viewModel.setSoundEnabled(it) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionHeader("ABOUT")

        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Version", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "1.0",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GroupDivider()
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Measure objects in the real world using your camera and augmented reality. " +
                        "For best results, use in a well-lit area and move your phone slowly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
