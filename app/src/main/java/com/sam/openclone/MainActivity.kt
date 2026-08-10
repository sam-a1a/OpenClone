package com.sam.openclone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import com.sam.openclone.clone.CloneCoordinator
import com.sam.openclone.ui.AppListScreen
import com.sam.openclone.ui.theme.OpenCloneTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenCloneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppListScreen()
                }
            }
        }

        // Clone progress and the install prompt are both delivered as
        // notifications, so ask once rather than at the moment they are needed.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The installer's confirmation dialog is an activity, and it can only be
    // launched directly while something of ours is on screen.
    override fun onStart() {
        super.onStart()
        CloneCoordinator.uiVisible = true
    }

    override fun onStop() {
        CloneCoordinator.uiVisible = false
        super.onStop()
    }
}
