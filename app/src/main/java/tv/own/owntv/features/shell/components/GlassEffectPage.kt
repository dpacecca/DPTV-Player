package tv.own.owntv.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.theme.GlassPreset
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.features.settings.PickerDialog
import tv.own.owntv.features.settings.SettingHelp
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsHeading
import tv.own.owntv.features.settings.settingHelp
import tv.own.owntv.features.settings.dotSeparator
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.drawOuterRing
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.ALL_GLASS_SURFACES
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

/**
 * Settings › Appearance › Glass Effect (P10B-16): the switch, Style (preset) and Background image, the
 * three FINE TUNING steppers and the four BEHAVIOR rows. Every row's panel carries the live preview, so a
 * change shows at once; Style lists its presets there.
 */
@Composable
internal fun GlassEffectPage(
    glassOn: Boolean,
    preset: GlassPreset,
    alphaPercent: Int,
    blurPercent: Int,
    highlightPercent: Int,
    allowFullTransparency: Boolean,
    depthEffects: Boolean,
    bgOn: Boolean,
    scope: Set<GlassSurface>,
    onToggleGlass: () -> Unit,
    onSetPreset: (GlassPreset) -> Unit,
    onSetAlpha: (Int) -> Unit,
    onSetBlur: (Int) -> Unit,
    onSetHighlight: (Int) -> Unit,
    onSetAllowFullTransparency: (Boolean) -> Unit,
    onSetDepthEffects: (Boolean) -> Unit,
    onOpenSurfaces: () -> Unit,
    onResetBalanced: () -> Unit,
    onOpenBackground: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowsFocus = remember { FocusRequester() }
    val styleFocus = remember { FocusRequester() }
    var pickStyle by remember { mutableStateOf(false) }
    var styleWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { rowsFocus.requestFocus() } }
    LaunchedEffect(pickStyle) {
        if (pickStyle) styleWasOpen = true
        else if (styleWasOpen) { styleWasOpen = false; kotlinx.coroutines.delay(80); runCatching { styleFocus.requestFocus() } }
    }

    val preview: @Composable () -> Unit = { GlassPreview() }
    val presetLabels = GlassPreset.entries.map { glassPresetLabel(it) }
    val count = if (glassOn) 10 else 1
    val stepHints = listOf("◀ ▶" to stringResource(R.string.settings_key_change), stringResource(R.string.common_back) to stringResource(R.string.settings_group_appearance))
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_appearance)),
        title = stringResource(R.string.settings_glass_effect_title),
        count = pluralStringResource(R.plurals.settings_setting_count, count, count),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
    ) {
        val glassTitle = stringResource(R.string.settings_glass_effect)
        val glassLine = stringResource(R.string.settings_glass_master_description)
        val glassValue = SettingValue.Switch(glassOn)
        StageSettingRow(
            icon = OwnTVIcon.SPARKLE,
            title = glassTitle,
            desc = glassLine,
            value = glassValue,
            onClick = onToggleGlass,
            help = settingHelp(null, glassTitle, glassLine, glassValue, pinnable = false).copy(extra = preview),
        )
        if (!glassOn) return@StageFullPage

        val styleTitle = stringResource(R.string.settings_glass_style)
        val styleValue = SettingValue.Choice(glassPresetLabel(preset))
        StageSettingRow(
            icon = OwnTVIcon.PALETTE,
            title = styleTitle,
            desc = stringResource(R.string.settings_line_glass_style),
            value = styleValue,
            onClick = { pickStyle = true },
            modifier = Modifier.focusRequester(styleFocus),
            help = settingHelp(
                null,
                styleTitle + dotSeparator() + stringResource(R.string.settings_glass_live_preview),
                stringResource(R.string.settings_glass_preview_help),
                styleValue,
                choices = presetLabels,
                chosen = GlassPreset.entries.indexOf(preset),
                recommended = GlassPreset.entries.indexOf(GlassPreset.BALANCED),
                pinnable = false,
            ).copy(extra = preview),
        )
        val bgTitle = stringResource(R.string.settings_glass_background_image)
        val bgLine = stringResource(R.string.settings_glass_background_action_description)
        val bgValue = SettingValue.Opens(stringResource(if (bgOn) R.string.common_on else R.string.common_off))
        StageSettingRow(
            icon = OwnTVIcon.IMAGE,
            title = bgTitle,
            desc = bgLine,
            value = bgValue,
            onClick = onOpenBackground,
            help = settingHelp(null, bgTitle, bgLine, bgValue, pinnable = false).copy(extra = preview),
        )

        StageSettingsHeading(stringResource(R.string.settings_glass_section_fine_tuning), 3)
        GlassStepRow(R.string.settings_glass_surface_transparency_title, R.string.settings_glass_transparency_short_description, alphaPercent, 20, 100, 5, onSetAlpha, stepHints, preview)
        GlassStepRow(R.string.settings_glass_background_blur_title, R.string.settings_glass_blur_short_description, blurPercent, 0, 100, 10, onSetBlur, stepHints, preview)
        GlassStepRow(R.string.settings_glass_highlight_title, R.string.settings_glass_highlight_short_description, highlightPercent, 0, 100, 5, onSetHighlight, stepHints, preview)

        StageSettingsHeading(stringResource(R.string.settings_glass_section_behavior), 4)
        GlassSwitchRow(R.string.settings_glass_full_transparency_short, R.string.settings_glass_full_transparency_short_description, allowFullTransparency, preview) {
            onSetAllowFullTransparency(!allowFullTransparency)
        }
        GlassSwitchRow(R.string.settings_glass_depth_effects_short, R.string.settings_glass_depth_effects_short_description, depthEffects, preview) {
            onSetDepthEffects(!depthEffects)
        }
        val applyTitle = stringResource(R.string.settings_glass_apply_to)
        val applyLine = stringResource(R.string.settings_line_glass_apply)
        val applyValue = SettingValue.Choice(
            if (scope == ALL_GLASS_SURFACES) stringResource(R.string.settings_glass_surface_all)
            else pluralStringResource(R.plurals.settings_surface_count, scope.size, scope.size, ALL_GLASS_SURFACES.size),
        )
        StageSettingRow(
            icon = OwnTVIcon.GRID,
            title = applyTitle,
            desc = applyLine,
            value = applyValue,
            onClick = onOpenSurfaces,
            help = settingHelp(null, applyTitle, stringResource(R.string.settings_glass_surfaces_description), applyValue, pinnable = false).copy(extra = preview),
        )
        val resetTitle = stringResource(R.string.settings_glass_reset_balanced)
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = resetTitle,
            desc = null,
            value = null,
            onClick = onResetBalanced,
            help = settingHelp(null, resetTitle, stringResource(R.string.settings_glass_preset_balanced_description), null, pinnable = false).copy(extra = preview),
        )
    }

    if (pickStyle) {
        PickerDialog(
            title = stringResource(R.string.settings_glass_style),
            options = GlassPreset.entries.mapIndexed { i, p -> p.name to presetLabels[i] },
            selected = preset.name,
            descriptions = GlassPreset.entries.associate { it.name to glassPresetDescription(it) },
            onSelect = { picked -> GlassPreset.entries.firstOrNull { it.name == picked }?.let(onSetPreset); pickStyle = false },
            onDismiss = { pickStyle = false },
        )
    }
}

