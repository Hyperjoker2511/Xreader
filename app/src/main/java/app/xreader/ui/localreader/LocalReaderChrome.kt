package app.xreader.ui.localreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.data.prefs.GlassStyle
import app.xreader.ui.theme.JakartaSans
import app.xreader.ui.theme.LocalGlassSettings
import app.xreader.ui.theme.LocalHazeState
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * v1.5.8 (G3): the local readers' chrome language — Xreader's
 * own frosted-glass vocabulary (the novel reader's chips and
 * the shell dock's capsule: pill shapes, hairline borders,
 * raised shadow, palette surfaces), NOT stock Material bars.
 * Shared by the EPUB and PDF readers; the machinery behind
 * every control is unchanged — this file is placement + look.
 *
 * v1.5.9 (Finding 4 F-8a/U-1): the "glass" is now REAL glass.
 * The primitives take their surface from the app's own glass
 * system (`GlassSettings.glassSurface()` — Theme.kt) and, when
 * the EFFECTIVE style is FROSTED and the shell's shared Haze
 * state is present, sample it with `Modifier.hazeEffect` —
 * the same construction the shell dock uses
 * (`Modifier.frostedGlass` in ui/common). SOLID/TINTED keep
 * the flat rendering, and plain mode (API < 31 safe
 * appearance) collapses to flat BY CONSTRUCTION:
 * `AppearanceValidation.effectiveGlassStyle` already resolves
 * FROSTED → TINTED there before this code ever sees the style,
 * so no runtime/API check lives in these composables. This
 * file does NOT add a haze source and does not remove the
 * shell's — it only samples the one that exists.
 *
 * v1.5.9 delta (Gemini sift A-8, amending F-8a): backdrop blur
 * is licensed by the BACKDROP KIND, not the style alone. Over
 * the EPUB navigator the backdrop is a live hardware-accelerated
 * WebView; recording WebView content into a RenderNode under a
 * blur RenderEffect is a maintainer-acknowledged failure mode
 * (flashing — haze issue #99). So the EPUB reader's subtree
 * provides [ReaderBackdropKind.WEBVIEW] (see
 * [LocalReaderBackdropKind]) and in that subtree NO glass
 * primitive can blur, in any GlassStyle: the surfaces are the
 * glass system's tint construction plus the violet border
 * treatment. True backdrop blur applies only where the backdrop
 * is app-rasterised — the PDF page canvas and Compose surfaces —
 * which is the default every non-EPUB call site gets.
 */

// ── F-8a: the pure glass-spec resolver ───────────────────────────

/** DESIGN.md's violet accent (#8B5CF6) — the one accent the
 * reader chrome spends on selection and glow (SLOP #10). */
val ReaderViolet: Color = Color(0xFF8B5CF6)

/** DESIGN.md tier-2 surface (#161822): unselected chips and
 * nested controls. */
val ReaderTier2Surface: Color = Color(0xFF161822)

/** DESIGN.md muted text (#94a3b8): unselected chip labels. */
val ReaderMutedText: Color = Color(0xFF94A3B8)

/** What sits BEHIND a reader-chrome surface (sift A-8).
 * [RASTERISED] — app-drawn content (the PDF page canvas,
 * Compose surfaces): backdrop blur is licensed. [WEBVIEW] —
 * a live hardware-accelerated WebView (the EPUB navigator):
 * blur is never licensed, in any GlassStyle. */
enum class ReaderBackdropKind { WEBVIEW, RASTERISED }

/**
 * The backdrop kind for the local-reader subtree being
 * composed. Defaults to [ReaderBackdropKind.RASTERISED], so
 * the PDF reader's call sites (which provide nothing) stay
 * blur-capable; `EpubBookReader` provides
 * [ReaderBackdropKind.WEBVIEW] around its whole subtree,
 * which makes blur over the WebView structurally
 * unrequestable from any EPUB chrome call site — the
 * primitives take the kind from here, never from a parameter
 * an EPUB call site could flip.
 */
val LocalReaderBackdropKind =
    androidx.compose.runtime.compositionLocalOf {
        ReaderBackdropKind.RASTERISED
    }

/**
 * The resolved glass treatment for one reader-chrome surface:
 * whether it blurs, the alpha its surface colour carries, its
 * border, and whether its shadow is the design's violet-tinted
 * ambient shadow. A PURE mapping (effective style + haze
 * availability + opacity + backdrop kind) so
 * `ReaderGlassSpecTest` can pin DESIGN.md's numbers without a
 * composition.
 *
 * FROSTED × RASTERISED → blur when (and only when) a haze
 * state is present, surface alpha 0.72 (Floating Glass),
 * white 0.10 border, blur radius 24 dp, violet ambient shadow.
 * FROSTED × WEBVIEW (A-8) → NO blur ever: the tint surface at
 * the same 0.72 alpha plus the U-3 violet border treatment
 * (violet at 0.8), violet ambient shadow kept (a shadow is
 * not a backdrop sample).
 * SOLID → fully opaque flat surface, no blur, no violet.
 * TINTED → flat surface at the user's opacity, no blur.
 * Plain mode arrives here as TINTED (see the file doc), so it
 * is flat by construction.
 */
data class ReaderGlassSpec(
    val blur: Boolean,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val blurRadiusDp: Float,
    val violetShadow: Boolean,
    val violetBorder: Boolean = false
)

fun readerGlassSpec(
    effectiveStyle: GlassStyle,
    hazeAvailable: Boolean,
    opacity: Float = 1f,
    backdropKind: ReaderBackdropKind = ReaderBackdropKind.RASTERISED
): ReaderGlassSpec = when (effectiveStyle) {
    // LIQUID renders as FROSTED until the liquid surface lands (Patches 2–3).
    GlassStyle.LIQUID, GlassStyle.FROSTED -> when (backdropKind) {
        ReaderBackdropKind.RASTERISED -> ReaderGlassSpec(
            blur = hazeAvailable,
            surfaceAlpha = 0.72f,
            borderAlpha = 0.10f,
            blurRadiusDp = 24f,
            violetShadow = true
        )
        ReaderBackdropKind.WEBVIEW -> ReaderGlassSpec(
            blur = false,
            surfaceAlpha = 0.72f,
            borderAlpha = 0.8f,
            blurRadiusDp = 0f,
            violetShadow = true,
            violetBorder = true
        )
    }
    GlassStyle.SOLID -> ReaderGlassSpec(
        blur = false,
        surfaceAlpha = 1f,
        borderAlpha = 0.12f,
        blurRadiusDp = 0f,
        violetShadow = false
    )
    GlassStyle.TINTED -> ReaderGlassSpec(
        blur = false,
        surfaceAlpha = opacity.coerceIn(0f, 1f),
        borderAlpha = 0.12f,
        blurRadiusDp = 0f,
        violetShadow = false
    )
}

/**
 * The resolved stadium-chip treatment (F-8b/U-3, DESIGN.md
 * "Chips & Filter Pills"): unselected = tier-2 #161822 +
 * white 0.06 border + #94a3b8 text; selected = violet border
 * rgba(139,92,246,0.8) + white text on the elevated slate, or
 * — for a PRIMARY toggle — the violet surface itself, with
 * the design's glow on the primary state. Pure, so the glass
 * spec test pins it without a composition.
 */
data class ReaderChipSpec(
    val containerColor: Color,
    val borderColor: Color,
    val labelColor: Color,
    val glow: Boolean
)

fun readerChipSpec(selected: Boolean, primary: Boolean = false): ReaderChipSpec =
    when {
        selected && primary -> ReaderChipSpec(
            containerColor = ReaderViolet,
            borderColor = ReaderViolet.copy(alpha = 0.8f),
            labelColor = Color.White,
            glow = true
        )
        selected -> ReaderChipSpec(
            containerColor = ReaderTier2Surface,
            borderColor = ReaderViolet.copy(alpha = 0.8f),
            labelColor = Color.White,
            glow = false
        )
        else -> ReaderChipSpec(
            containerColor = ReaderTier2Surface,
            borderColor = Color.White.copy(alpha = 0.06f),
            labelColor = ReaderMutedText,
            glow = false
        )
    }

/** The live glass spec for the current composition: effective
 * style + whether the shell's haze state is being provided +
 * the subtree's backdrop kind (A-8). */
@Composable
internal fun rememberReaderGlassSpec(): ReaderGlassSpec {
    val glass = LocalGlassSettings.current
    val haze = LocalHazeState.current
    return readerGlassSpec(
        glass.effectiveStyle,
        haze != null,
        glass.opacity,
        LocalReaderBackdropKind.current
    )
}

/** The border colour a glass primitive strokes: the U-3
 * violet when the spec carries the violet border treatment
 * (A-8's WebView adaptation), else white at the spec's alpha. */
private fun glassBorderColor(spec: ReaderGlassSpec): Color =
    if (spec.violetBorder) {
        ReaderViolet.copy(alpha = spec.borderAlpha)
    } else {
        Color.White.copy(alpha = spec.borderAlpha)
    }

/**
 * Applies the resolved glass treatment to a chrome surface:
 * the haze effect (FROSTED + a haze state only — the shell
 * dock's construction, including its background colour, which
 * Haze requires) and the design's ambient shadow in place of
 * Material's `shadowElevation` (black ambient; a violet-tinted
 * spot on the bottom capsules, per [violetSpot]).
 */
@Composable
private fun Modifier.readerGlassSurface(
    spec: ReaderGlassSpec,
    shape: Shape,
    elevation: Dp,
    violetSpot: Boolean
): Modifier {
    var result = this
    if (spec.blur) {
        val haze = LocalHazeState.current
        val glass = LocalGlassSettings.current
        if (haze != null) {
            val background = MaterialTheme.colorScheme.background
            val tint = glass.glassSurface(MaterialTheme.colorScheme.surface)
            result = result.hazeEffect(haze) {
                backgroundColor = background
                blurRadius = spec.blurRadiusDp.dp
                tints = listOf(HazeTint(tint.copy(alpha = 0.55f)))
                fallbackTint = HazeTint(tint)
            }
        }
    }
    return result.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.7f),
        spotColor = if (violetSpot && spec.violetShadow) {
            ReaderViolet.copy(alpha = 0.35f)
        } else {
            Color.Black.copy(alpha = 0.7f)
        }
    )
}

