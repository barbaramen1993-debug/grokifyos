package io.grokify.os

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.service.GrokifyForegroundService
import io.grokify.os.service.GrokifyNotificationListener
import io.grokify.os.service.NotificationMirror
import io.grokify.os.ui.GrokifyAppRoot
import io.grokify.os.ui.GrokifyViewModel
import io.grokify.os.ui.theme.GrokifyColors
import io.grokify.os.ui.theme.GrokifyTheme

class MainActivity : ComponentActivity() {
    /**
     * Runtime permission launcher — invoked on demand from Settings toggles
     * or in-chat Allow cards (never bulk-requested at first launch).
     */
    private var permissionResultHandler: ((Map<String, Boolean>) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val handler = permissionResultHandler
        permissionResultHandler = null
        handler?.invoke(result)
    }

    private var onReturnFromSettings: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Do not request all dangerous permissions on first run — user toggles
        // each capability in Settings or via AI-driven in-chat Allow cards.
        startAssistantService()
        // Spotify OAuth deep link (cold start after browser redirect).
        io.grokify.os.apps.plugin.SpotifyOAuth.handleRedirect(this, intent)

        setContent {
            GrokifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = GrokifyColors.Void,
                ) {
                    val vm: GrokifyViewModel = viewModel()
                    val state by vm.state.collectAsState()
                    onReturnFromSettings = vm::onResumeFromSettings

                    LaunchedEffect(Unit) {
                        vm.bindPermissionRequester { perms, onResult ->
                            if (perms.isEmpty()) {
                                onResult(emptyMap())
                                return@bindPermissionRequester
                            }
                            permissionResultHandler = onResult
                            permissionLauncher.launch(perms)
                        }
                        vm.refreshPermissions()
                    }

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
                        onOpenSettings = vm::openSettings,
                        onCloseSettings = vm::closeSettings,
                        onSaveMapboxAccessToken = vm::saveMapboxAccessToken,
                        onClearMapboxAccessToken = vm::clearMapboxAccessToken,
                        onSaveApiKey = { id, value, label, desc ->
                            vm.saveApiKey(id, value, label, desc)
                        },
                        onClearApiKey = vm::clearApiKey,
                        onToggleHistory = vm::toggleUseHistory,
                        onToggleKeepScreenOn = vm::toggleKeepScreenOn,
                        onToggleEnterForNewline = vm::toggleEnterForNewline,
                        onToggleShareNotifications = vm::toggleShareNotifications,
                        onToggleShowTools = vm::toggleShowTools,
                        onToggleShowThoughts = vm::toggleShowThoughts,
                        onOpenNotificationAccess = { openNotificationListenerSettings() },
                        onRefreshNotificationAccess = vm::refreshNotificationAccessState,
                        onTogglePermission = vm::togglePermission,
                        onEnsurePermission = vm::ensurePermission,
                        onEnsurePermissions = vm::ensurePermissions,
                        onRefreshPermissions = vm::refreshPermissions,
                        onAllowPermissionRequest = vm::allowPermissionRequest,
                        onDenyPermissionRequest = vm::denyPermissionRequest,
                        onOpenAppPermissionSettings = {
                            PermissionHelper.openAppDetailsSettings(this@MainActivity)
                        },
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
                        onSetAppOrder = vm::setAppOrder,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onReturnFromSettings?.invoke()
        // Only re-check if intent still carries a Spotify callback URI
        // (custom scheme or https App Link). handleRedirect consumes data
        // and remembers the code so we never double-exchange.
        if (io.grokify.os.apps.plugin.SpotifyOAuth.isSpotifyCallbackUri(intent?.data)) {
            io.grokify.os.apps.plugin.SpotifyOAuth.handleRedirect(this, intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        io.grokify.os.apps.plugin.SpotifyOAuth.handleRedirect(this, intent)
    }

    /**
     * Opens system UI to grant Notification access.
     * Prefers the per-app detail screen (API 30+) so the toggle is one tap.
     */
    private fun openNotificationListenerSettings() {
        val cn = ComponentName(this, GrokifyNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        cn.flattenToString(),
                    )
                }
                startActivity(detail)
                return
            } catch (_: Exception) {
                // fall through to list screen
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) { /* ignore */ }
        }
        // Nudge system to bind once user returns after enabling
        NotificationMirror.requestRebind(this)
    }

    private fun startAssistantService() {
        val i = Intent(this, GrokifyForegroundService::class.java)
        ContextCompat.startForegroundService(this, i)
    }
}
