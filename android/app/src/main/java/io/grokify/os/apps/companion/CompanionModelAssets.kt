package io.grokify.os.apps.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bundled Seed-san VRM + VRMA animation clips live under assets. WebView cannot
 * reliably fetch large `file:///android_asset/...` binaries, so we materialize
 * once into app files and the stage reads bytes via the Kotlin bridge.
 */
object CompanionModelAssets {
    private const val TAG = "CompanionVrm"
    private const val ASSET_SEED = "companion/models/default/Seed-san.vrm"
    private const val LOCAL_DIR = "companion/bundled"
    private const val LOCAL_NAME = "Seed-san.vrm"
    private const val ASSET_ANIM_DIR = "companion/animations"
    private const val LOCAL_ANIM_DIR = "companion/animations"
    private val lock = Any()

    /** glTF binary magic "glTF" (VRM and VRMA are both glTF binaries). */
    private val GLTF_MAGIC = byteArrayOf(0x67, 0x6c, 0x54, 0x46)

    /**
     * Built-in VRMA clip ids (filename without extension under [ASSET_ANIM_DIR]).
     * Portable humanoid animations — same clip works on any VRM 1.0.
     */
    val BUNDLED_VRMA_IDS: List<String> = listOf(
        "angry",
        "blush",
        "clapping",
        "goodbye",
        "jump",
        "lookaround",
        "relax",
        "sad",
        "sleepy",
        "surprised",
        "thinking",
        "test",
    )

    /** Absolute path to extracted Seed-san, or null if extraction failed. */
    fun ensureBundledVrmFile(ctx: Context): String? {
        synchronized(lock) {
            return extractAssetGlb(
                ctx,
                assetPath = ASSET_SEED,
                localDir = LOCAL_DIR,
                localName = LOCAL_NAME,
            )
        }
    }

    /**
     * Materialize every bundled `.vrma` into app files. Safe to call often —
     * skips files that already match size.
     * @return number of clips available on disk after the pass
     */
    fun ensureBundledAnimations(ctx: Context): Int {
        synchronized(lock) {
            var ok = 0
            for (id in BUNDLED_VRMA_IDS) {
                val path = ensureAnimationFileLocked(ctx, id)
                if (path != null) ok++
            }
            return ok
        }
    }

    /**
     * Absolute path to a bundled VRMA clip, or null.
     * @param id clip id e.g. `goodbye` / `Goodbye` / `goodbye.vrma`
     */
    fun ensureAnimationFile(ctx: Context, id: String): String? {
        synchronized(lock) {
            return ensureAnimationFileLocked(ctx, id)
        }
    }

    private fun ensureAnimationFileLocked(ctx: Context, id: String): String? {
        val clean = id.trim()
            .removePrefix("anim:")
            .removePrefix("vrma:")
            .removeSuffix(".vrma")
            .removeSuffix(".VRMA")
            .lowercase()
            .replace(Regex("""[\s-]+"""), "")
        if (clean.isEmpty()) return null
        // Accept look_around → lookaround
        val key = clean.replace("_", "")
        val match = BUNDLED_VRMA_IDS.firstOrNull {
            it.equals(key, ignoreCase = true) ||
                it.replace("_", "").equals(key, ignoreCase = true)
        } ?: return null
        return extractAssetGlb(
            ctx,
            assetPath = "$ASSET_ANIM_DIR/$match.vrma",
            localDir = LOCAL_ANIM_DIR,
            localName = "$match.vrma",
        )
    }

    private fun extractAssetGlb(
        ctx: Context,
        assetPath: String,
        localDir: String,
        localName: String,
    ): String? {
        return try {
            val app = ctx.applicationContext
            val dir = File(app.filesDir, localDir)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdir failed: ${dir.absolutePath}")
                return null
            }
            val out = File(dir, localName)
            val assetLen = assetLength(app, assetPath)

            if (out.isFile && out.length() >= 64L && looksLikeGltfBinary(out)) {
                if (assetLen <= 0L || out.length() == assetLen) {
                    return out.absolutePath
                }
                Log.i(
                    TAG,
                    "asset size mismatch file=${out.length()} asset=$assetLen path=$assetPath — re-extract",
                )
            }

            val tmp = File(dir, "$localName.part")
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "could not clear partial ${tmp.absolutePath}")
            }
            app.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.isFile || tmp.length() < 64L || !looksLikeGltfBinary(tmp)) {
                Log.w(
                    TAG,
                    "extracted invalid len=${tmp.length()} magic=${looksLikeGltfBinary(tmp)} $assetPath",
                )
                tmp.delete()
                return null
            }
            if (out.exists() && !out.delete()) {
                tmp.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.delete()
            } else if (!tmp.renameTo(out)) {
                tmp.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.delete()
            }
            if (!out.isFile || out.length() <= 0L || !looksLikeGltfBinary(out)) {
                Log.w(TAG, "final file invalid after extract $localName")
                return null
            }
            Log.i(TAG, "extracted $assetPath → ${out.absolutePath} (${out.length()} bytes)")
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractAssetGlb failed $assetPath", e)
            null
        }
    }

    fun fileUrl(absolutePath: String): String {
        val p = absolutePath.trim()
        if (p.startsWith("file://")) return p
        return if (p.startsWith("/")) "file://$p" else "file:///$p"
    }

    fun looksLikeGltfBinary(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4L) return false
                val magic = ByteArray(4)
                raf.seek(0)
                if (raf.read(magic) != 4) return false
                magic.contentEquals(GLTF_MAGIC)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun assetLength(ctx: Context, assetPath: String): Long {
        return try {
            ctx.applicationContext.assets.openFd(assetPath).use { it.length }
        } catch (_: IOException) {
            // Compressed-in-APK assets cannot openFd; size check skipped.
            -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