/** The surface colour a glass primitive renders: the glass
 * system's own surface for the base scheme colour. */
@Composable
private fun glassPrimitiveColor(): Color =
    LocalGlassSettings.current.glassSurface(MaterialTheme.colorScheme.surface)

/** One frosted-glass chip button (the novel reader's
 * GlassChipButton shape, in the app scheme). */
@Composable
internal fun GlassChipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val spec = rememberReaderGlassSpec()
    val shape = CircleShape
    Surface(
        onClick = onClick,
        shape = shape,
        color = glassPrimitiveColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, glassBorderColor(spec)),
        shadowElevation = 0.dp,
        modifier = modifier
            .readerGlassSurface(spec, shape, elevation = 4.dp, violetSpot = false)
            .size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** DESIGN.md headline-sm: Plus Jakarta Sans 600 at 18 sp — the
 * title pill's title weight (F-8c/U-4). */
internal val ReaderTitlePillTitleStyle = TextStyle(
    fontFamily = JakartaSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.18).sp
)

/** The slim top bar's title pill: book title (+ optional
 * subtitle line) in a glass capsule. F-8c: the title carries
 * headline weight and, when [coverModel] is supplied, a
 * 12 dp-radius cover thumbnail sits at the pill's start (the
 * book page's cover language, miniaturised). The cover
 * parameter is optional and defaults to null so the PDF
 * reader's existing call site compiles unchanged. */