/** A FINE TUNING row: "46%" in a stepper, ◀ ▶ change it by [step] within [min]…[max]. */
@Composable
private fun GlassStepRow(
    titleRes: Int,
    lineRes: Int,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onSet: (Int) -> Unit,
    hints: List<Pair<String, String>>,
    preview: @Composable () -> Unit,
) {
    val title = stringResource(titleRes)
    val line = stringResource(lineRes)
    StageSettingRow(
        icon = OwnTVIcon.LAYERS,
        title = title,
        desc = line,
        value = SettingValue.Stepper(stringResource(R.string.settings_surface_transparency, value)),
        onClick = { onSet((value + step).let { if (it > max) min else it }) },
        onStep = { dir -> onSet((value + dir * step).coerceIn(min, max)) },
        help = SettingHelp(title, line, hints = hints, extra = preview),
    )
}

@Composable
private fun GlassSwitchRow(titleRes: Int, lineRes: Int, on: Boolean, preview: @Composable () -> Unit, onClick: () -> Unit) {
    val title = stringResource(titleRes)
    val line = stringResource(lineRes)
    val value = SettingValue.Switch(on)
    StageSettingRow(
        icon = OwnTVIcon.LAYERS,
        title = title,
        desc = line,
        value = value,
        onClick = onClick,
        help = settingHelp(null, title, line, value, pinnable = false).copy(extra = preview),
    )
}

/**
 * The panel's live preview (P10B-16): an accent-to-violet backdrop with two Stage glass blocks on it,
 * the smaller one focused. Drawn with the user's current glass, so every change shows here.
 */
@Composable
private fun GlassPreview() {
    val a = stageAccent
    Row(
        Modifier
            .padding(top = 16.mpx)
            .fillMaxWidth()
            .height(150.mpx)
            .background(
                Brush.linearGradient(listOf(a.accent.copy(alpha = 0.35f), Color(0x40785AFF))),
                RoundedCornerShape(20.mpx),
            )
            .padding(18.mpx),
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(2f).height(90.mpx).stageGlass(16.mpx))
        Box(
            Modifier
                .weight(1f)
                .height(60.mpx)
                .drawBehind { drawOuterRing(a.focus, 2.mpx.toPx(), 14.mpx.toPx()) }
                .stageGlass(14.mpx),
        )
    }
}
