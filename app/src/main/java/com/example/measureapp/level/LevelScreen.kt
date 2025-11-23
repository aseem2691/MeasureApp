package com.example.measureapp.level

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.measureapp.viewmodel.LevelViewModel
import kotlin.math.abs

@Composable
fun LevelScreen(
    navController: NavController,
    viewModel: LevelViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    
    val pitch by viewModel.pitch.collectAsState()
    val roll by viewModel.roll.collectAsState()
    val isLevel by viewModel.isLevel.collectAsState()
    
    var hasVibrated by remember { mutableStateOf(false) }
    
    // Vibrate when level
    LaunchedEffect(isLevel) {
        if (isLevel && !hasVibrated) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
            hasVibrated = true
        } else if (!isLevel) {
            hasVibrated = false
        }
    }
    
    DisposableEffect(Unit) {
        viewModel.startSensor()
        onDispose {
            viewModel.stopSensor()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Level") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isLevel) 
                        Color(0xFF4CAF50) 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    if (isLevel) 
                        Color(0xFF4CAF50).copy(alpha = 0.1f) 
                    else 
                        MaterialTheme.colorScheme.surface
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Status text
                Text(
                    text = if (isLevel) "LEVEL" else "NOT LEVEL",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isLevel) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                )
                
                // Bubble level
                BubbleLevelView(
                    pitch = pitch,
                    roll = roll,
                    isLevel = isLevel,
                    modifier = Modifier.size(300.dp)
                )
                
                // Degree displays
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DegreeCard(
                        label = "Pitch",
                        degrees = pitch,
                        isLevel = abs(pitch) < 0.5f
                    )
                    DegreeCard(
                        label = "Roll",
                        degrees = roll,
                        isLevel = abs(roll) < 0.5f
                    )
                }
                
                // Calibration hint
                if (!isLevel) {
                    Text(
                        text = "Place device on flat surface to level",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun BubbleLevelView(
    pitch: Float,
    roll: Float,
    isLevel: Boolean,
    modifier: Modifier = Modifier
) {
    // Animate bubble position
    val animatedPitch by animateFloatAsState(
        targetValue = pitch.coerceIn(-45f, 45f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pitch"
    )
    
    val animatedRoll by animateFloatAsState(
        targetValue = roll.coerceIn(-45f, 45f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "roll"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val outerRadius = size.minDimension / 2
        val innerRadius = outerRadius * 0.8f
        val bubbleRadius = outerRadius * 0.15f
        val maxOffset = innerRadius - bubbleRadius

        // Draw outer circle
        drawCircle(
            color = Color.Gray.copy(alpha = 0.3f),
            radius = outerRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 4.dp.toPx())
        )

        // Draw inner circle
        drawCircle(
            color = Color.Gray.copy(alpha = 0.2f),
            radius = innerRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2.dp.toPx())
        )

        // Draw crosshair
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(centerX - innerRadius, centerY),
            end = Offset(centerX + innerRadius, centerY),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(centerX, centerY - innerRadius),
            end = Offset(centerX, centerY + innerRadius),
            strokeWidth = 1.dp.toPx()
        )

        // Draw center target circle
        val targetRadius = outerRadius * 0.1f
        drawCircle(
            color = if (isLevel) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.3f),
            radius = targetRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 3.dp.toPx())
        )

        // Calculate bubble position based on tilt
        val bubbleX = centerX + (animatedRoll / 45f) * maxOffset
        val bubbleY = centerY + (animatedPitch / 45f) * maxOffset

        // Draw bubble shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.2f),
            radius = bubbleRadius + 4.dp.toPx(),
            center = Offset(bubbleX + 2.dp.toPx(), bubbleY + 2.dp.toPx())
        )

        // Draw bubble
        drawCircle(
            color = if (isLevel) Color(0xFF4CAF50) else Color(0xFF2196F3),
            radius = bubbleRadius,
            center = Offset(bubbleX, bubbleY)
        )

        // Draw bubble highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = bubbleRadius * 0.4f,
            center = Offset(
                bubbleX - bubbleRadius * 0.3f,
                bubbleY - bubbleRadius * 0.3f
            )
        )
    }
}

@Composable
fun DegreeCard(
    label: String,
    degrees: Float,
    isLevel: Boolean
) {
    Card(
        modifier = Modifier.width(140.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLevel)
                Color(0xFF4CAF50).copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "%.1f°".format(degrees),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (isLevel) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