@Composable
internal fun GlassTitlePill(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    coverModel: Any? = null
) {
    val spec = rememberReaderGlassSpec()
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = glassPrimitiveColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, glassBorderColor(spec)),
        shadowElevation = 0.dp,
        modifier = modifier.readerGlassSurface(
            spec, shape, elevation = 4.dp, violetSpot = false
        )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(width = 30.dp, height = 42.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    title,
                    style = ReaderTitlePillTitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** A bottom-chrome capsule in the dock's language: full-pill
 * radius, hairline border, and the design's ambient shadow
 * with its violet-tinted spot (F-8a). */
@Composable
internal fun GlassCapsule(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val spec = rememberReaderGlassSpec()
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = glassPrimitiveColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, glassBorderColor(spec)),
        shadowElevation = 0.dp,
        modifier = modifier.readerGlassSurface(
            spec, shape, elevation = 6.dp, violetSpot = true
        )
    ) {
        content()
    }
}

// ── F-8b/U-3: the shared stadium chip ────────────────────────────

/**
 * The ONE stadium chip both local readers' chip rows consume
 * (EPUB mode/auto-scroll rows, PDF quick/display rows): a
 * Material [FilterChip] whose colours are the DESIGN chip
 * rule resolved by [readerChipSpec] — never stock M3
 * grey-on-grey selection. [primary] marks the row's primary
 * toggle (e.g. Auto-scroll while running): its selected state
 * takes the violet surface + glow.
 */
