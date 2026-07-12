package io.grokpot.grokify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grokpot.grokify.service.GrokifyForegroundService
import io.grokpot.grokify.ui.GrokifyAppRoot
import io.grokpot.grokify.ui.GrokifyViewModel
import io.grokpot.grokify.ui.theme.GrokifyColors
import io.grokpot.grokify.ui.theme.GrokifyTheme

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* granted map available for future UX */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestRuntimePermissions()
        startAssistantService()

        setContent {
            GrokifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = GrokifyColors.Void,
                ) {
                    val vm: GrokifyViewModel = viewModel()
                    val state by vm.state.collectAsState()

                    LaunchedEffect(state.keepScreenOn) {
                        if (state.keepScreenOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    GrokifyAppRoot(
                        state = state,
                        onSaveToken = vm::saveToken,
                        onRefresh = vm::refresh,
                        onSend = vm::sendMessage,
                        onCheckUpdate = vm::checkUpdate,
                        onDownloadInstallUpdate = vm::downloadAndInstallUpdate,
                        onToggleExpand = vm::toggleExpand,
                        onSetPanel = vm::setPanel,
                        onToggleHistory = vm::toggleUseHistory,
                        onToggleKeepScreenOn = vm::toggleKeepScreenOn,
                        onToggleEnterForNewline = vm::toggleEnterForNewline,
                        onNewChat = vm::newChat,
                        onSelectSession = vm::selectSession,
                        onDeleteSession = vm::deleteSession,
                        onAddNote = vm::addNote,
                        onToggleNote = vm::toggleNote,
                        onDeleteNote = vm::deleteNote,
                        onSelectModel = vm::selectModel,
                        onToggleMessageExclude = vm::toggleMessageExclude,
                        onDeleteMessage = vm::deleteMessage,
                        onEditMessage = vm::editMessage,
                        onRenameSession = vm::renameSession,
                        onLoadOlder = vm::loadOlderMessages,
                        onRefreshUsage = { vm.refreshUsage(force = true) },
                    )
                }
            }
        }
    }

    private fun startAssistantService() {
        val i = Intent(this, GrokifyForegroundService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
