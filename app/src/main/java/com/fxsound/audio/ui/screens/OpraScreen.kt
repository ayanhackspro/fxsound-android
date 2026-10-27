package com.fxsound.audio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.opra.CatalogueEntry
import com.fxsound.audio.opra.HeadphoneEqPlan
import com.fxsound.audio.opra.OpraApplyResult
import com.fxsound.audio.opra.OpraEqProfile
import com.fxsound.audio.opra.OpraProfileConverter
import com.fxsound.audio.opra.OpraRepository
import com.fxsound.audio.opra.OpraSearch
import com.fxsound.audio.ui.theme.AppliedGreen
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurface
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedDark
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.PendingAmber
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.launch

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

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        }
        Unit
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        val entry = selectedEntry
        if (entry == null) {
            CatalogueContent(
                loadState = loadState,
                results = results,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                plan = state.headphonePlan,
                planApplied = state.isHeadphonePlanApplied,
                onClearPlan = {
                    hapticsManager.performPresetSelected()
                    opra?.clearSelection()
                },
                onRefresh = {
                    hapticsManager.performPresetSelected()
                    scope.launch { opra?.loadOrRefresh(forceNetwork = true) }
                },
                onDelete = {
                    hapticsManager.performPresetSelected()
                    scope.launch { opra?.deleteDownloadedData() }
                },
                onOpenUrl = openUrl,
                onSelect = {
                    hapticsManager.performPresetSelected()
                    applyMessage = null
                    selectedEntry = it
                }
            )
        } else if (opra != null) {
            DetailContent(
                entry = entry,
                opra = opra,
                currentPlan = state.headphonePlan,
                planApplied = state.isHeadphonePlanApplied,
                applyMessage = applyMessage,
                onApply = { result -> applyMessage = result.message },
                onBack = {
                    selectedEntry = null
                    applyMessage = null
                }
            )
        }
    }
}

