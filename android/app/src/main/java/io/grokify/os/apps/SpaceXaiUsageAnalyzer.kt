package io.grokify.os.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val MANAGEMENT_BASE = "https://management-api.x.ai"
private const val CONSOLE_BILLING = "https://console.x.ai/team/default/billing"
private const val CONSOLE_USAGE = "https://console.x.ai/team/default/usage"
private const val CONSOLE_MGMT_KEYS = "https://console.x.ai/team/default/management-keys"

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

private val currency: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

data class SpaceXaiUsageSnapshot(
    val teamId: String,
    val keyName: String?,
    val prepaidBalanceCents: Long?,
    val prepaidChanges: List<BalanceChangeRow>,
    val periodYear: Int?,
    val periodMonth: Int?,
    val periodSpendCents: Long?,
    val prepaidCreditsUsedCents: Long?,
    val softLimitCents: Long?,
    val hardLimitCents: Long?,
    val usageByModel: List<UsageBucket>,
    val dailySpend: List<DailySpendPoint>,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
)

data class BalanceChangeRow(
    val origin: String,
    val amountCents: Long,
    val status: String?,
    val createTime: String?,
    val invoiceNumber: String?,
)

data class UsageBucket(
    val label: String,
    val amountUsd: Double,
)

data class DailySpendPoint(
    val day: String,
    val amountUsd: Double,
)

private sealed class LoadState {
    data object Idle : LoadState()
    data object Loading : LoadState()
    data class Ready(val data: SpaceXaiUsageSnapshot) : LoadState()
    data class Error(val message: String, val hint: String? = null) : LoadState()
}

@Composable
fun SpaceXaiUsageAnalyzerPane(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState>(LoadState.Idle) }
    val hasKey = remember {
        mutableStateOf(!HostApiKeyStore.getSpaceXaiManagementKey(context).isNullOrBlank())
    }

    fun refresh() {
        hasKey.value = !HostApiKeyStore.getSpaceXaiManagementKey(context).isNullOrBlank()
        if (!hasKey.value) {
            state = LoadState.Error(
                message = "No Management key in vault",
                hint = "Settings → SpaceXAI Management key (vault id spacexai_management_key). " +
                    "console.x.ai → Management Keys with billing read. " +
                    "This is not the same as the inference API key used for Voice TTS.",
            )
            return
        }
        state = LoadState.Loading
        scope.launch {
            state = withContext(Dispatchers.IO) {
                val key = HostApiKeyStore.getSpaceXaiManagementKey(context).orEmpty()
                fetchUsageSnapshot(key)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(GrokifyColors.VoidElevated)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "SpaceXAI Usage Analyzer",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    "Credits · spend · limits",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = { refresh() }, enabled = state !is LoadState.Loading) {
                Icon(Icons.Default.Refresh, "Refresh", tint = GrokifyColors.GlowAmber)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (val s = state) {
                LoadState.Idle, LoadState.Loading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GrokifyColors.GlowAmber)
                            Spacer(Modifier.height(12.dp))
                            Text("Fetching billing…", color = GrokifyColors.TextMuted, fontSize = 13.sp)
                        }
                    }
                }
                is LoadState.Error -> {
                    AnalyzerCard(accent = GrokifyColors.GlowRose) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = GrokifyColors.GlowRose)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Could not load usage",
                                color = GrokifyColors.GlowRose,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                        s.hint?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = GrokifyColors.TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { refresh() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GrokifyColors.GlowAmber.copy(alpha = 0.2f),
                                    contentColor = GrokifyColors.GlowAmber,
                                ),
                            ) { Text("Retry") }
                            TextButton(onClick = {
                                openUrl(context, CONSOLE_MGMT_KEYS)
                            }) {
                                Text("Management Keys", color = GrokifyColors.GlowCyan)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    KeyHelpCard(context)
                }
                is LoadState.Ready -> {
                    SnapshotBody(context = context, data = s.data, onRefresh = { refresh() })
                }
            }
        }
    }
}

