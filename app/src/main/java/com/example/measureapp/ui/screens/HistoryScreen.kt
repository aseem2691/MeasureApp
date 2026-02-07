package com.example.measureapp.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.MeasurementType
import com.example.measureapp.viewmodel.HistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val measurements by viewModel.measurements.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            if (measurements.isNotEmpty()) {
                TextButton(onClick = { viewModel.deleteAll() }) {
                    Text("Clear All", color = Color.Red)
                }
            }
        }

        if (measurements.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📏", fontSize = 48.sp)
                    Text(
                        "No measurements yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Gray
                    )
                    Text(
                        "Measurements will appear here\nafter you complete them",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(measurements, key = { it.id }) { measurement ->
                    MeasurementCard(
                        measurement = measurement,
                        onDelete = { viewModel.deleteMeasurement(measurement.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasurementCard(
    measurement: MeasurementEntity,
    onDelete: () -> Unit
) {
    val iosYellow = Color(0xFFFFCC00)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type emoji
            Text(
                text = when (measurement.type) {
                    MeasurementType.POINT_TO_POINT -> "📏"
                    MeasurementType.RECTANGLE -> "⬜"
                    MeasurementType.PERSON_HEIGHT -> "🧍"
                    MeasurementType.PATH -> "🔗"
                    MeasurementType.AREA -> "📐"
                    MeasurementType.LEVEL -> "⚖️"
                },
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            // Info column
            Column(modifier = Modifier.weight(1f)) {
                // Distance value
                Text(
                    text = formatDistance(measurement.value),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = iosYellow
                )

                // Relative time
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        measurement.timestamp,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                // Label if present
                if (measurement.label.isNotEmpty()) {
                    Text(
                        text = measurement.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.Gray
                )
            }
        }
    }
}

private fun formatDistance(meters: Float): String {
    return if (meters >= 1.0f) {
        String.format("%.2f m", meters)
    } else {
        String.format("%.1f cm", meters * 100)
    }
}
