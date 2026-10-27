package com.fxsound.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.service.SessionCompanionService
import com.fxsound.audio.ui.screens.EqualizerScreen
import com.fxsound.audio.ui.screens.HomeScreen
import com.fxsound.audio.ui.screens.PresetsScreen
import com.fxsound.audio.ui.screens.SettingsScreen
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurface
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.FxRedDark
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.FxSoundTheme
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private lateinit var hapticsManager: HapticsManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            SessionCompanionService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hapticsManager = HapticsManager(this)
        com.fxsound.audio.data.FxSoundRepository.initPersistence(this)

        // Request runtime notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start session companion foreground service
        SessionCompanionService.start(this)

        handleIncomingSessionIntent(intent)

        setContent {
            FxSoundTheme {
                MainAppContainer(
                    hapticsManager = hapticsManager
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingSessionIntent(intent)
    }

    private fun handleIncomingSessionIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL ||
            action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            if (sessionId != 0) {
                val serviceIntent = Intent(this, SessionCompanionService::class.java).apply {
                    this.action = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                }
                startService(serviceIntent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun MainAppContainer(
    hapticsManager: HapticsManager
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        val isTabletOrFoldable = maxWidth >= 720.dp

        if (isTabletOrFoldable) {
            // Responsive Split-Pane Tablet & Foldable Studio Console
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = DarkSurfaceCard,
                    header = {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = "FxSound Studio",
                            tint = FxRedPrimary,
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .size(28.dp)
                        )
                    }
                ) {
                    val railColors = NavigationRailItemDefaults.colors(
                        selectedIconColor = FxRedPrimary,
                        selectedTextColor = FxRedPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = Color(0xFF2B1218)
                    )

                    NavigationRailItem(
                        selected = selectedTab == 0,
                        onClick = {
                            hapticsManager.performPresetSelected()
                            selectedTab = 0
                        },
                        icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = "Studio") },
                        label = { Text("Studio", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = railColors
                    )

                    NavigationRailItem(
                        selected = selectedTab == 1,
                        onClick = {
                            hapticsManager.performPresetSelected()
                            selectedTab = 1
                        },
                        icon = { Icon(painterResource(R.drawable.ic_equalizer), contentDescription = "Equalizer") },
                        label = { Text("Equalizer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = railColors
                    )

                    NavigationRailItem(
                        selected = selectedTab == 2,
                        onClick = {
                            hapticsManager.performPresetSelected()
                            selectedTab = 2
                        },
                        icon = { Icon(painterResource(R.drawable.ic_presets), contentDescription = "Presets") },
                        label = { Text("Presets", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = railColors
                    )

                    NavigationRailItem(
                        selected = selectedTab == 3,
                        onClick = {
                            hapticsManager.performPresetSelected()
                            selectedTab = 3
                        },
                        icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") },
                        label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = railColors
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    when (selectedTab) {
                        0 -> {
                            // Dual-Pane Studio Console: Core 5 Effects (Left) + 10-Band EQ Curve (Right)
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    HomeScreen(
                                        hapticsManager = hapticsManager,
                                        onNavigateToEqualizer = { selectedTab = 1 },
                                        onNavigateToPresets = { selectedTab = 2 }
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(BorderSubtle)
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1.15f)
                                        .fillMaxHeight()
                                ) {
                                    EqualizerScreen(hapticsManager = hapticsManager)
                                }
                            }
                        }
                        1 -> EqualizerScreen(hapticsManager = hapticsManager)
                        2 -> PresetsScreen(hapticsManager = hapticsManager)
                        3 -> SettingsScreen(
                            hapticsManager = hapticsManager
                        )
                    }
                }
            }
        } else {
            // Standard Phone Navigation Layout
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = DarkSurfaceCard,
                        tonalElevation = 0.dp
                    ) {
                        val navBarColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FxRedPrimary,
                            selectedTextColor = FxRedPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0xFF2B1218)
                        )

                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = {
                                hapticsManager.performPresetSelected()
                                selectedTab = 0
                            },
                            icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = "Home") },
                            label = { Text("Home", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navBarColors
                        )

                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = {
                                hapticsManager.performPresetSelected()
                                selectedTab = 1
                            },
                            icon = { Icon(painterResource(R.drawable.ic_equalizer), contentDescription = "Equalizer") },
                            label = { Text("Equalizer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navBarColors
                        )

                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = {
                                hapticsManager.performPresetSelected()
                                selectedTab = 2
                            },
                            icon = { Icon(painterResource(R.drawable.ic_presets), contentDescription = "Presets") },
                            label = { Text("Presets", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navBarColors
                        )

                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = {
                                hapticsManager.performPresetSelected()
                                selectedTab = 3
                            },
                            icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") },
                            label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navBarColors
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                        .padding(innerPadding)
                ) {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            hapticsManager = hapticsManager,
                            onNavigateToEqualizer = { selectedTab = 1 },
                            onNavigateToPresets = { selectedTab = 2 }
                        )
                        1 -> EqualizerScreen(hapticsManager = hapticsManager)
                        2 -> PresetsScreen(hapticsManager = hapticsManager)
                        3 -> SettingsScreen(
                            hapticsManager = hapticsManager
                        )
                    }
                }
            }
        }
    }
}
