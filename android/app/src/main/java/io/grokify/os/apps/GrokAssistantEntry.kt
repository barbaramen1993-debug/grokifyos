package io.grokify.os.apps

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent

/**
 * System / hardware entry points into Grok Assistant
 * (assist button, voice command, BT headset, Android Auto launcher).
 */
object GrokAssistantEntry {

    const val EXTRA_OPEN_ASSISTANT = GrokAssistantOverlayService.EXTRA_OPEN_ASSISTANT
    const val EXTRA_AUTO_LISTEN = "grok_assistant_auto_listen"

    fun isAssistantIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_OPEN_ASSISTANT, false)) return true
        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) return true
        return when (intent.action) {
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND,
            "android.intent.action.VOICE_ASSIST",
            Intent.ACTION_WEB_SEARCH,
            RecognizerIntent.ACTION_WEB_SEARCH,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE,
            -> true
            else -> false
        }
    }

    /**
     * Open assistant pane + optionally expand overlay and start listening.
     * Safe to call from Activity or Service (adds NEW_TASK when needed).
     */
    fun activate(
        ctx: Context,
        listen: Boolean = true,
        openPane: Boolean = true,
    ) {
        val app = ctx.applicationContext
        val store = GrokAssistantStore(app)
        if (openPane) {
            runCatching {
                val i = io.grokify.os.widgets.WidgetNav.openPluginIntent(
                    app,
                    io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
                )
                if (ctx !is android.app.Activity) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
            }
        }
        if (!store.enabled) return
        // Hardware/assist entry: ephemeral overlay when permitted (not a permanent bubble).
        if (GrokAssistantOverlayService.canDrawOverlays(app)) {
            if (!store.overlayEnabled) store.overlayEnabled = true
            if (listen) {
                GrokAssistantOverlayService.startListeningForCommand(app)
            } else {
                GrokAssistantOverlayService.start(app, expand = true)
            }
        }
    }

    fun openDefaultAssistantSettings(ctx: Context) {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidates += Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
        candidates += Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        candidates += Intent(Settings.ACTION_SETTINGS)
        for (base in candidates) {
            val i = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                ctx.packageManager.resolveActivity(i, 0) != null
            }.getOrDefault(false)
            if (!ok) continue
            runCatching {
                ctx.startActivity(i)
                return
            }
        }
    }

    /**
     * Request [RoleManager.ROLE_ASSISTANT] when available.
     * Many OEMs still require a full VoiceInteractionService — UI explains fallbacks.
     * Returns true if a role-request activity was launched.
     */
    fun requestAssistantRole(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = activity.getSystemService(RoleManager::class.java) ?: return false
        return try {
            if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return false
            if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return false
            activity.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isAssistantRoleHeld(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    const val REQ_ASSISTANT_ROLE = 42017
}