@Composable
internal fun ReaderStadiumChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    label: @Composable () -> Unit
) {
    val chip = readerChipSpec(selected = selected, primary = primary)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = leadingIcon,
        modifier = if (chip.glow) {
            modifier.shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(50),
                clip = false,
                ambientColor = ReaderViolet.copy(alpha = 0.4f),
                spotColor = ReaderViolet.copy(alpha = 0.4f)
            )
        } else {
            modifier
        },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = chip.containerColor,
            labelColor = chip.labelColor,
            iconColor = chip.labelColor,
            selectedContainerColor = chip.containerColor,
            selectedLabelColor = chip.labelColor,
            selectedLeadingIconColor = chip.labelColor
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = chip.borderColor,
            selectedBorderColor = chip.borderColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        )
    )
}

/** Text-label convenience overload of [ReaderStadiumChip]. */
@Composable
internal fun ReaderStadiumChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    ReaderStadiumChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        primary = primary,
        leadingIcon = leadingIcon,
        label = { Text(text) }
    )
}

// ── G3: the quick-row action model ───────────────────────────────

/** One bottom quick-row action. The row is a MODEL first so
 * its shape — six actions, in Moon+'s order, each bound to its
 * PRE-EXISTING handler — is testable without a composition
 * (V158EpubChromeShapeTest asserts handler identity). */
data class QuickAction(
    val id: String,
    val label: String,
    val onClick: () -> Unit
)

/** The EPUB chrome's pre-existing handlers, gathered once.
 * Every lambda here is the SAME handler the v1.5.7 top bar
 * invoked — G3 is placement, not new machinery. */
class EpubChromeHandlers(
    val onToggleDayNight: () -> Unit,
    val onContents: () -> Unit,
    val onBookmarks: () -> Unit,
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    val onReadAloud: () -> Unit
)

/** Moon+'s bottom quick row (G3): Day/Night · Contents ·
 * Bookmarks · Search · Aa (book settings) · Read aloud. */
fun epubQuickActions(handlers: EpubChromeHandlers): List<QuickAction> = listOf(
    QuickAction("dayNight", "Day / night", handlers.onToggleDayNight),
    QuickAction("contents", "Contents", handlers.onContents),
    QuickAction("bookmarks", "Bookmarks", handlers.onBookmarks),
    QuickAction("search", "Search", handlers.onSearch),
    QuickAction("settings", "Book settings", handlers.onSettings),
    QuickAction("readAloud", "Read aloud", handlers.onReadAloud)
)

// ── v1.5.10 (F1): the customisable reader bar ────────────────────

