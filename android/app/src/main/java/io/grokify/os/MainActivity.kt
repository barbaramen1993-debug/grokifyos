package io.grokify.os

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.service.GrokifyForegroundService
import io.grokify.os.service.GrokifyNotificationListener
import io.grokify.os.service.NotificationMirror
import io.grokify.os.ui.GrokifyAppRoot
import io.grokify.os.ui.GrokifyViewModel
import io.grokify.os.ui.theme.GrokifyColors
import io.grokify.os.ui.theme.GrokifyTheme
import kotlinx.coroutines.launch

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
        // Home-screen widgets → open inner app / Live DJ tab.
        io.grokify.os.widgets.WidgetNav.handleIntent(intent)
        handleAssistantEntry(intent)

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
                        onSend = { text, images -> vm.sendMessage(text, images) },
                        onPrepareChatImage = { uri -> vm.prepareChatImage(uri) },
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
                        onSetWorkDir = vm::setWorkDir,
                        onResetWorkDir = vm::resetWorkDir,
                        onToggleWorkDirBrowser = vm::toggleWorkDirBrowser,
                        onBrowseWorkDir = vm::browseWorkDir,
                        onUseBrowsedWorkDir = vm::useBrowsedWorkDir,
                        onToggleMessageExclude = vm::toggleMessageExclude,
                        onDeleteMessage = vm::deleteMessage,
                        onEditMessage = vm::editMessage,
                        onRenameSession = vm::renameSession,
                        onLoadOlder = vm::loadOlderMessages,
                        onRefreshUsage = { vm.refreshUsage(force = true) },
                        onGrokLogin = {
                            lifecycleScope.launch {
                                val url = vm.ensureGrokLoginUrl(forceNew = false)
                                if (!url.isNullOrBlank()) {
                                    try {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    } catch (_: Exception) {
                                        // No browser / invalid URL — status chip still shows code.
                                    }
                                } else {
                                    vm.refreshUsage(force = true)
                                }
                            }
                        },
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
        io.grokify.os.widgets.WidgetNav.handleIntent(intent)
        handleAssistantEntry(intent)
    }

    /**
     * System assistant / voice-search / BT headset / car entry, or explicit open-assistant extras.
     * Deep-links into Grok Assistant; expands overlay and starts mic when enabled.
     */
    private fun handleAssistantEntry(intent: Intent?) {
        if (intent == null) return
        if (!io.grokify.os.apps.GrokAssistantEntry.isAssistantIntent(intent)) return

        val wantListen = intent.getBooleanExtra(
            io.grokify.os.apps.GrokAssistantEntry.EXTRA_AUTO_LISTEN,
            true,
        ) || intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.action == "android.intent.action.VOICE_ASSIST" ||
            intent.action == Intent.ACTION_ASSIST

        io.grokify.os.widgets.WidgetNav.openPlugin(
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
        val store = io.grokify.os.apps.GrokAssistantStore(this)
        if (store.enabled) {
            // Keep wake loop in sync when arriving from hardware buttons.
            io.grokify.os.apps.GrokAssistantWakeService.sync(this)
            if (store.overlayEnabled &&
                io.grokify.os.apps.GrokAssistantOverlayService.canDrawOverlays(this)
            ) {
                if (wantListen) {
                    io.grokify.os.apps.GrokAssistantOverlayService.startListeningForCommand(this)
                } else {
                    io.grokify.os.apps.GrokAssistantOverlayService.start(this, expand = true)
                }
            }
        }
        // Consume one-shot flags so rotation doesn't re-fire.
        intent.removeExtra(io.grokify.os.apps.GrokAssistantOverlayService.EXTRA_OPEN_ASSISTANT)
        intent.removeExtra(io.grokify.os.apps.GrokAssistantEntry.EXTRA_AUTO_LISTEN)
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
