package io.grokify.os.apps.companion

/**
 * In-app Companion World — maps from [godot/companion-world] ship inside the
 * GrokifyOS APK (`assets/companion/world/`). No separate Godot package install.
 *
 * Uses the same VRM stage + stick/jump; [CompanionStageHost.enterWorld] loads
 * a map (proto_arena, kenney_plaza, courtyard, mini_dungeon).
 */
object CompanionWorldLauncher {
    /** Historical external package id (optional fallback only). */
    const val WORLD_PACKAGE = "io.grokify.os.companion.world"

    /** Always true — world is embedded in the main app. */
    fun isWorldInstalled(ctx: android.content.Context): Boolean {
        // Prefer embedded stage; keep package check only as informational.
        if (CompanionStageHost.isAttached()) return true
        return try {
            ctx.packageManager.getPackageInfo(WORLD_PACKAGE, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            // Embedded maps still work without the side package.
            true
        }
    }

    /**
     * Open the in-app world on the live Companion stage.
     * @return null on success, or a short user-facing error string.
     */
    fun openWorld(ctx: android.content.Context, mapId: String = "proto_arena"): String? {
        if (!CompanionStageHost.isAttached()) {
            return "Open Companion first so the stage can load a map."
        }
        val ok = CompanionStageHost.enterWorld(mapId)
        if (!ok) {
            return "Could not load world map. Try again after the avatar loads."
        }
        return null
    }

    fun nextMap(): Boolean = CompanionStageHost.nextMap()

    fun leaveWorld(): Boolean = CompanionStageHost.leaveWorld()
}
