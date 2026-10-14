package org.icculus.chunky.morsecar

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import org.icculus.chunky.morsecar.ui.MainViewModel
import org.icculus.chunky.morsecar.ui.MorseCarApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Playback works either way; without permission there's just no notification.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MorseCarApp(vm) }
        if (savedInstanceState == null) handleIntent(intent)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            vm.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
    }
}
