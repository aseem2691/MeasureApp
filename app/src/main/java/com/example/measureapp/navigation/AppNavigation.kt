package com.example.measureapp.navigation

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.measureapp.ar.MeasureActivity
import com.example.measureapp.level.LevelScreen
import com.example.measureapp.ui.screens.HistoryScreen

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
                NavigationBarItem(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "History") },
                    label = { Text("History") },
                    selected = currentRoute == "history",
                    onClick = {
                        navController.navigate("history") {
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
                val context = LocalContext.current

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
                            "Measure real-world objects with AR",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                val intent = Intent(context, MeasureActivity::class.java)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFCC00)
                            )
                        ) {
                            Text("Start Measuring", color = Color.Black)
                        }
                    }
                }
            }
            composable("level") {
                LevelScreen(navController = navController)
            }
            composable("history") {
                HistoryScreen()
            }
        }
    }
}
