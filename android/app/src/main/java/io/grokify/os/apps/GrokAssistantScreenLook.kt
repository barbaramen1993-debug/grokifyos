package io.grokify.os.apps

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.ui.theme.GrokifyColors
import io.grokify.os.ui.theme.GrokifyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screen capture + crop flow for Grok Assistant “Look at my screen”.
 *
 * 1. Optionally hides the floating overlay
 * 2. Requests [MediaProjection] (system dialog)
 * 3. Captures one frame via a short mediaProjection FGS
 * 4. Lets the user drag a crop rect + optional question
 * 5. Sends cropped JPEG through [GrokAssistantSession.sendWithImage]
 */
class GrokAssistantScreenLookActivity : ComponentActivity() {

    private var pendingQuery: String = ""
    private var restoreOverlay: Boolean = true

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            finishWithRestore("Screen capture cancelled")
            return@registerForActivityResult
        }
        // Hand off to FGS (required for mediaProjection on API 34+)
        GrokAssistantScreenCaptureService.start(
            this,
            result.resultCode,
            result.data!!,
        )
        capturing = true
        // Poll for capture result
        waitForCapture()
    }

    private var capturing by mutableStateOf(false)
    private var captureError by mutableStateOf<String?>(null)
    private var fullBitmap by mutableStateOf<Bitmap?>(null)
    private var sending by mutableStateOf(false)
    private var statusLine by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        restoreOverlay = intent.getBooleanExtra(EXTRA_RESTORE_OVERLAY, true)

        if (intent.getBooleanExtra(EXTRA_HIDE_OVERLAY, true)) {
            GrokAssistantOverlayService.hideForCapture(this)
        }

        setContent {
            GrokifyTheme {
                ScreenLookRoot()
            }
        }

        // Small delay so the overlay is off-screen before the system dialog / capture.
        window.decorView.postDelayed({
            if (isFinishing) return@postDelayed
            val bmp = pendingBitmap.getAndSet(null)
            if (bmp != null) {
                fullBitmap = bmp
                capturing = false
            } else {
                startProjectionRequest()
            }
        }, 180)
    }

    private fun startProjectionRequest() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }.onFailure {
            finishWithRestore(it.message ?: "Could not start screen capture")
        }
    }

    private fun waitForCapture() {
        val handler = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        fun tick() {
            if (isFinishing) return
            val bmp = pendingBitmap.getAndSet(null)
            if (bmp != null) {
                fullBitmap = bmp
                capturing = false
                captureError = null
                return
            }
            val err = pendingError.getAndSet(null)
            if (err != null) {
                capturing = false
                captureError = err
                finishWithRestore(err)
                return
            }
            if (System.currentTimeMillis() - started > 12_000L) {
                capturing = false
                finishWithRestore("Screen capture timed out")
                return
            }
            handler.postDelayed({ tick() }, 80)
        }
        handler.postDelayed({ tick() }, 120)
    }

    private fun finishWithRestore(msg: String? = null) {
        if (restoreOverlay) {
            GrokAssistantOverlayService.showAfterCapture(this, expand = true)
        }
        if (!msg.isNullOrBlank()) {
            Log.i(TAG, msg)
        }
        finish()
    }

    @Composable
    private fun ScreenLookRoot() {
        val scope = rememberCoroutineScope()
        val bmp = fullBitmap
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            when {
                capturing || (bmp == null && captureError == null) -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = GrokifyColors.GlowCyan)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Capturing screen…",
                            color = GrokifyColors.TextPrimary,
                            fontSize = 14.sp,
                        )
                        Text(
                            "Allow screen recording if prompted",
                            color = GrokifyColors.TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                bmp != null -> {
                    CropAndAsk(
                        bitmap = bmp,
                        initialQuery = pendingQuery,
                        sending = sending,
                        status = statusLine,
                        onCancel = { finishWithRestore(null) },
                        onUseFull = { q ->
                            scope.launch { submitCrop(bmp, fullRect(bmp), q) }
                        },
                        onConfirm = { rect, q ->
                            scope.launch { submitCrop(bmp, rect, q) }
                        },
                    )
                }
            }
        }
    }

    private fun fullRect(bmp: Bitmap) = Rect(0, 0, bmp.width, bmp.height)

    private suspend fun submitCrop(src: Bitmap, crop: Rect, query: String) {
        if (sending) return
        sending = true
        statusLine = "Sending to Grok…"
        val safe = Rect(
            crop.left.coerceIn(0, src.width - 1),
            crop.top.coerceIn(0, src.height - 1),
            crop.right.coerceIn(1, src.width),
            crop.bottom.coerceIn(1, src.height),
        )
        if (safe.width() < 8 || safe.height() < 8) {
            statusLine = "Crop too small"
            sending = false
            return
        }
        val result = withContext(Dispatchers.IO) {
            val cropped = try {
                Bitmap.createBitmap(src, safe.left, safe.top, safe.width(), safe.height())
            } catch (e: Exception) {
                return@withContext GrokAssistantSession.SendResult(
                    ok = false,
                    errorText = e.message ?: "crop_failed",
                )
            }
            val jpeg = compressJpeg(cropped, maxEdge = 1600, quality = 82)
            if (cropped !== src) cropped.recycle()
            if (jpeg == null || jpeg.isEmpty()) {
                return@withContext GrokAssistantSession.SendResult(
                    ok = false,
                    errorText = "encode_failed",
                )
            }
            // Keep a small cache copy for debugging / future attach
            runCatching {
                File(cacheDir, "assistant-look-last.jpg").writeBytes(jpeg)
            }
            GrokAssistantSession.sendWithImage(
                this@GrokAssistantScreenLookActivity,
                userText = query,
                imageJpeg = jpeg,
            )
        }
        sending = false
        if (result.ok) {
            statusLine = "Done"
            finishWithRestore(null)
        } else {
            statusLine = result.errorText ?: "Failed"
            // Stay on crop so user can retry / cancel
        }
    }

    companion object {
        private const val TAG = "GrokAssistantLook"
        const val EXTRA_QUERY = "query"
        const val EXTRA_HIDE_OVERLAY = "hide_overlay"
        const val EXTRA_RESTORE_OVERLAY = "restore_overlay"

        /** Latest capture handed from [GrokAssistantScreenCaptureService]. */
        val pendingBitmap = AtomicReference<Bitmap?>(null)
        val pendingError = AtomicReference<String?>(null)

        fun start(
            ctx: Context,
            query: String = "",
            hideOverlayFirst: Boolean = true,
        ) {
            pendingBitmap.set(null)
            pendingError.set(null)
            if (hideOverlayFirst) {
                GrokAssistantOverlayService.hideForCapture(ctx)
            }
            val i = Intent(ctx, GrokAssistantScreenLookActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_QUERY, query)
                putExtra(EXTRA_HIDE_OVERLAY, hideOverlayFirst)
                putExtra(EXTRA_RESTORE_OVERLAY, true)
            }
            ctx.startActivity(i)
        }

        fun compressJpeg(bmp: Bitmap, maxEdge: Int = 1600, quality: Int = 82): ByteArray? {
            return try {
                var working = bmp
                val maxDim = max(bmp.width, bmp.height)
                if (maxDim > maxEdge) {
                    val scale = maxEdge.toFloat() / maxDim
                    val w = max(1, (bmp.width * scale).roundToInt())
                    val h = max(1, (bmp.height * scale).roundToInt())
                    working = Bitmap.createScaledBitmap(bmp, w, h, true)
                }
                val out = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (working !== bmp) working.recycle()
                out.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "compressJpeg", e)
                null
            }
        }
    }
}

