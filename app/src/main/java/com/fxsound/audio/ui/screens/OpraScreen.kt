package com.fxsound.audio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.opra.CatalogueEntry
import com.fxsound.audio.opra.HeadphoneEqPlan
import com.fxsound.audio.opra.OpraApplyResult
import com.fxsound.audio.opra.OpraProfileConverter
import com.fxsound.audio.opra.OpraRepository
import com.fxsound.audio.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * OPRA (Open Profiles for Revealing Audio) headphone-tuning catalogue.
 *
 * Attribution requirements from the OPRA README licence section: the OPRA logo plus a
 * brief project description and link must be shown when browsing the database, the
 * CC BY-SA licence must be reachable, the app must say it modifies the curves, and the
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
            text = stringResource(R.string.opra_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.opra_subtitle),
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
                    Image(
                        painter = painterResource(R.drawable.opra_logo),
                        contentDescription = stringResource(R.string.opra_attribution_title),
                        modifier = Modifier.size(width = 88.dp, height = 34.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.opra_attribution_title),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.opra_attribution_license,
                                OpraRepository.LICENSE_NAME
                            ),
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                        Text(
                            text = OpraRepository.MODIFICATION_NOTICE,
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(OpraRepository.OPRA_URL)
                        )
                        runCatching { context.startActivity(intent) }
                    }) {
                        Text(stringResource(R.string.opra_project_link), color = FxRedPrimary, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(OpraRepository.LICENSE_URL)
                        )
                        runCatching { context.startActivity(intent) }
                    }) {
                        Text(stringResource(R.string.opra_license_link), color = FxRedPrimary, fontSize = 11.sp)
                    }
                    if (loadState is OpraRepository.LoadState.Ready) {
                        TextButton(onClick = {
                            hapticsManager.performPresetSelected()
                            scope.launch { opra?.loadOrRefresh(forceNetwork = true) }
                        }) {
                            Text(stringResource(R.string.opra_check_updates), color = TextSecondary, fontSize = 11.sp)
                        }
                        TextButton(onClick = {
                            hapticsManager.performPresetSelected()
                            scope.launch { opra?.deleteDownloadedData() }
                        }) {
                            Text(stringResource(R.string.opra_delete_data), color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (val ls = loadState) {
            is OpraRepository.LoadState.Loading -> Text(
                when (ls.phase) {
                    OpraRepository.LoadState.Loading.Phase.DOWNLOADING ->
                        stringResource(
                            if (ls.firstLoad) R.string.opra_downloading else R.string.opra_checking_updates
                        )
                    OpraRepository.LoadState.Loading.Phase.INDEXING ->
                        stringResource(R.string.opra_indexing)
                },
                color = TextSecondary, fontSize = 12.sp
            )

            is OpraRepository.LoadState.Failed -> Text(
                ls.message, color = FxRedPrimary, fontSize = 12.sp
            )

            is OpraRepository.LoadState.Ready -> {
                ls.staleMessage?.let {
                    Text(it, color = AmberAccent, fontSize = 11.sp, lineHeight = 14.sp)
                }

                // Status chip for the current selection
                val plan = state.headphonePlan
                if (plan != null) {
                    Text(
                        text = stringResource(
                            if (state.isHeadphonePlanApplied) R.string.opra_status_applied
                            else R.string.opra_status_pending,
                            plan.author,
                            plan.details ?: plan.profileId
                        ),
                        color = if (state.isHeadphonePlanApplied) Color(0xFF4CAF50) else AmberAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        hapticsManager.performPresetSelected()
                        opra?.clearSelection()
                    }) {
                        Text(
                            stringResource(R.string.opra_remove_profile),
                            color = TextTertiary, fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(stringResource(R.string.opra_search_hint), color = TextTertiary, fontSize = 13.sp)
                    },
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
                                    stringResource(R.string.opra_search_cap),
                                    color = TextTertiary, fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        if (hasAmbiguousFamilies(results)) {
                            item {
                                Text(
                                    stringResource(R.string.opra_ambiguous_warning),
                                    color = AmberAccent, fontSize = 11.sp, lineHeight = 14.sp,
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
                                    stringResource(
                                        R.string.opra_row_meta,
                                        entry.summaries.size,
                                        entry.product.subtype ?: "headphones"
                                    ),
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                                OpraDivider()
                            }
                        }
                        if (results.isEmpty()) {
                            item {
                                Text(
                                    if (searchQuery.isBlank()) stringResource(R.string.opra_no_cache)
                                    else stringResource(R.string.opra_no_match, searchQuery),
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
                            currentPlan = state.headphonePlan,
                            onApply = { result -> applyMessage = result.message },
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

/** Same product family (name without the variant suffix) appearing more than once. */
private fun hasAmbiguousFamilies(results: List<CatalogueEntry>): Boolean {
    if (results.size < 2) return false
    val families = results.map { it.product.name.substringBefore(" (").trim().lowercase() }
    return families.size != families.toSet().size
}

@Composable
fun OpraDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x11FFFFFF))
    )
}

/**
 * Detail pane: profile list for a product with per-profile preview, creator attribution
 * (more prominent than OPRA's, per the licence FAQ), per-band trims and Apply.
 */
@Composable
fun OpraProductDetail(
    entry: CatalogueEntry,
    opra: OpraRepository,
    currentPlan: HeadphoneEqPlan?,
    onApply: (OpraApplyResult) -> Unit,
    onBack: () -> Unit,
    applyMessage: String?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.opra_back), color = TextSecondary, fontSize = 12.sp)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(entry.summaries, key = { it.id }) { summary ->
                val profile = remember(summary.id) { opra.readProfile(summary.id) }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    // Creator credit FIRST and larger — licence wants it more prominent.
                    Text(
                        stringResource(R.string.opra_profile_by, summary.author),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    summary.details?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    summary.link?.takeIf { it.isNotBlank() }?.let { link ->
                        val ctx = LocalContext.current
                        TextButton(
                            contentPadding = PaddingValues(0.dp),
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(link)
                                        )
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(R.string.opra_measurement_source), color = FxRedPrimary, fontSize = 11.sp)
                        }
                    }
                    Text(
                        stringResource(R.string.opra_profile_meta, summary.preampDb.toString(), summary.bandCount) +
                            (if (summary.shelfBandCount > 0)
                                stringResource(R.string.opra_profile_meta_shelf, summary.shelfBandCount) else "") +
                            (if (summary.unsupportedBandCount > 0)
                                stringResource(R.string.opra_profile_meta_unsupported, summary.unsupportedBandCount) else ""),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )

                    if (profile != null) {
                        ProfileControls(
                            profileId = profile.id,
                            summaryId = summary.id,
                            plan = remember(summary.id, profile) { OpraProfileConverter.convert(profile) },
                            currentPlan = currentPlan,
                            masterAdjustDb = opra.adjustDb,
                            onApply = { offsets, adjust -> onApply(opra.selectProfile(profile, adjust, offsets)) }
                        )
                    } else {
                        Text(
                            stringResource(R.string.opra_profile_unparseable),
                            color = FxRedPrimary, fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OpraDivider()
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
private fun ProfileControls(
    profileId: String,
    summaryId: String,
    plan: HeadphoneEqPlan?,
    currentPlan: HeadphoneEqPlan?,
    masterAdjustDb: Float,
    onApply: (List<Float>, Float) -> Unit
) {
    if (plan == null) {
        Text(
            stringResource(R.string.opra_profile_unrenderable),
            color = FxRedPrimary, fontSize = 11.sp
        )
        return
    }

    val applied = currentPlan?.takeIf { it.profileId == profileId }
    var adjustDb by remember(summaryId) {
        mutableStateOf(if (applied != null) masterAdjustDb else 0f)
    }
    var offsets by remember(summaryId) {
        mutableStateOf(
            if (applied != null && applied.userBandOffsets.size == plan.bands.size) {
                applied.userBandOffsets
            } else {
                List(plan.bands.size) { 0f }
            }
        )
    }
    var showBandTrims by remember(summaryId) { mutableStateOf(false) }

    HeadphoneCurvePreview(
        plan = plan.copy(
            bands = plan.bands.mapIndexed { i, band ->
                band.copy(gainDb = (band.gainDb + (offsets.getOrNull(i) ?: 0f) + adjustDb).coerceIn(-20f, 20f))
            }
        ),
        modifier = Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp)
    )
    Text(
        stringResource(R.string.opra_ideal_preview),
        color = TextTertiary, fontSize = 11.sp
    )
    if (plan.maxShelfApproximationErrorDb >= 3f) {
        Text(
            stringResource(
                R.string.opra_approximation_error,
                String.format(Locale.ROOT, "%.1f", plan.maxShelfApproximationErrorDb)
            ),
            color = AmberAccent, fontSize = 11.sp, lineHeight = 14.sp
        )
    }
    Text(
        plan.rendererDisclosure,
        color = TextTertiary, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = Modifier.padding(top = 4.dp)
    )

    // Master level trim.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.opra_level), color = TextSecondary, fontSize = 11.sp)
        Slider(
            value = adjustDb,
            onValueChange = { adjustDb = it },
            valueRange = -6f..6f,
            steps = 11,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = FxRedPrimary,
                activeTrackColor = FxRedPrimary,
                inactiveTrackColor = BorderSubtle
            )
        )
        Text(
            "${if (adjustDb >= 0) "+" else ""}${String.format(Locale.ROOT, "%.1f", adjustDb)} dB",
            color = TextPrimary, fontSize = 11.sp
        )
    }

    // Per-band trims (E4).
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            contentPadding = PaddingValues(0.dp),
            onClick = { showBandTrims = !showBandTrims }
        ) {
            Text(
                (if (showBandTrims) "▾ " else "▸ ") + stringResource(R.string.opra_per_band_title),
                color = TextSecondary, fontSize = 11.sp
            )
        }
        if (showBandTrims) {
            TextButton(onClick = { offsets = List(plan.bands.size) { 0f } }) {
                Text(stringResource(R.string.opra_per_band_reset), color = TextTertiary, fontSize = 11.sp)
            }
        }
    }
    if (showBandTrims) {
        Text(
            stringResource(R.string.opra_per_band_hint),
            color = TextTertiary, fontSize = 11.sp, lineHeight = 14.sp
        )
        plan.bands.forEachIndexed { index, band ->
            val offset = offsets.getOrNull(index) ?: 0f
            val label = stringResource(
                R.string.opra_band_label,
                String.format(Locale.ROOT, "%.0f", band.frequencyHz)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (band.approximationErrorDb != null) {
                    Text(
                        stringResource(R.string.opra_shelf_tag),
                        color = TextTertiary, fontSize = 11.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                TextButton(
                    onClick = {
                        offsets = offsets.toMutableList().also {
                            it[index] = (offset - 0.5f).coerceIn(-6f, 6f)
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Decrease $label by 0.5 dB"
                    }
                ) { Text("−", color = FxRedPrimary, fontSize = 16.sp) }
                Text(
                    "${if (offset >= 0) "+" else ""}${String.format(Locale.ROOT, "%.1f", offset)}",
                    color = TextPrimary, fontSize = 11.sp,
                    modifier = Modifier.width(38.dp), textAlign = TextAlign.Center
                )
                TextButton(
                    onClick = {
                        offsets = offsets.toMutableList().also {
                            it[index] = (offset + 0.5f).coerceIn(-6f, 6f)
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Increase $label by 0.5 dB"
                    }
                ) { Text("+", color = FxRedPrimary, fontSize = 16.sp) }
            }
        }
    }

    OutlinedButton(
        onClick = { onApply(offsets, adjustDb) },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = FxRedPrimary
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, FxRedPrimary)
    ) {
        Text(stringResource(R.string.opra_apply))
    }
}

/**
 * Analytic magnitude preview of the converted plan's peaking (RBJ) biquads at 44.1 kHz,
 * drawn as a log-frequency response curve. This is a filter-graph preview of an ideal
 * peaking model, not a measured result — the device renderer chooses its own bandwidth.
 */
@Composable
fun HeadphoneCurvePreview(plan: HeadphoneEqPlan, modifier: Modifier = Modifier) {
    val curve = remember(plan) {
        val freqs = (0..200).map { i ->
            20.0 * (1000.0.pow(i / 200.0 * (ln(20000.0 / 20.0) / ln(10.0))))
        }
        freqs.map { f -> f to magnitude2dB(plan, f) }
    }
    val description = stringResource(R.string.opra_curve_description)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(10.dp))
            .semantics { contentDescription = description }
    ) {
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
private fun magnitude2dB(plan: HeadphoneEqPlan, f: Double): Double {
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
        total += 10 * ln(mag.coerceIn(Double.MIN_VALUE, Double.MAX_VALUE)) / ln(10.0)
    }
    return total
}
