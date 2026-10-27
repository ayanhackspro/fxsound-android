package com.fxsound.audio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.opra.CatalogueEntry
import com.fxsound.audio.opra.OpraApplyResult
import com.fxsound.audio.opra.OpraProfileConverter
import com.fxsound.audio.opra.OpraRepository
import com.fxsound.audio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.abs

/**
 * OPRA (Open Profiles for Revealing Audio) headphone-tuning catalogue.
 *
 * Attribution requirements from the OPRA README licence section: the OPRA logo plus a
 * brief project description and link must be shown when browsing the database, and the
 * preset creator must be credited more prominently than OPRA itself.
 */
@Composable
fun OpraScreen(
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier
) {
    val state by FxSoundRepository.uiState.collectAsState()
    val opra = FxSoundRepository.opraInstance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<CatalogueEntry?>(null) }
    var applyMessage by remember { mutableStateOf<String?>(null) }

    // Kick the first load on first composition (stale cached copy OK; refresh separate).
    LaunchedEffect(opra) {
        opra?.loadOrRefresh(forceNetwork = false)
    }

    val loadState = if (opra != null) opra.loadState.collectAsState().value else OpraRepository.LoadState.NotLoaded
    val results = if (opra != null) opra.search(searchQuery) else emptyList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "HEADPHONE TUNING",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Community-measured EQ correction curves for your exact headphone model.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(12.dp))

        // ---- OPRA attribution card (README licence requirement) -----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.opra_logo),
                        contentDescription = "OPRA logo",
                        modifier = Modifier.size(width = 88.dp, height = 34.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Powered by OPRA — Open Profiles for Revealing Audio",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Data licensed ${OpraRepository.LICENSE_NAME}; each profile credits its creator.",
                            color = TextTertiary,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OpraRepository.OPRA_URL))
                        runCatching { context.startActivity(intent) }
                    }) {
                        Text("OPRA project ↗", color = FxRedPrimary, fontSize = 11.sp)
                    }
                    val refreshState = loadState
                    if (refreshState is OpraRepository.LoadState.Ready) {
                        TextButton(onClick = {
                            hapticsManager.performPresetSelected()
                            scope.launch { opra?.loadOrRefresh(forceNetwork = true) }
                        }) {
                            Text("Check for updates", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (val ls = loadState) {
            is OpraRepository.LoadState.Loading -> Text(
                if (ls.firstLoad) "Downloading OPRA database (about 12 MB, once)…" else "Refreshing…",
                color = TextSecondary, fontSize = 12.sp
            )
            is OpraRepository.LoadState.Failed -> Text(
                ls.message, color = FxRedPrimary, fontSize = 12.sp
            )
            is OpraRepository.LoadState.Ready -> {
                // Status chip for the current selection
                val plan = state.headphonePlan
                if (plan != null) {
                    Text(
                        if (state.isHeadphonePlanApplied)
                            "APPLIED · ${plan.author} — ${plan.details ?: plan.profileId}"
                        else
                            "PENDING · ${plan.author} — ${plan.details ?: plan.profileId} (applies when an audio session attaches)",
                        color = if (state.isHeadphonePlanApplied) Color(0xFF4CAF50) else AmberAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        hapticsManager.performPresetSelected()
                        opra?.clearSelection()
                    }) {
                        Text("Remove headphone profile", color = TextTertiary, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search headphones or brand…", color = TextTertiary, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = BorderSubtle,
                        focusedBorderColor = FxRedPrimary,
                        cursorColor = FxRedPrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(8.dp))

                if (selectedEntry == null) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (results.size >= 100) {
                            item {
                                Text(
                                    "Showing the first 100 matches — refine the search to narrow it down.",
                                    color = TextTertiary, fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        items(results, key = { it.product.id }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        hapticsManager.performPresetSelected()
                                        selectedEntry = entry
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    "${entry.product.vendorName} ${entry.product.name}",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${entry.summaries.size} profile(s) · ${entry.product.subtype ?: "headphones"}",
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                                HorizontalDivider()
                            }
                        }
                        if (results.isEmpty()) {
                            item {
                                Text(
                                    if (searchQuery.isBlank()) "No profiles cached yet — check connectivity."
                                    else "No model matches \"${searchQuery}\". OPRA only carries measured models; nothing is invented.",
                                    color = TextTertiary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        OpraProductDetail(
                            entry = selectedEntry!!,
                            opra = opra!!,
                            enabled = true,
                            onApply = { result ->
                                applyMessage = result.message
                            },
                            onBack = {
                                selectedEntry = null
                                applyMessage = null
                            },
                            applyMessage = applyMessage
                        )
                    }
                }
            }
            else -> {}
        }
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x11FFFFFF))
    )
}

/**
 * Detail pane: profile list for a product with per-profile preview, creator attribution
 * (more prominent than OPRA's, per the licence FAQ), and the Apply control.
 */
@Composable
fun OpraProductDetail(
    entry: CatalogueEntry,
    opra: OpraRepository,
    enabled: Boolean,
    onApply: (OpraApplyResult) -> Unit,
    onBack: () -> Unit,
    applyMessage: String?
) {
    var adjustDb by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← All headphones", color = TextSecondary, fontSize = 12.sp) }

        // Global adjustment applied on top of the selected profile, persisted with it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Profile level", color = TextSecondary, fontSize = 11.sp)
            androidx.compose.material3.Slider(
                value = adjustDb,
                onValueChange = { adjustDb = it },
                valueRange = -6f..6f,
                steps = 11,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = FxRedPrimary,
                    activeTrackColor = FxRedPrimary,
                    inactiveTrackColor = BorderSubtle
                )
            )
            Text(
                "${if (adjustDb >= 0) "+" else ""}${String.format("%.1f", adjustDb)} dB",
                color = TextPrimary, fontSize = 11.sp
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(entry.summaries, key = { it.id }) { summary ->
                val profile = remember(summary.id) { opra.readProfile(summary.id) }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    // Creator credit FIRST and larger — licence wants it more prominent.
                    Text(
                        "Profile by ${summary.author}",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    summary.details?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                    summary.link?.takeIf { it.isNotBlank() }?.let { link ->
                        val ctx = LocalContext.current
                        TextButton(
                            contentPadding = PaddingValues(0.dp),
                            onClick = {
                                runCatching { ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                ) }
                            }
                        ) {
                            Text("Measurement source ↗", color = FxRedPrimary, fontSize = 10.sp)
                        }
                    }
                    Text(
                        "Preamp ${summary.preampDb} dB · ${summary.bandCount} bands" +
                            (if (summary.shelfBandCount > 0) " · ${summary.shelfBandCount} shelf (approximated)" else "") +
                            (if (summary.unsupportedBandCount > 0) " · ${summary.unsupportedBandCount} unsupported" else ""),
                        color = TextTertiary,
                        fontSize = 10.sp
                    )

                    if (profile != null) {
                        HeadphoneCurvePreview(
                            plan = OpraProfileConverter.convert(profile) ?: return@items,
                            modifier = Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp)
                        )
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { onApply(opra.selectProfile(profile, adjustmentDb = adjustDb)) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = FxRedPrimary
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, FxRedPrimary)
                        ) {
                            Text("APPLY PROFILE")
                        }
                    } else {
                        Text(
                            "This profile could not be parsed — it is shown but not offered.",
                            color = FxRedPrimary, fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                }
            }
            applyMessage?.let { msg ->
                item {
                    Text(msg, color = AmberAccent, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x11FFFFFF)))
}

/**
 * Analytic magnitude preview of the converted plan's peaking (RBJ) biquads at 44.1 kHz,
 * drawn as a log-frequency response curve. This is a filter-graph preview, not a
 * measured result.
 */
@Composable
fun HeadphoneCurvePreview(plan: com.fxsound.audio.opra.HeadphoneEqPlan, modifier: Modifier = Modifier) {
    val curve = remember(plan) {
        val freqs = (0..200).map { i ->
            20.0 * (1000.0.pow(i / 200.0 * (ln(20000.0 / 20.0) / ln(10.0))))
        }
        freqs.map { f -> f to magnitude2dB(plan, f) }
    }

    Canvas(modifier = modifier.fillMaxWidth().background(DarkSurfaceElevated, RoundedCornerShape(10.dp))) {
        val top = 12f
        val bottom = size.height - 12f
        val yFor: (Double) -> Float = { db -> (bottom - ((db + 18.0) / 36.0) * (bottom - top)).toFloat() }
        val xFor: (Double) -> Float = { f ->
            val t = (ln(f / 20.0) / ln(20000.0 / 20.0))
            size.width * t.toFloat()
        }
        // grid
        for (db in listOf(-12, -6, 0, 6, 12, 18)) {
            drawLine(Color(0x22FFFFFF), Offset(0f, yFor(db.toDouble())), Offset(size.width, yFor(db.toDouble())), 1f)
        }
        val path = Path()
        curve.forEachIndexed { i, (f, db) ->
            val p = Offset(xFor(f), yFor(db))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Color(0xFFE53935), style = Stroke(width = 3f))
        // 0 dB reference
        drawLine(Color(0x44FFFFFF), Offset(0f, yFor(0.0)), Offset(size.width, yFor(0.0)), 1f)
    }
}

/** Sum of RBJ peaking EQ magnitude responses (dB) for the converted plan at fs=44100. */
private fun magnitude2dB(plan: com.fxsound.audio.opra.HeadphoneEqPlan, f: Double): Double {
    val fs = 44100.0
    var total = 0.0
    for (band in plan.bands) {
        val f0 = band.frequencyHz.toDouble()
        if (f0 <= 0 || fs <= 2 * f0) continue
        val a = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosw = cos(w0)
        val sinw = sin(w0)
        val q = band.q.toDouble().coerceAtLeast(0.1)
        val alpha = sinw / (2.0 * q)
        // RBJ peaking biquad coefficients
        val b0 = 1 + alpha * a
        val b1 = -2 * cosw
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosw
        val a2 = 1 - alpha / a
        val w = 2.0 * PI * f / fs
        val cosw2 = cos(w)
        val sinw2 = sin(w)
        val numRe = b0 + b1 * cosw2 + b2 * (2 * cosw2 * cosw2 - 1)
        val numIm = -(b1 * sinw2 + b2 * 2 * sinw2 * cosw2)
        val denRe = a0 + a1 * cosw2 + a2 * (2 * cosw2 * cosw2 - 1)
        val denIm = -(a1 * sinw2 + a2 * 2 * sinw2 * cosw2)
        val mag = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        total += 10 * ln(mag.coerceIn(Double.MIN_VALUE, Double.MAX_VALUE)) / kotlin.math.ln(10.0)
    }
    return total
}