/** The icon a bar action renders with. Static per action —
 * state-ful variants (day/night's sun/moon) stay with the
 * readers' own shipped rows; the customised row's contract is
 * identity + handler, not per-state artwork. */
@Composable
internal fun ReaderBarActionIcon(action: ReaderBarActionId) {
    when (action) {
        ReaderBarActionId.ORIENTATION -> Icon(
            androidx.compose.material.icons.Icons.Filled.ScreenRotation,
            contentDescription = action.label
        )
        ReaderBarActionId.DAY_NIGHT -> Icon(
            androidx.compose.material.icons.Icons.Filled.DarkMode,
            contentDescription = action.label
        )
        ReaderBarActionId.SPEAK -> Icon(
            androidx.compose.material.icons.Icons.Filled.RecordVoiceOver,
            contentDescription = action.label
        )
        ReaderBarActionId.AUTOSCROLL -> Icon(
            androidx.compose.material.icons.Icons.Filled.PlayArrow,
            contentDescription = action.label
        )
        ReaderBarActionId.CHAPTERS -> Icon(
            androidx.compose.material.icons.Icons.Filled.List,
            contentDescription = action.label
        )
        ReaderBarActionId.BOOKMARKS -> Icon(
            androidx.compose.material.icons.Icons.Filled.Bookmark,
            contentDescription = action.label
        )
        ReaderBarActionId.BRIGHTNESS -> Icon(
            androidx.compose.material.icons.Icons.Filled.Brightness6,
            contentDescription = action.label
        )
        ReaderBarActionId.FONT_SIZE -> Icon(
            androidx.compose.material.icons.Icons.Filled.FormatSize,
            contentDescription = action.label
        )
        ReaderBarActionId.SEARCH -> Icon(
            androidx.compose.material.icons.Icons.Filled.Search,
            contentDescription = action.label
        )
        ReaderBarActionId.TILT -> Icon(
            androidx.compose.material.icons.Icons.Filled.RotateRight,
            contentDescription = action.label
        )
        ReaderBarActionId.VISUAL_OPTIONS -> Icon(
            androidx.compose.material.icons.Icons.Filled.Tune,
            contentDescription = action.label
        )
        ReaderBarActionId.CONTROL_OPTIONS -> Icon(
            androidx.compose.material.icons.Icons.Filled.Settings,
            contentDescription = action.label
        )
        ReaderBarActionId.BOOKMARK_ADD -> Icon(
            androidx.compose.material.icons.Icons.Filled.BookmarkAdd,
            contentDescription = action.label
        )
        ReaderBarActionId.TEXT_VIEW -> Icon(
            androidx.compose.material.icons.Icons.Filled.TextFields,
            contentDescription = action.label
        )
    }
}

/**
 * The customised bar row (F1): the resolved actions for one
 * reader, in the config's order, one line or two per the
 * Double-line toggle. An action whose reader supplied no
 * handler is NOT rendered — the bar never shows a dead tile
 * (F1's honesty rule). Tile shape = the shipped quick rows'
 * glass chip + plain-word label.
 */