@Composable
private fun SnapshotBody(
    context: Context,
    data: SpaceXaiUsageSnapshot,
    onRefresh: () -> Unit,
) {
    val balance = data.prepaidBalanceCents
    val balanceUsd = balance?.let { centsToUsdDisplay(it) }
    val spendUsd = data.periodSpendCents?.let { centsToUsdDisplay(it) }
    val usedUsd = data.prepaidCreditsUsedCents?.let { centsToUsdDisplay(it) }
    val softUsd = data.softLimitCents?.let { centsToUsdDisplay(it) }
    val hardUsd = data.hardLimitCents?.let { centsToUsdDisplay(it) }

    AnalyzerCard(accent = GrokifyColors.GlowAmber) {
        Text("PREPAID CREDITS", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowAmber)
        Spacer(Modifier.height(6.dp))
        Text(
            balanceUsd ?: "—",
            color = GrokifyColors.TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Remaining balance (team)",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        if (data.periodYear != null && data.periodMonth != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Billing period ${data.periodYear}-${data.periodMonth.toString().padStart(2, '0')}",
                color = GrokifyColors.TextDim,
                fontSize = 11.sp,
            )
        }
        data.keyName?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text("Key · $it", color = GrokifyColors.TextDim, fontSize = 11.sp)
        }
        Text(
            "Team ${data.teamId.take(8)}…",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }

    Spacer(Modifier.height(12.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniStat(
            modifier = Modifier.weight(1f),
            title = "Period spend",
            value = spendUsd ?: "—",
            tint = GrokifyColors.GlowRose,
        )
        MiniStat(
            modifier = Modifier.weight(1f),
            title = "Credits used",
            value = usedUsd ?: "—",
            tint = GrokifyColors.GlowViolet,
        )
    }

    Spacer(Modifier.height(10.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniStat(
            modifier = Modifier.weight(1f),
            title = "Soft limit",
            value = softUsd ?: "—",
            tint = GrokifyColors.GlowCyan,
        )
        MiniStat(
            modifier = Modifier.weight(1f),
            title = "Hard limit",
            value = hardUsd ?: "—",
            tint = GrokifyColors.GlowMint,
        )
    }

    // Limit utilization bar when we have spend + soft limit > 0
    val soft = data.softLimitCents
    val spend = data.periodSpendCents
    if (soft != null && soft > 0 && spend != null) {
        val ratio = (abs(spend).toFloat() / soft.toFloat()).coerceIn(0f, 1f)
        Spacer(Modifier.height(12.dp))
        AnalyzerCard(accent = GrokifyColors.GlowCyan) {
            Text("SOFT LIMIT USE", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowCyan)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    ratio >= 0.9f -> GrokifyColors.GlowRose
                    ratio >= 0.7f -> GrokifyColors.GlowAmber
                    else -> GrokifyColors.GlowMint
                },
                trackColor = GrokifyColors.PanelBorder,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(ratio * 100).toInt()}% of soft postpaid limit",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }

    if (data.usageByModel.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AnalyzerCard(accent = GrokifyColors.GlowViolet) {
            Text("USAGE · LAST 7 DAYS", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowViolet)
            Spacer(Modifier.height(4.dp))
            Text("By product / model (USD)", color = GrokifyColors.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            val maxAmt = data.usageByModel.maxOf { it.amountUsd }.coerceAtLeast(0.0001)
            data.usageByModel.take(12).forEach { bucket ->
                val frac = (bucket.amountUsd / maxAmt).toFloat().coerceIn(0f, 1f)
                Text(
                    bucket.label.ifBlank { "Unknown" },
                    color = GrokifyColors.TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(GrokifyColors.PanelBorder),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(8.dp)
                            .background(GrokifyColors.GlowViolet.copy(alpha = 0.75f)),
                    )
                }
                Text(
                    formatUsd(bucket.amountUsd),
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (data.dailySpend.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AnalyzerCard(accent = GrokifyColors.GlowMint) {
            Text("DAILY SPEND", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowMint)
            Spacer(Modifier.height(8.dp))
            data.dailySpend.forEach { pt ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(pt.day, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    Text(
                        formatUsd(pt.amountUsd),
                        color = GrokifyColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (data.prepaidChanges.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AnalyzerCard(accent = GrokifyColors.GlowBlue) {
            Text("BALANCE HISTORY", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowBlue)
            Spacer(Modifier.height(8.dp))
            data.prepaidChanges.take(15).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            humanOrigin(row.origin),
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        val sub = listOfNotNull(
                            row.createTime?.let { formatIsoShort(it) },
                            row.invoiceNumber?.takeIf { it.isNotBlank() }?.let { "inv $it" },
                            row.status?.takeIf { it.isNotBlank() && it != "SUCCEEDED" },
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, color = GrokifyColors.TextDim, fontSize = 11.sp)
                        }
                    }
                    Text(
                        centsToUsdSigned(row.amountCents, row.origin),
                        color = originColor(row.origin),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    data.warnings.forEach { w ->
        Spacer(Modifier.height(10.dp))
        Text("· $w", color = GrokifyColors.GlowAmber, fontSize = 11.sp)
    }

    Spacer(Modifier.height(12.dp))
    AnalyzerCard(accent = GrokifyColors.PanelBorder) {
        Text("LINKS", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.TextDim)
        Spacer(Modifier.height(6.dp))
        LinkRow("Billing / top-up", CONSOLE_BILLING) { openUrl(context, CONSOLE_BILLING) }
        LinkRow("Usage explorer", CONSOLE_USAGE) { openUrl(context, CONSOLE_USAGE) }
        LinkRow("Management keys", CONSOLE_MGMT_KEYS) { openUrl(context, CONSOLE_MGMT_KEYS) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Fetched ${formatLocalTime(data.fetchedAtMs)}. " +
                "Not affiliated with SpaceXAI — your key talks to management-api.x.ai only.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRefresh) {
            Text("Refresh now", color = GrokifyColors.GlowAmber)
        }
    }
}

@Composable
private fun KeyHelpCard(context: Context) {
    AnalyzerCard(accent = GrokifyColors.GlowViolet) {
        Text("KEY SETUP", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowViolet)
        Spacer(Modifier.height(6.dp))
        Text(
            "Settings → SpaceXAI Management key\n" +
                "Vault id: spacexai_management_key\n\n" +
                "• This app: console.x.ai → Management Keys (billing read)\n" +
                "• Voice TTS (Spotify DJ): separate inference API key (spacexai_api_key)\n\n" +
                "Paste a Management Key to analyze prepaid balance and spend. " +
                "Inference keys alone will fail validation.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { openUrl(context, CONSOLE_MGMT_KEYS) }) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                null,
                modifier = Modifier.size(16.dp),
                tint = GrokifyColors.GlowCyan,
            )
            Spacer(Modifier.width(6.dp))
            Text("Open Management Keys", color = GrokifyColors.GlowCyan)
        }
    }
}

@Composable
private fun AnalyzerCard(
    accent: Color,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun MiniStat(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    tint: Color,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(title.uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = GrokifyColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = GrokifyColors.GlowCyan, fontSize = 13.sp)
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                null,
                tint = GrokifyColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun fetchUsageSnapshot(apiKey: String): LoadState {
    return try {
        val validation = getJson("$MANAGEMENT_BASE/auth/management-keys/validation", apiKey)
            ?: return LoadState.Error(
                message = "Management API rejected the key",
                hint = "Use a Management Key (not a regular inference API key). " +
                    "console.x.ai → Management Keys. Ensure the account has billing read permission.",
            )
        if (validation.has("error") || validation.has("code") && validation.optInt("code") >= 400) {
            return LoadState.Error(
                message = validation.optString("error", validation.optString("message", "Unauthorized")),
                hint = "Paste a Management Key with billing access into Settings → " +
                    "SpaceXAI Management key (spacexai_management_key).",
            )
        }
        val teamId = validation.optString("teamId")
            .ifBlank { validation.optString("scopeId") }
            .ifBlank { validation.optString("scope_id") }
            .trim()
        if (teamId.isEmpty()) {
            return LoadState.Error(
                message = "Could not resolve team from key",
                hint = "Key validated but teamId/scopeId missing. Check Management Key scope.",
            )
        }
        val keyName = validation.optString("name").takeIf { it.isNotBlank() }
        val warnings = mutableListOf<String>()

        val balanceJson = getJson("$MANAGEMENT_BASE/v1/billing/teams/$teamId/prepaid/balance", apiKey)
        val prepaidCents = balanceJson?.let { parseCents(it.opt("total")) }
        val changes = balanceJson?.optJSONArray("changes")?.let { parseChanges(it) }.orEmpty()
        if (balanceJson == null) warnings += "Prepaid balance endpoint unavailable for this key"

        val previewJson = getJson(
            "$MANAGEMENT_BASE/v1/billing/teams/$teamId/postpaid/invoice/preview",
            apiKey,
        )
        var periodYear: Int? = null
        var periodMonth: Int? = null
        var periodSpend: Long? = null
        var prepaidUsed: Long? = null
        if (previewJson != null) {
            val cycle = previewJson.optJSONObject("billingCycle")
            periodYear = cycle?.optInt("year")?.takeIf { it > 0 }
            periodMonth = cycle?.optInt("month")?.takeIf { it > 0 }
            val core = previewJson.optJSONObject("coreInvoice")
            periodSpend = core?.let {
                parseCents(it.opt("amountAfterVat"))
                    ?: parseCents(it.opt("amountBeforeVat"))
                    ?: parseCents(it.optJSONObject("totalWithCorr"))
            }
            prepaidUsed = core?.let { parseCents(it.opt("prepaidCreditsUsed") ?: it.optJSONObject("prepaidCreditsUsed")) }
        } else {
            warnings += "Period invoice preview unavailable"
        }

        val limitsJson = getJson(
            "$MANAGEMENT_BASE/v1/billing/teams/$teamId/postpaid/spending-limits",
            apiKey,
        )
        var soft: Long? = null
        var hard: Long? = null
        if (limitsJson != null) {
            val sl = limitsJson.optJSONObject("spendingLimits")
            soft = parseCents(sl?.opt("effectiveSl") ?: sl?.opt("softSl"))
            hard = parseCents(sl?.opt("effectiveHardSl") ?: sl?.opt("hardSlOverride"))
        } else {
            warnings += "Spending limits unavailable"
        }

        val (byModel, daily) = fetchUsageSeries(teamId, apiKey, warnings)

        LoadState.Ready(
            SpaceXaiUsageSnapshot(
                teamId = teamId,
                keyName = keyName,
                prepaidBalanceCents = prepaidCents,
                prepaidChanges = changes,
                periodYear = periodYear,
                periodMonth = periodMonth,
                periodSpendCents = periodSpend,
                prepaidCreditsUsedCents = prepaidUsed,
                softLimitCents = soft,
                hardLimitCents = hard,
                usageByModel = byModel,
                dailySpend = daily,
                warnings = warnings,
            ),
        )
    } catch (e: Exception) {
        LoadState.Error(
            message = e.message?.take(200) ?: "Network error",
            hint = "Check connectivity and that the Management Key is valid.",
        )
    }
}

private fun fetchUsageSeries(
    teamId: String,
    apiKey: String,
    warnings: MutableList<String>,
): Pair<List<UsageBucket>, List<DailySpendPoint>> {
    val cal = Calendar.getInstance()
    val end = cal.clone() as Calendar
    end.add(Calendar.DAY_OF_MONTH, 1)
    val start = cal.clone() as Calendar
    start.add(Calendar.DAY_OF_MONTH, -6)
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val startStr = fmt.format(start.time).replace(Regex(" \\d{2}:\\d{2}:\\d{2}$"), " 00:00:00")
    val endStr = fmt.format(end.time).replace(Regex(" \\d{2}:\\d{2}:\\d{2}$"), " 00:00:00")
    val tz = TimeZone.getDefault().id

    fun analyticsBody(groupBy: List<String>, timeUnit: String): JSONObject {
        return JSONObject()
            .put(
                "analyticsRequest",
                JSONObject()
                    .put(
                        "timeRange",
                        JSONObject()
                            .put("startTime", startStr)
                            .put("endTime", endStr)
                            .put("timezone", tz),
                    )
                    .put("timeUnit", timeUnit)
                    .put(
                        "values",
                        JSONArray().put(
                            JSONObject()
                                .put("name", "usd")
                                .put("aggregation", "AGGREGATION_SUM"),
                        ),
                    )
                    .put("groupBy", JSONArray(groupBy))
                    .put("filters", JSONArray()),
            )
    }

    val byModel = mutableListOf<UsageBucket>()
    val daily = mutableListOf<DailySpendPoint>()

    val modelJson = postJson(
        "$MANAGEMENT_BASE/v1/billing/teams/$teamId/usage",
        apiKey,
        analyticsBody(listOf("description"), "TIME_UNIT_NONE"),
    )
    if (modelJson != null) {
        val series = modelJson.optJSONArray("timeSeries") ?: JSONArray()
        for (i in 0 until series.length()) {
            val s = series.optJSONObject(i) ?: continue
            val label = s.optJSONArray("groupLabels")?.optString(0)
                ?: s.optJSONArray("group")?.optString(0)
                ?: "Unknown"
            val points = s.optJSONArray("dataPoints") ?: JSONArray()
            var sum = 0.0
            for (j in 0 until points.length()) {
                val vals = points.optJSONObject(j)?.optJSONArray("values") ?: continue
                sum += vals.optDouble(0, 0.0)
            }
            if (sum > 0) byModel.add(UsageBucket(label, sum))
        }
        byModel.sortByDescending { it.amountUsd }
    } else {
        warnings += "Usage-by-model unavailable"
    }

    val dayJson = postJson(
        "$MANAGEMENT_BASE/v1/billing/teams/$teamId/usage",
        apiKey,
        analyticsBody(emptyList(), "TIME_UNIT_DAY"),
    )
    if (dayJson != null) {
        val series = dayJson.optJSONArray("timeSeries") ?: JSONArray()
        val totals = linkedMapOf<String, Double>()
        for (i in 0 until series.length()) {
            val s = series.optJSONObject(i) ?: continue
            val points = s.optJSONArray("dataPoints") ?: JSONArray()
            for (j in 0 until points.length()) {
                val pt = points.optJSONObject(j) ?: continue
                val ts = pt.optString("timestamp").take(10)
                val v = pt.optJSONArray("values")?.optDouble(0, 0.0) ?: 0.0
                totals[ts] = (totals[ts] ?: 0.0) + v
            }
        }
        totals.entries.sortedBy { it.key }.forEach { (day, amt) ->
            daily.add(DailySpendPoint(day, amt))
        }
    } else {
        warnings += "Daily usage series unavailable"
    }

    return byModel to daily
}

private fun getJson(url: String, apiKey: String): JSONObject? {
    val req = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "application/json")
        .get()
        .build()
    return http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            if (resp.code == 401 || resp.code == 403) return null
            // Try parse error body for other failures
            return try {
                JSONObject(body).put("_http", resp.code)
            } catch (_: Exception) {
                null
            }
        }
        if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}

private fun postJson(url: String, apiKey: String, body: JSONObject): JSONObject? {
    val req = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
    return http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return null
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun parseCents(raw: Any?): Long? {
    if (raw == null || raw == JSONObject.NULL) return null
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        is JSONObject -> {
            val v = raw.opt("val") ?: raw.opt("value") ?: raw.opt("amount")
            parseCents(v)
        }
        else -> null
    }
}

private fun parseChanges(arr: JSONArray): List<BalanceChangeRow> {
    val out = ArrayList<BalanceChangeRow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val amount = parseCents(o.opt("amount")) ?: 0L
        out.add(
            BalanceChangeRow(
                origin = o.optString("changeOrigin", "UNKNOWN"),
                amountCents = amount,
                status = o.optString("topupStatus").takeIf { it.isNotBlank() },
                createTime = o.optString("createTime").ifBlank { o.optString("createTs") }
                    .takeIf { it.isNotBlank() },
                invoiceNumber = o.optString("invoiceNumber").takeIf { it.isNotBlank() },
            ),
        )
    }
    // Newest first when timestamps present
    return out.sortedByDescending { it.createTime.orEmpty() }
}

/**
 * Prepaid balances from the Management API are often stored as negative cents
 * (credits on account). Display remaining as absolute dollars.
 */
private fun centsToUsdDisplay(cents: Long): String {
    val dollars = abs(cents) / 100.0
    return currency.format(dollars)
}

private fun centsToUsdSigned(cents: Long, origin: String): String {
    // Docs: PURCHASE amount negative (add credits), SPEND positive (use credits).
    // Show purchases as +$X and spends as -$X for humans.
    val dollars = abs(cents) / 100.0
    val isCredit = when (origin.uppercase(Locale.US)) {
        "PURCHASE", "AUTO_PURCHASE", "REFUND" -> true
        "SPEND" -> false
        "MANUAL" -> cents <= 0
        else -> cents <= 0
    }
    val bare = currency.format(dollars)
    return if (isCredit) "+$bare" else "-$bare"
}

private fun formatUsd(amount: Double): String = currency.format(amount)

private fun humanOrigin(origin: String): String = when (origin.uppercase(Locale.US)) {
    "PURCHASE" -> "Purchase"
    "AUTO_PURCHASE" -> "Auto top-up"
    "SPEND" -> "API spend"
    "REFUND" -> "Refund"
    "MANUAL" -> "Manual adjustment"
    else -> origin.replace('_', ' ').lowercase(Locale.US)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

private fun originColor(origin: String): Color = when (origin.uppercase(Locale.US)) {
    "SPEND" -> GrokifyColors.GlowRose
    "PURCHASE", "AUTO_PURCHASE", "REFUND" -> GrokifyColors.GlowMint
    else -> GrokifyColors.GlowAmber
}

private fun formatIsoShort(iso: String): String {
    return try {
        // 2025-02-24T15:28:02.308840Z → 2025-02-24 15:28
        iso.replace('T', ' ').take(16)
    } catch (_: Exception) {
        iso.take(19)
    }
}

private fun formatLocalTime(ms: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(ms)
}
