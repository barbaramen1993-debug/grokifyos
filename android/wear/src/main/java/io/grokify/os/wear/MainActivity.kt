package io.grokify.os.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.grokify.os.wear.data.PhoneApiKeySync
import io.grokify.os.wear.data.SensorHub
import io.grokify.os.wear.data.WearPrefs
import io.grokify.os.wear.ui.CarinaOverlay
import io.grokify.os.wear.ui.RadialHud
import io.grokify.os.wear.update.WearSelfUpdater
import io.grokify.os.wear.voice.CarinaTools
import io.grokify.os.wear.voice.CarinaVoiceSession
import kotlinx.coroutines.launch

/**
 * Grokify Wear — radial telemetry HUD + Carina voice agent (swipe up).
 */
class MainActivity : ComponentActivity() {

    private var hub: SensorHub? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hub?.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HUD + Carina are always-on while this activity is visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestCorePermissions()

        val openCarina = intent?.getBooleanExtra(EXTRA_OPEN_CARINA, false) == true ||
            intent?.action == ACTION_OPEN_CARINA

        setContent {
            MaterialTheme {
                val view = LocalView.current
                DisposableEffect(Unit) {
                    view.keepScreenOn = true
                    onDispose { view.keepScreenOn = false }
                }
                val scope = rememberCoroutineScope()
                val sensorHub = remember {
                    SensorHub(applicationContext, scope).also { hub = it }
                }
                val snapshot by sensorHub.snapshot.collectAsState()
                var showPermBanner by remember { mutableStateOf(true) }
                var carinaOpen by remember { mutableStateOf(openCarina) }
                var carinaSnap by remember {
                    mutableStateOf(
                        CarinaVoiceSession.Snapshot(
                            turn = CarinaVoiceSession.Turn.Idle,
                            statusLine = null,
                            partialUser = null,
                            partialAssistant = null,
                            level = 0f,
                        ),
                    )
                }
                val prefs = remember { WearPrefs(applicationContext) }
                val phoneKeySync = remember { PhoneApiKeySync(applicationContext, scope) }
                val selfUpdater = remember { WearSelfUpdater(applicationContext) }
                val phoneKeyReady by phoneKeySync.hasKey.collectAsState()
                val syncStatus by phoneKeySync.status.collectAsState()
                val updateStatus by selfUpdater.status.collectAsState()
                val updateProgress by selfUpdater.progress.collectAsState()
                val updateRunning by selfUpdater.running.collectAsState()
                var hasDeviceToken by remember {
                    mutableStateOf(prefs.deviceToken.isNotBlank())
                }
                var apiKeySet by remember {
                    mutableStateOf(prefs.spaceXaiApiKey.isNotBlank() || phoneKeyReady)
                }
                var keySourceLabel by remember {
                    mutableStateOf(
                        if (prefs.keySource == WearPrefs.SOURCE_PHONE) "phone" else "local",
                    )
                }

                LaunchedEffect(snapshot) {
                    CarinaTools.updateSnapshot(snapshot)
                }

                LaunchedEffect(phoneKeyReady, prefs.spaceXaiApiKey, prefs.keySource) {
                    val ready = phoneKeyReady || prefs.spaceXaiApiKey.isNotBlank()
                    apiKeySet = ready
                    if (ready) {
                        keySourceLabel =
                            if (prefs.keySource == WearPrefs.SOURCE_PHONE) "phone" else "local"
                    }
                }

                DisposableEffect(sensorHub, phoneKeySync) {
                    sensorHub.start()
                    phoneKeySync.start()
                    val obs = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                sensorHub.onPermissionsChanged()
                                sensorHub.start()
                                phoneKeySync.start()
                                apiKeySet = prefs.spaceXaiApiKey.isNotBlank()
                                hasDeviceToken = prefs.deviceToken.isNotBlank()
                                keySourceLabel =
                                    if (prefs.keySource == WearPrefs.SOURCE_PHONE) "phone"
                                    else "local"
                            }
                            Lifecycle.Event.ON_STOP -> sensorHub.stop()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(obs)
                    onDispose {
                        lifecycle.removeObserver(obs)
                        sensorHub.stop()
                        phoneKeySync.stop()
                        CarinaVoiceSession.stop()
                    }
                }

                DisposableEffect(carinaOpen) {
                    if (!carinaOpen) {
                        onDispose { }
                        return@DisposableEffect onDispose { }
                    }
                    val listener = object : CarinaVoiceSession.Listener {
                        override fun onSnapshot(snap: CarinaVoiceSession.Snapshot) {
                            carinaSnap = snap
                        }

                        override fun onError(message: String) {
                            carinaSnap = carinaSnap.copy(
                                turn = CarinaVoiceSession.Turn.Idle,
                                statusLine = message,
                            )
                        }
                    }
                    // Listener is set on start; keep local state updates via start path.
                    onDispose {
                        // session stopped when leaving overlay explicitly
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    if (carinaOpen) {
                        CarinaOverlay(
                            snap = carinaSnap,
                            apiKeySet = apiKeySet,
                            keySourceLabel = keySourceLabel,
                            syncStatus = syncStatus,
                            updateStatus = updateStatus,
                            updateProgress = updateProgress,
                            updateRunning = updateRunning,
                            hasDeviceToken = hasDeviceToken,
                            onStart = {
                                requestMicPermission()
                                // Fresh pull in case phone vault changed.
                                phoneKeySync.requestFromPhone()
                                CarinaVoiceSession.start(
                                    this@MainActivity,
                                    object : CarinaVoiceSession.Listener {
                                        override fun onSnapshot(snap: CarinaVoiceSession.Snapshot) {
                                            carinaSnap = snap
                                        }

                                        override fun onError(message: String) {
                                            carinaSnap = CarinaVoiceSession.Snapshot(
                                                turn = CarinaVoiceSession.Turn.Idle,
                                                statusLine = message,
                                                partialUser = null,
                                                partialAssistant = null,
                                                level = 0f,
                                            )
                                        }
                                    },
                                )
                            },
                            onStop = { CarinaVoiceSession.stop() },
                            onDismiss = {
                                CarinaVoiceSession.stop()
                                carinaOpen = false
                            },
                            onSaveApiKey = { key ->
                                prefs.spaceXaiApiKey = key
                                prefs.keySource = WearPrefs.SOURCE_LOCAL
                                apiKeySet = key.trim().isNotEmpty()
                                keySourceLabel = "local"
                            },
                            onRequestPhoneKey = {
                                phoneKeySync.requestFromPhone()
                                phoneKeySync.requestDeviceTokenFromPhone()
                            },
                            onUpdateApp = {
                                // Ensure token is present, then one-shot check→download→install.
                                if (prefs.deviceToken.isBlank()) {
                                    phoneKeySync.requestDeviceTokenFromPhone()
                                }
                                hasDeviceToken = prefs.deviceToken.isNotBlank()
                                scope.launch { selfUpdater.updateNow() }
                            },
                            onSaveDeviceToken = { token ->
                                prefs.deviceToken = token
                                hasDeviceToken = token.trim().isNotEmpty()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        RadialHud(
                            snapshot = snapshot,
                            versionName = BuildConfig.VERSION_NAME,
                            modifier = Modifier.fillMaxSize(),
                            onSwipeUp = {
                                requestMicPermission()
                                carinaOpen = true
                            },
                        )
                    }

                    if (!carinaOpen && showPermBanner && snapshot.permissionHints.isNotEmpty()) {
                        PermissionBanner(
                            hints = snapshot.permissionHints,
                            onGrant = {
                                requestCorePermissions()
                                hub?.onPermissionsChanged()
                            },
                            onNotifAccess = {
                                try {
                                    startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (_: Exception) {
                                }
                            },
                            onDismiss = { showPermBanner = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun requestCorePermissions() {
        val needed = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val bg = Manifest.permission::class.java
                    .getField("BODY_SENSORS_BACKGROUND")
                    .get(null) as? String
                if (bg != null) needed += bg
            } catch (_: Exception) {
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestMicPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    companion object {
        const val EXTRA_OPEN_CARINA = "open_carina"
        const val ACTION_OPEN_CARINA = "io.grokify.os.wear.OPEN_CARINA"
    }
}

@Composable
private fun PermissionBanner(
    hints: List<String>,
    onGrant: () -> Unit,
    onNotifAccess: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC0F172A))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Enable: ${hints.joinToString(", ")}",
            color = Color(0xFF22D3EE),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Sensors", onGrant)
            Chip("Notifs", onNotifAccess)
            Chip("Hide", onDismiss)
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF0F172A),
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF22D3EE))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