@Composable
internal fun ReaderBarRow(
    actions: List<ReaderBarActionId>,
    handlers: Map<ReaderBarActionId, () -> Unit>,
    doubleLineLayout: Boolean,
    activeActions: Set<ReaderBarActionId> = emptySet()
) {
    val wired = actions.filter { it in handlers }
    if (wired.isEmpty()) return
    val rows: List<List<ReaderBarActionId>> =
        if (doubleLineLayout && wired.size > 4) {
            val split = (wired.size + 1) / 2
            listOf(wired.take(split), wired.drop(split))
        } else {
            listOf(wired)
        }
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { rowActions ->
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                rowActions.forEach { action ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlassChipButton(onClick = handlers.getValue(action)) {
                            ReaderBarActionIcon(action)
                        }
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (action in activeActions) {
                                ReaderViolet
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Customise reader bar sheet's CONTENT (F1; the caller
 * hosts it in the readers' sheet chrome): Moon+'s full
 * enumeration — the Double-line toggle plus every action row,
 * checkable and drag-ordered (up/down steppers, the return's
 * sheet interaction shape). Rows state their per-format
 * availability in words instead of offering a dead action in
 * the other reader.
 */
@Composable
internal fun CustomisableReaderBarSheetContent(
    config: ReaderBarConfig,
    onConfigChange: (ReaderBarConfig) -> Unit
) {
    val ordered = config.orderedKeys
        .mapNotNull { ReaderBarActionId.fromKey(it) }
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Reader bar", style = MaterialTheme.typography.titleMedium)
        Text(
            "One bar for both readers. An action a reader can't " +
                "do is hidden in that reader — never a dead button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Double-line layout", style = MaterialTheme.typography.labelLarge)
            ReaderBarSwitch(
                checked = config.doubleLineLayout,
                onCheckedChange = { on ->
                    onConfigChange(config.copy(doubleLineLayout = on))
                }
            )
        }
        ordered.forEachIndexed { index, action ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = action.key in config.enabledKeys,
                    onCheckedChange = { checked ->
                        onConfigChange(config.toggled(action, checked))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = ReaderViolet,
                        uncheckedColor = ReaderMutedText
                    )
                )
                Column(Modifier.weight(1f)) {
                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                    action.availabilityNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { onConfigChange(config.moved(index, index - 1)) },
                    enabled = index > 0
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.ArrowUpward,
                        contentDescription = "Move ${action.label} up"
                    )
                }
                IconButton(
                    onClick = { onConfigChange(config.moved(index, index + 1)) },
                    enabled = index < ordered.lastIndex
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.ArrowDownward,
                        contentDescription = "Move ${action.label} down"
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The design-system switch for reader-chrome sheets (violet
 * on-state — the PDF display sheet's treatment, shared). */
@Composable
internal fun ReaderBarSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = ReaderViolet,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = ReaderTier2Surface,
            uncheckedThumbColor = ReaderMutedText,
            uncheckedBorderColor = Color.White.copy(alpha = 0.06f)
        )
    )
}

/** F1's brightness action surface: a slider popover over the
 * window-brightness seam (the same value the 28 dp edge strip
 * drives). [value] is 0.02..1; the caller applies it. */
@Composable
internal fun BrightnessSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Brightness", style = MaterialTheme.typography.labelLarge)
            Text(
                "${(value.coerceIn(0.02f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(0.02f, 1f),
            onValueChange = onValueChange,
            valueRange = 0.02f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── v1.5.10 (G2): Control Options ────────────────────────────────

/** The Control Options sheet's state — one value per control,
 * each writing the SAME state its other door writes (the
 * tree's one-state-two-controls discipline). [smartScrollLock]
 * is null for the EPUB reader (the lock is a PDF-continuous
 * control) and its row is then absent. */
data class ControlOptionsState(
    val tapPreset: TapLayoutPreset,
    val tiltEnabled: Boolean,
    val smartScrollLock: Boolean?,
    val volumeKeyTurn: Boolean,
    val keepScreenOn: Boolean,
    /** v1.5.34 (item 3b): true when the reader is in a mode
     * where tap TURN zones do not exist — EPUB scrolling
     * (river and legacy alike: `epubScrollModeTapAction` maps
     * every turn zone to NONE; a tap can only show/hide the
     * bars). The sheet then says exactly that instead of
     * advertising zones the mode ignores. The presets stay
     * selectable: the choice still governs Page by page. */
    val tapTurnsInert: Boolean = false
)

/**
 * The shared Control Options sheet's CONTENT (G2): three
 * PRESET tap layouts (the limitation is stated in the sheet,
 * per plan G2 — no free-form grid editor), the tilt row (F6),
 * the PDF smart scroll lock, volume keys and keep-screen-on.
 * Auto-scroll's naming is honest here too: the shipped driver
 * offers continuous roll and page-step — Moon+'s other roll
 * variants are not faked as modes.
 */
@Composable
internal fun ControlOptionsSheetContent(
    state: ControlOptionsState,
    onTapPreset: (TapLayoutPreset) -> Unit,
    onTiltEnabled: (Boolean) -> Unit,
    onSmartScrollLock: (Boolean) -> Unit,
    onVolumeKeyTurn: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Control options", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Tap layout", style = MaterialTheme.typography.titleSmall)
        Text(
            if (state.tapTurnsInert) {
                // v1.5.34 (item 3b): the honest scroll-mode
                // wording — in scrolling reading there are no
                // tap turn zones at all (the scroll IS the
                // navigation); the preset below governs Page
                // by page only.
                "You're reading in scrolling mode: taps only " +
                    "show or hide the bars — the scroll moves " +
                    "through the book. These layouts apply in " +
                    "Page by page mode."
            } else {
                "Three preset layouts. (Moon+ lets you remap every " +
                    "zone; Xreader offers these presets instead.)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapLayoutPreset.entries.forEach { preset ->
                ReaderStadiumChip(
                    selected = state.tapPreset == preset,
                    onClick = { onTapPreset(preset) },
                    text = preset.label
                )
            }
        }
        if (!state.tapTurnsInert) {
            Text(
                when (state.tapPreset) {
                    TapLayoutPreset.MOON_DEFAULT ->
                        "Left third = back, right third = next page, centre = show the bars."
                    TapLayoutPreset.EDGES_ONLY ->
                        "Only the outer edges turn pages; the middle shows the bars."
                    TapLayoutPreset.NINE_ZONE ->
                        "The page in nine zones: side columns turn, centre shows the bars."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        ControlSwitchRow(
            label = "Tilt to turn page",
            subtitle = "Tilting the phone past a point turns the page. Off by default.",
            checked = state.tiltEnabled,
            onCheckedChange = onTiltEnabled
        )
        if (state.smartScrollLock != null) {
            ControlSwitchRow(
                label = "Smart scroll lock",
                subtitle = "In scroll-down reading, a mostly-up-and-down drag never drifts sideways.",
                checked = state.smartScrollLock,
                onCheckedChange = onSmartScrollLock
            )
        }
        ControlSwitchRow(
            label = "Volume-key page turns",
            subtitle = "Volume down = next, volume up = back.",
            checked = state.volumeKeyTurn,
            onCheckedChange = onVolumeKeyTurn
        )
        ControlSwitchRow(
            label = "Keep screen on",
            subtitle = "The screen stays awake while you read.",
            checked = state.keepScreenOn,
            onCheckedChange = onKeepScreenOn
        )
        Text(
            "Auto-scroll offers the two ways it can honestly " +
                "move: a continuous roll, and a page-step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ControlSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ReaderBarSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** G2's smart scroll lock, the pure half: in the PDF
 * continuous column, a predominantly-vertical drag's
 * horizontal component is consumed (locked) — the column can
 * never drift sideways mid-read. */
fun smartScrollLockConsumesHorizontal(
    deltaX: Float,
    deltaY: Float,
    lockEnabled: Boolean
): Boolean =
    lockEnabled && deltaX != 0f && abs(deltaY) > abs(deltaX)

private fun abs(value: Float): Float = if (value < 0f) -value else value

// ── G2: the settings-sheet section order ─────────────────────────

/** The book-mode sheet's sections, in render order. G2: the
 * reading-mode row is FIRST (it was the last row of the sheet
 * in v1.5.7 — the buried switch the owner hit), then theme,
 * then typography. */
enum class EpubSheetSection { MODE, THEME, TYPOGRAPHY, PLATES, COMFORTS, RESET }

fun epubSheetSections(): List<EpubSheetSection> = listOf(
    EpubSheetSection.MODE,
    EpubSheetSection.THEME,
    EpubSheetSection.TYPOGRAPHY,
    EpubSheetSection.PLATES,
    EpubSheetSection.COMFORTS,
    EpubSheetSection.RESET
)
