package com.example.measureapp.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*

@Composable
fun CameraPermissionHandler(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit,
    content: @Composable ((() -> Unit), Boolean) -> Unit
) {
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                hasPermission = true
                onPermissionGranted()
            } else {
                hasPermission = false
                onPermissionDenied()
            }
        }
    )

    content(
        {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        },
        hasPermission
    )
}
