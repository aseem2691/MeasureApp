package com.example.measureapp.navigation

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.measureapp.ar.MeasureActivity
import com.example.measureapp.level.LevelScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Measure") },
                    label = { Text("Measure") },
                    selected = currentRoute == "measurement",
                    onClick = {
                        navController.navigate("measurement") {
                            popUpTo("measurement") { inclusive = true }
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Level") },
                    label = { Text("Level") },
                    selected = currentRoute == "level",
                    onClick = {
                        navController.navigate("level") {
                            popUpTo("measurement")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController, 
            startDestination = "measurement",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("measurement") {
                // Launch Activity automatically
                val context = LocalContext.current
                var hasLaunched by remember { mutableStateOf(false) }
                
                // Auto-launch MeasureActivity once
                LaunchedEffect(Unit) {
                    if (!hasLaunched) {
                        val intent = Intent(context, MeasureActivity::class.java)
                        context.startActivity(intent)
                        hasLaunched = true
                    }
                }
                
                // Show info screen
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "AR Measurement",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            "Activity-based implementation",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                val intent = Intent(context, MeasureActivity::class.java)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Open AR Measure")
                        }
                    }
                }
            }
            composable("level") {
                LevelScreen(navController = navController)
            }
        }
    }
}
