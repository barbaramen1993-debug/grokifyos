package io.grokify.os.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.grokify.os.wear.MainActivity
import io.grokify.os.wear.R
import io.grokify.os.wear.data.WearTelemetryCache
import io.grokify.os.wear.voice.CarinaTools
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wear OS tile — radial HUD twin of the main app face (arcs + metrics + tap → Carina).
 */
class CarinaTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val live = CarinaTools.currentSnapshot()
        val cached = WearTelemetryCache.read(this)
        // Prefer live hub values when the app is/was open; fall back to cache + battery.
        val hr = live.heartRateBpm ?: cached.heartRateBpm
        val steps = live.stepsToday ?: cached.stepsToday
        val bat = live.batteryPct ?: cached.batteryPct ?: WearTelemetryCache.readBattery(this)
        val wx = live.weatherTempC ?: cached.weatherTempC
        val heading = live.headingDeg ?: cached.headingDeg
        val media = live.mediaTitle ?: cached.mediaTitle
        val timeMs = live.timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
        val hrText = hr?.roundToInt()?.let { "$it" } ?: "—"
        val stepText = steps?.let { formatSteps(it) } ?: "—"
        val batText = bat?.let { "$it%" } ?: "—"
        val wxText = wx?.roundToInt()?.let { "${it}°" } ?: "—"
        val headText = heading?.roundToInt()?.let { "${it}°" } ?: "N"

        val hrFrac = (((hr ?: 0f) - 40f).coerceIn(0f, 140f) / 140f)
        val stepFrac = ((steps ?: 0L).toFloat() / 10_000f).coerceIn(0f, 1f)
        val batFrac = ((bat ?: 0).toFloat() / 100f).coerceIn(0f, 1f)
        val wxFrac = wx?.let { ((it + 10f) / 50f).coerceIn(0f, 1f) } ?: 0.12f
        val mediaFrac = if (!media.isNullOrBlank()) 0.8f else 0.12f

        val click = ModifiersBuilders.Clickable.Builder()
            .setId("open_carina")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_OPEN_CARINA,
                                ActionBuilders.AndroidBooleanExtra.Builder()
                                    .setValue(true)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        // Outer container: full tile, dark HUD plate.
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(click)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(COL_BG))
                            .build(),
                    )
                    .build(),
            )
            // Progress arcs (back → front).
            .addContent(metricArc(0.96f, hrFrac, COL_MAGENTA, -90f, 300f, 7f))
            .addContent(metricArc(0.84f, stepFrac, COL_CYAN, -40f, 280f, 6.5f))
            .addContent(metricArc(0.72f, batFrac, COL_GREEN, 20f, 260f, 6f))
            .addContent(metricArc(0.60f, wxFrac, COL_AMBER, 70f, 240f, 5.5f))
            .addContent(metricArc(0.50f, mediaFrac, COL_VIOLET, 120f, 220f, 5f))
            // Center readouts.
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setAll(dp(10f))
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(icon(ID_CARINA, 14f))
                            .addContent(spacer(4f))
                            .addContent(label("CARINA", 11f, COL_MAGENTA, bold = true))
                            .build(),
                    )
                    .addContent(label(timeStr, 22f, COL_TEXT, bold = true))
                    .addContent(spacer(2f))
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(icon(ID_HEART, 12f))
                            .addContent(spacer(2f))
                            .addContent(label(hrText, 11f, COL_MAGENTA))
                            .addContent(spacer(6f))
                            .addContent(icon(ID_STEPS, 12f))
                            .addContent(spacer(2f))
                            .addContent(label(stepText, 11f, COL_CYAN))
                            .build(),
                    )
                    .addContent(spacer(2f))
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(icon(ID_BATTERY, 12f))
                            .addContent(spacer(2f))
                            .addContent(label(batText, 10f, COL_GREEN))
                            .addContent(spacer(6f))
                            .addContent(icon(ID_WEATHER, 12f))
                            .addContent(spacer(2f))
                            .addContent(label(wxText, 10f, COL_AMBER))
                            .build(),
                    )
                    .addContent(spacer(2f))
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(icon(ID_COMPASS, 11f))
                            .addContent(spacer(2f))
                            .addContent(label(headText, 9f, COL_AMBER))
                            .addContent(spacer(6f))
                            .addContent(label("TAP · TALK", 9f, COL_CYAN))
                            .build(),
                    )
                    .build(),
            )
            .build()

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(root)
            .build()
        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(entry)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(30_000L)
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(ID_HEART, androidImage(R.drawable.ic_hud_heart))
            .addIdToImageMapping(ID_STEPS, androidImage(R.drawable.ic_hud_steps))
            .addIdToImageMapping(ID_BATTERY, androidImage(R.drawable.ic_hud_battery))
            .addIdToImageMapping(ID_WEATHER, androidImage(R.drawable.ic_hud_weather))
            .addIdToImageMapping(ID_MEDIA, androidImage(R.drawable.ic_hud_media))
            .addIdToImageMapping(ID_COMPASS, androidImage(R.drawable.ic_hud_compass))
            .addIdToImageMapping(ID_CARINA, androidImage(R.drawable.ic_hud_carina))
            .build()
        return Futures.immediateFuture(resources)
    }

    private fun androidImage(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(resId)
                    .build(),
            )
            .build()

    private fun metricArc(
        radiusFrac: Float,
        fraction: Float,
        color: Int,
        startDeg: Float,
        maxSweep: Float,
        thicknessDp: Float,
    ): LayoutElementBuilders.LayoutElement {
        // Approximate arc rings with full-circle Arc containers sized by padding.
        // Wear protolayout Arc is edge-anchored; we stack multiple arcs in a Box.
        val sweep = (maxSweep * fraction.coerceIn(0f, 1f)).coerceAtLeast(2f)
        val pad = ((1f - radiusFrac) * 50f).coerceAtLeast(1f)
        return LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(startDeg))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(dp(pad))
                            .build(),
                    )
                    .build(),
            )
            // Dim track
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(maxSweep))
                    .setThickness(dp(thicknessDp))
                    .setColor(argb(withAlpha(color, 0x28)))
                    .build(),
            )
            // Progress
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(sweep))
                    .setThickness(dp(thicknessDp))
                    .setColor(argb(color))
                    .build(),
            )
            .build()
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): LayoutElementBuilders.Text {
        val font = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setColor(argb(color))
        if (bold) font.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(font.build())
            .setMaxLines(1)
            .build()
    }

    private fun icon(id: String, sizeDp: Float): LayoutElementBuilders.Image =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(id)
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .build()

    private fun spacer(h: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(dp(h))
            .setWidth(dp(h))
            .build()

    private fun formatSteps(n: Long): String =
        if (n >= 10_000) String.format(Locale.US, "%.1fk", n / 1000.0)
        else n.toString()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        private const val RESOURCES_VERSION = "3"
        private const val ID_HEART = "ic_heart"
        private const val ID_STEPS = "ic_steps"
        private const val ID_BATTERY = "ic_battery"
        private const val ID_WEATHER = "ic_weather"
        private const val ID_MEDIA = "ic_media"
        private const val ID_COMPASS = "ic_compass"
        private const val ID_CARINA = "ic_carina"

        private const val COL_BG = 0xFF03050A.toInt()
        private const val COL_CYAN = 0xFF22D3EE.toInt()
        private const val COL_MAGENTA = 0xFFE879F9.toInt()
        private const val COL_AMBER = 0xFFFBBF24.toInt()
        private const val COL_GREEN = 0xFF4ADE80.toInt()
        private const val COL_VIOLET = 0xFFA78BFA.toInt()
        private const val COL_TEXT = 0xFFE2E8F0.toInt()
    }
}