/** Browse state: header, licence attribution, search and the result list, all scrollable. */
@Composable
private fun CatalogueContent(
    loadState: OpraRepository.LoadState,
    results: List<CatalogueEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    plan: HeadphoneEqPlan?,
    planApplied: Boolean,
    onClearPlan: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelect: (CatalogueEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)
    ) {
        item("header") {
            Text(
                text = stringResource(R.string.opra_title),
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.opra_subtitle),
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(14.dp))
            AttributionCard(
                ready = loadState is OpraRepository.LoadState.Ready,
                onRefresh = onRefresh,
                onDelete = onDelete,
                onOpenUrl = onOpenUrl
            )
            Spacer(Modifier.height(14.dp))
        }

        when (loadState) {
            is OpraRepository.LoadState.Loading -> item("loading") {
                StateLine(
                    text = when (loadState.phase) {
                        OpraRepository.LoadState.Loading.Phase.DOWNLOADING ->
                            stringResource(
                                if (loadState.firstLoad) R.string.opra_downloading else R.string.opra_checking_updates
                            )
                        OpraRepository.LoadState.Loading.Phase.INDEXING ->
                            stringResource(R.string.opra_indexing)
                    },
                    color = TextSecondary
                )
            }

            is OpraRepository.LoadState.Failed -> item("failed") {
                StateLine(text = loadState.message, color = FxRedPrimary)
            }

            is OpraRepository.LoadState.Ready -> {
                loadState.staleMessage?.let { stale ->
                    item("stale") { StateLine(text = stale, color = PendingAmber) }
                }

                item("search") {
                    SearchField(query = query, onQueryChange = onQueryChange)
                    Spacer(Modifier.height(12.dp))
                }

                if (plan != null) {
                    item("plan") {
                        PlanStatusRow(plan = plan, applied = planApplied, onRemove = onClearPlan)
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (results.size >= 100) {
                    item("cap") {
                        Text(
                            stringResource(R.string.opra_search_cap),
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }

                if (query.isNotBlank() && OpraSearch.hasVariantFamilies(results)) {
                    item("ambiguous") {
                        Surface(
                            color = PendingAmber.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PendingAmber.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.opra_ambiguous_warning),
                                color = PendingAmber,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                items(results, key = { it.product.id }) { entry ->
                    ResultRow(entry = entry, onClick = { onSelect(entry) })
                }

                if (results.isEmpty()) {
                    item("empty") {
                        Text(
                            text = if (query.isBlank()) stringResource(R.string.opra_no_cache)
                            else stringResource(R.string.opra_no_match, query),
                            color = TextTertiary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 28.dp, start = 12.dp, end = 12.dp)
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

/**
 * Licence attribution (OPRA README requirement).
 *
 * Deliberately compact: the previous version put four text buttons in one unbounded row, so
 * "Delete downloaded data" wrapped into ~10 lines off-screen and stretched the card by
 * ~250 dp of dead space. Links live on their own line and the refresh action is an icon.
 */
@Composable
private fun AttributionCard(
    ready: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.opra_logo),
                    contentDescription = null,
                    modifier = Modifier.size(width = 52.dp, height = 20.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(TextSecondary)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.opra_attribution_title),
                    color = TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.opra_check_updates),
                        tint = TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.opra_attribution_license, OpraRepository.LICENSE_NAME),
                color = TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Text(
                text = OpraRepository.MODIFICATION_NOTICE,
                color = TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinkButton(
                    text = stringResource(R.string.opra_project_link),
                    onClick = { onOpenUrl(OpraRepository.OPRA_URL) }
                )
                Spacer(Modifier.width(14.dp))
                LinkButton(
                    text = stringResource(R.string.opra_license_link),
                    onClick = { onOpenUrl(OpraRepository.LICENSE_URL) }
                )
                Spacer(Modifier.weight(1f))
                if (ready) {
                    LinkButton(
                        text = stringResource(R.string.opra_delete_short),
                        color = TextTertiary,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkButton(
    text: String,
    color: Color = FxRedPrimary,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
        placeholder = {
            Text(stringResource(R.string.opra_search_hint), color = TextTertiary, fontSize = 13.sp)
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.opra_clear_search),
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = DarkSurfaceElevated,
            unfocusedContainerColor = DarkSurfaceElevated,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = FxRedPrimary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        )
    )
}

/** Current selection: what is applied (or pending a session) and a way to drop it. */
@Composable
private fun PlanStatusRow(plan: HeadphoneEqPlan, applied: Boolean, onRemove: () -> Unit) {
    val accent = if (applied) AppliedGreen else PendingAmber
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (applied) R.string.opra_applied_tag else R.string.opra_pending_tag
                    ),
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(
                        stringResource(R.string.opra_remove_short),
                        color = TextSecondary, fontSize = 11.sp
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.opra_status_credit,
                    plan.author,
                    plan.details ?: plan.profileId
                ),
                color = TextPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 14.dp, end = 8.dp)
            )
            if (!applied) {
                Text(
                    text = stringResource(R.string.opra_pending_hint),
                    color = TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultRow(entry: CatalogueEntry, onClick: () -> Unit) {
    val product = entry.product
    val type = product.subtype?.replace('_', ' ')?.ifBlank { null } ?: "headphones"
    // OPRA names some products as "(Brand) Model" and others as just "Model", so only
    // prepend the vendor when the name does not already carry it.
    val carriesVendor = product.vendorName.isNotBlank() &&
        product.name.contains(product.vendorName, ignoreCase = true)
    val title = when {
        product.name.isBlank() -> product.vendorName
        carriesVendor -> product.name
        else -> "${product.vendorName} ${product.name}".trim()
    }
    val profiles = pluralStringResource(
        R.plurals.opra_profile_count,
        entry.summaries.size,
        entry.summaries.size
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$profiles \u00B7 $type",
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = TextDisabled,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
    OpraDivider()
}

@Composable
private fun StateLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
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
private fun DetailContent(
    entry: CatalogueEntry,
    opra: OpraRepository,
    currentPlan: HeadphoneEqPlan?,
    planApplied: Boolean,
    applyMessage: String?,
    onApply: (OpraApplyResult) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.padding(start = 20.dp, top = 10.dp).height(28.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.opra_all_headphones), color = TextSecondary, fontSize = 12.sp)
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                text = entry.product.name.ifBlank { entry.product.vendorName },
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (entry.product.vendorName.isNotBlank() &&
                        !entry.product.name.contains(entry.product.vendorName, ignoreCase = true)
                    ) {
                        append(entry.product.vendorName).append(" \u00B7 ")
                    }
                    append(
                        pluralStringResource(
                            R.plurals.opra_profile_count,
                            entry.summaries.size,
                            entry.summaries.size
                        )
                    )
                },
                color = TextTertiary,
                fontSize = 11.sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
        ) {
            items(entry.summaries, key = { it.id }) { summary ->
                val profile = remember(summary.id) { opra.readProfile(summary.id) }
                val plan = remember(summary.id, profile) {
                    profile?.let { OpraProfileConverter.convert(it) }
                }
                val metaParts = mutableListOf(
                    stringResource(
                        R.string.opra_profile_meta,
                        summary.preampDb.toString(),
                        summary.bandCount
                    )
                )
                if (summary.shelfBandCount > 0) {
                    metaParts += stringResource(R.string.opra_profile_meta_shelf, summary.shelfBandCount)
                }
                if (summary.unsupportedBandCount > 0) {
                    metaParts += stringResource(
                        R.string.opra_profile_meta_unsupported,
                        summary.unsupportedBandCount
                    )
                }
                ProfileSection(
                    author = summary.author,
                    details = summary.details,
                    sourceUrl = summary.link,
                    // aapt strips leading whitespace from string resources, so the
                    // separators have to be added here rather than inside the strings.
                    meta = metaParts.joinToString(" \u00B7 "),
                    profile = profile,
                    plan = plan,
                    summaryId = summary.id,
                    currentPlan = currentPlan,
                    planApplied = planApplied,
                    masterAdjustDb = opra.adjustDb,
                    onApply = { offsets, adjust -> onApply(opra.selectProfile(profile!!, adjust, offsets)) }
                )
            }
            applyMessage?.let { msg ->
                item("apply-message") {
                    Text(
                        msg, color = PendingAmber, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSection(
    author: String,
    details: String?,
    sourceUrl: String?,
    meta: String,
    profile: OpraEqProfile?,
    plan: HeadphoneEqPlan?,
    summaryId: String,
    currentPlan: HeadphoneEqPlan?,
    planApplied: Boolean,
    masterAdjustDb: Float,
    onApply: (List<Float>, Float) -> Unit
) {
    // Selected is not the same as applied: the companion service only reports APPLIED
    // once it has actually attached the effect, so the pill must not appear on selection.
    val selected = plan != null && currentPlan?.profileId == plan.profileId
    val applied = selected && planApplied
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.opra_profile_by, author),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                val tint = if (applied) AppliedGreen else PendingAmber
                Surface(
                    color = tint.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        stringResource(
                            if (applied) R.string.opra_applied_tag else R.string.opra_pending_tag
                        ),
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
        details?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        sourceUrl?.takeIf { it.isNotBlank() }?.let { link ->
            LinkButton(
                text = stringResource(R.string.opra_measurement_source),
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                        )
                    }
                }
            )
        }
        Text(meta, color = TextTertiary, fontSize = 11.sp)

        if (profile == null) {
            Text(
                stringResource(R.string.opra_profile_unparseable),
                color = FxRedPrimary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (plan == null) {
            Text(
                stringResource(R.string.opra_profile_unrenderable),
                color = FxRedPrimary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            ProfileControls(
                profileId = plan.profileId,
                summaryId = summaryId,
                plan = plan,
                currentPlan = currentPlan,
                masterAdjustDb = masterAdjustDb,
                onApply = onApply
            )
        }
        Spacer(Modifier.height(12.dp))
        OpraDivider()
    }
}

@Composable
private fun ProfileControls(
    profileId: String,
    summaryId: String,
    plan: HeadphoneEqPlan,
    currentPlan: HeadphoneEqPlan?,
    masterAdjustDb: Float,
    onApply: (List<Float>, Float) -> Unit
) {
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

    Spacer(Modifier.height(8.dp))
    ProfileCurve(
        plan = plan.copy(
            bands = plan.bands.mapIndexed { i, band ->
                band.copy(gainDb = (band.gainDb + (offsets.getOrNull(i) ?: 0f) + adjustDb).coerceIn(-20f, 20f))
            }
        )
    )
    Text(
        stringResource(R.string.opra_ideal_preview),
        color = TextTertiary, fontSize = 10.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
    if (plan.maxShelfApproximationErrorDb >= 3f) {
        Text(
            stringResource(
                R.string.opra_approximation_error,
                String.format(Locale.ROOT, "%.1f", plan.maxShelfApproximationErrorDb)
            ),
            color = PendingAmber, fontSize = 10.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    Text(
        plan.rendererDisclosure,
        color = TextTertiary, fontSize = 10.sp, lineHeight = 14.sp,
        modifier = Modifier.padding(top = 4.dp)
    )

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.opra_level), color = TextSecondary, fontSize = 11.sp)
        Slider(
            value = adjustDb,
            onValueChange = { adjustDb = it },
            valueRange = -6f..6f,
            steps = 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = FxRedPrimary,
                activeTrackColor = FxRedPrimary,
                inactiveTrackColor = BorderSubtle
            )
        )
        Text(
            "${if (adjustDb >= 0) "+" else ""}${String.format(Locale.ROOT, "%.1f", adjustDb)}",
            color = TextPrimary, fontSize = 11.sp,
            modifier = Modifier.width(46.dp), textAlign = TextAlign.End
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            onClick = { showBandTrims = !showBandTrims }
        ) {
            Text(
                (if (showBandTrims) "\u25BE " else "\u25B8 ") + stringResource(R.string.opra_per_band_title),
                color = TextSecondary, fontSize = 11.sp
            )
        }
        Spacer(Modifier.weight(1f))
        if (showBandTrims) {
            TextButton(onClick = { offsets = List(plan.bands.size) { 0f } }) {
                Text(stringResource(R.string.opra_per_band_reset), color = TextTertiary, fontSize = 11.sp)
            }
        }
    }
    if (showBandTrims) {
        Text(
            stringResource(R.string.opra_per_band_hint),
            color = TextTertiary, fontSize = 10.sp, lineHeight = 14.sp
        )
        plan.bands.forEachIndexed { index, band ->
            val offset = offsets.getOrNull(index) ?: 0f
            val label = stringResource(
                R.string.opra_band_label,
                String.format(Locale.ROOT, "%.0f", band.frequencyHz)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            ) {
                Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (band.approximationErrorDb != null) {
                    Text(
                        stringResource(R.string.opra_shelf_tag),
                        color = TextTertiary, fontSize = 9.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                TrimButton(
                    symbol = "\u2212",
                    description = stringResource(R.string.opra_decrease, label),
                    onClick = {
                        offsets = offsets.toMutableList().also { it[index] = (offset - 0.5f).coerceIn(-6f, 6f) }
                    }
                )
                Text(
                    "${if (offset >= 0) "+" else ""}${String.format(Locale.ROOT, "%.1f", offset)}",
                    color = TextPrimary, fontSize = 11.sp,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center
                )
                TrimButton(
                    symbol = "+",
                    description = stringResource(R.string.opra_increase, label),
                    onClick = {
                        offsets = offsets.toMutableList().also { it[index] = (offset + 0.5f).coerceIn(-6f, 6f) }
                    }
                )
            }
        }
    }

    Button(
        onClick = { onApply(offsets, adjustDb) },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FxRedDark, contentColor = Color.White)
    ) {
        Text(stringResource(R.string.opra_apply), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun TrimButton(symbol: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(symbol, color = FxRedPrimary, fontSize = 16.sp)
    }
}

/** Response preview plus a log-frequency axis caption. */
@Composable
private fun ProfileCurve(plan: HeadphoneEqPlan, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HeadphoneCurvePreview(plan = plan, modifier = Modifier.fillMaxWidth().height(104.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(
                R.string.opra_chart_axis_20,
                R.string.opra_chart_axis_100,
                R.string.opra_chart_axis_1k,
                R.string.opra_chart_axis_10k,
                R.string.opra_chart_axis_20k
            ).forEach {
                Text(stringResource(it), color = TextTertiary, fontSize = 9.sp)
            }
        }
    }
}

/**
 * Analytic magnitude preview of the converted plan's peaking (RBJ) biquads at 44.1 kHz,
 * drawn as a log-frequency response curve. This is a filter-graph preview of an ideal
 * peaking model, not a measured result - the device renderer chooses its own bandwidth.
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
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = Color(0x77FFFFFF))

    Canvas(
        modifier = modifier
            .background(DarkSurfaceElevated, RoundedCornerShape(10.dp))
            .semantics { contentDescription = description }
    ) {
        val top = 10f
        val bottom = size.height - 10f
        val yFor: (Double) -> Float = { db -> (bottom - ((db + 18.0) / 36.0) * (bottom - top)).toFloat() }
        val xFor: (Double) -> Float = { f ->
            val t = (ln(f / 20.0) / ln(20000.0 / 20.0))
            size.width * t.toFloat()
        }
        for (db in listOf(-12, -6, 0, 6, 12, 18)) {
            drawLine(
                Color(0x1AFFFFFF),
                Offset(0f, yFor(db.toDouble())),
                Offset(size.width, yFor(db.toDouble())),
                1f
            )
        }
        val path = Path()
        curve.forEachIndexed { i, (f, db) ->
            val p = Offset(xFor(f), yFor(db))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, bottom)
            lineTo(0f, bottom)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(FxRedPrimary.copy(alpha = 0.20f), Color.Transparent),
                startY = top,
                endY = bottom
            )
        )
        drawPath(path, FxRedPrimary, style = Stroke(width = 2.5f))
        // 0 dB reference line drawn last so it stays visible over the fill.
        drawLine(
            Color(0x33FFFFFF),
            Offset(0f, yFor(0.0)),
            Offset(size.width, yFor(0.0)),
            1f
        )
        // The vertical scale is fixed at +/-18 dB so profiles stay comparable; label the
        // three main gridlines so a nearly flat curve is readable as "small correction".
        for (db in listOf(12, 0, -12)) {
            val layout = measurer.measure(if (db > 0) "+$db" else "$db", labelStyle)
            val y = yFor(db.toDouble())
            // Keep the 0 dB label off its own reference line.
            val dy = if (db == 0) y - layout.size.height - 1f else y - layout.size.height / 2f
            drawText(layout, topLeft = Offset(6f, dy))
        }
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