@Composable
private fun CropAndAsk(
    bitmap: Bitmap,
    initialQuery: String,
    sending: Boolean,
    status: String?,
    onCancel: () -> Unit,
    onUseFull: (String) -> Unit,
    onConfirm: (Rect, String) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    // Normalized crop in image space (0..1)
    var leftN by remember { mutableFloatStateOf(0.08f) }
    var topN by remember { mutableFloatStateOf(0.08f) }
    var rightN by remember { mutableFloatStateOf(0.92f) }
    var bottomN by remember { mutableFloatStateOf(0.92f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Look at my screen",
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel, enabled = !sending) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = GrokifyColors.TextMuted)
            }
        }
        Text(
            "Drag the box to crop · then Ask Grok",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            val density = LocalDensity.current
            val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val imgAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val boxAspect = maxW / maxH
            val drawW: Float
            val drawH: Float
            if (imgAspect > boxAspect) {
                drawW = maxW
                drawH = maxW / imgAspect
            } else {
                drawH = maxH
                drawW = maxH * imgAspect
            }
            val offsetX = (maxW - drawW) / 2f
            val offsetY = (maxH - drawH) / 2f

            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Screenshot",
                    modifier = Modifier
                        .size(
                            width = with(density) { drawW.toDp() },
                            height = with(density) { drawH.toDp() },
                        ),
                )
                // Dim outside crop + crop border with drag
                Canvas(
                    Modifier
                        .size(
                            width = with(density) { drawW.toDp() },
                            height = with(density) { drawH.toDp() },
                        )
                        .pointerInput(drawW, drawH) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val x = change.position.x / drawW
                                val y = change.position.y / drawH
                                // Move nearest edge toward pointer
                                val dl = kotlin.math.abs(x - leftN)
                                val dr = kotlin.math.abs(x - rightN)
                                val dt = kotlin.math.abs(y - topN)
                                val db = kotlin.math.abs(y - bottomN)
                                val minD = min(min(dl, dr), min(dt, db))
                                when (minD) {
                                    dl -> leftN = (leftN + drag.x / drawW).coerceIn(0f, rightN - 0.05f)
                                    dr -> rightN = (rightN + drag.x / drawW).coerceIn(leftN + 0.05f, 1f)
                                    dt -> topN = (topN + drag.y / drawH).coerceIn(0f, bottomN - 0.05f)
                                    else -> bottomN = (bottomN + drag.y / drawH).coerceIn(topN + 0.05f, 1f)
                                }
                            }
                        },
                ) {
                    val l = leftN * size.width
                    val t = topN * size.height
                    val r = rightN * size.width
                    val b = bottomN * size.height
                    // Dim outside
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset.Zero, Size(size.width, t))
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset(0f, t), Size(l, b - t))
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset(r, t), Size(size.width - r, b - t))
                    // Crop frame
                    drawRect(
                        color = GrokifyColors.GlowCyan,
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3f),
                    )
                    // Corner handles
                    val hs = 18f
                    val handleColor = GrokifyColors.GlowMint
                    listOf(
                        Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b),
                    ).forEach { c ->
                        drawRect(
                            color = handleColor,
                            topLeft = Offset(c.x - hs / 2, c.y - hs / 2),
                            size = Size(hs, hs),
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(GrokifyColors.VoidElevated)
                .padding(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending,
                placeholder = {
                    Text("What should Grok look at? (optional)", fontSize = 13.sp)
                },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GrokifyColors.TextPrimary,
                    unfocusedTextColor = GrokifyColors.TextPrimary,
                    focusedBorderColor = GrokifyColors.GlowCyan,
                    unfocusedBorderColor = GrokifyColors.PanelBorder,
                    cursorColor = GrokifyColors.GlowCyan,
                    focusedContainerColor = GrokifyColors.PanelSoft,
                    unfocusedContainerColor = GrokifyColors.PanelSoft,
                ),
            )
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    color = GrokifyColors.GlowMint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
                TextButton(
                    onClick = {
                        leftN = 0f; topN = 0f; rightN = 1f; bottomN = 1f
                        onUseFull(query)
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Full screen", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val rect = Rect(
                            (leftN * bitmap.width).roundToInt(),
                            (topN * bitmap.height).roundToInt(),
                            (rightN * bitmap.width).roundToInt(),
                            (bottomN * bitmap.height).roundToInt(),
                        )
                        onConfirm(rect, query)
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrokifyColors.GlowViolet.copy(alpha = 0.85f),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Ask", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * One-shot media-projection foreground service (API 34+ requirement).
 * Captures a single frame, then stops.
 */
class GrokAssistantScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        startAsForeground()
        if (resultCode != Activity.RESULT_OK || data == null) {
            GrokAssistantScreenLookActivity.pendingError.set("No projection grant")
            stopSelfSafely()
            return START_NOT_STICKY
        }
        Thread {
            try {
                val bmp = captureOnce(resultCode, data)
                if (bmp != null) {
                    GrokAssistantScreenLookActivity.pendingBitmap.set(bmp)
                } else {
                    GrokAssistantScreenLookActivity.pendingError.set("Empty capture")
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                GrokAssistantScreenLookActivity.pendingError.set(e.message ?: "capture_failed")
            } finally {
                Handler(Looper.getMainLooper()).post { stopSelfSafely() }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val n: Notification = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_ASSISTANT)
            .setContentTitle("Grok Assistant")
            .setContentText("Capturing screen…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (Build.VERSION.SDK_INT >= 34) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
            runCatching { startForeground(NOTIF_ID, n) }
        }
    }

    private fun captureOnce(resultCode: Int, data: Intent): Bitmap? {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection null")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>(null)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                latch.countDown()
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "GrokAssistantCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            // Wait for a frame
            val deadline = System.currentTimeMillis() + 4_000L
            while (System.currentTimeMillis() < deadline) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        val cropped = if (bmp.width > width) {
                            Bitmap.createBitmap(bmp, 0, 0, width, height).also {
                                if (it !== bmp) bmp.recycle()
                            }
                        } else {
                            bmp
                        }
                        holder.set(cropped)
                        latch.countDown()
                        break
                    } finally {
                        image.close()
                    }
                } else {
                    Thread.sleep(40)
                }
            }
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
        }
        return holder.get()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "GrokAssistantCapture"
        private const val NOTIF_ID = 42043
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, GrokAssistantScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
