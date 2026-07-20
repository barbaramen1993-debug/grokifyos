package io.grokify.os.apps.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bundled Seed-san VRM lives under assets. WebView cannot reliably fetch large
 * `file:///android_asset/...` binaries, so we materialize once into app files
 * and the stage reads bytes via the Kotlin bridge.
 */
object CompanionModelAssets {
    private const val TAG = "CompanionVrm"
    private const val ASSET_SEED = "companion/models/default/Seed-san.vrm"
    private const val LOCAL_DIR = "companion/bundled"
    private const val LOCAL_NAME = "Seed-san.vrm"
    private val lock = Any()

    /** glTF binary magic "glTF" */
    private val GLTF_MAGIC = byteArrayOf(0x67, 0x6c, 0x54, 0x46)

    /** Absolute path to extracted Seed-san, or null if extraction failed. */
    fun ensureBundledVrmFile(ctx: Context): String? {
        synchronized(lock) {
            return try {
                val app = ctx.applicationContext
                val dir = File(app.filesDir, LOCAL_DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "mkdir failed: ${dir.absolutePath}")
                    return null
                }
                val out = File(dir, LOCAL_NAME)
                val assetLen = assetLength(app, ASSET_SEED)

                if (out.isFile && out.length() >= 64L && looksLikeGltfBinary(out)) {
                    if (assetLen <= 0L || out.length() == assetLen) {
                        return out.absolutePath
                    }
                    Log.i(
                        TAG,
                        "bundled VRM size mismatch file=${out.length()} asset=$assetLen — re-extract",
                    )
                }

                // Write to a sibling then rename so readers never see a partial file.
                val tmp = File(dir, "$LOCAL_NAME.part")
                if (tmp.exists() && !tmp.delete()) {
                    Log.w(TAG, "could not clear partial ${tmp.absolutePath}")
                }
                app.assets.open(ASSET_SEED).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.isFile || tmp.length() < 64L || !looksLikeGltfBinary(tmp)) {
                    Log.w(
                        TAG,
                        "extracted VRM invalid len=${tmp.length()} magic=${looksLikeGltfBinary(tmp)}",
                    )
                    tmp.delete()
                    return null
                }
                if (out.exists() && !out.delete()) {
                    // Fallback: overwrite in place
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
                    Log.w(TAG, "final VRM invalid after extract")
                    return null
                }
                Log.i(TAG, "extracted bundled VRM → ${out.absolutePath} (${out.length()} bytes)")
                out.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "ensureBundledVrmFile failed", e)
                null
            }
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
