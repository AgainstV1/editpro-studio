package com.editpro.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import com.editpro.studio.media.TitleRenderer
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.KeyframeOps
import com.editpro.studio.model.MgTemplate
import com.editpro.studio.model.MotionGraphics
import com.editpro.studio.model.ShortcutBindings
import com.editpro.studio.model.ShortcutChords
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import com.editpro.studio.model.CameraRig
import com.editpro.studio.model.ClipFilters
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.ExportSettings
import com.editpro.studio.model.InspectorTab
import com.editpro.studio.model.LookPreset
import com.editpro.studio.model.PresetCatalog
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.formatFileSize
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.viewmodel.EditorUiState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectorPanel(
    state: EditorUiState,
    onSelectTab: (InspectorTab) -> Unit,
    onSnapHistory: () -> Unit,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onResetClip: () -> Unit,
    onResetColor: () -> Unit,
    onResetTransform: () -> Unit,
    onApplyLook: (LookPreset) -> Unit,
    onApplyMotion: (String) -> Unit,
    onApplyEquipmentRig: (String) -> Unit,
    onApplyAudioGear: (String) -> Unit,
    onAddKeyframe: () -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onClearKeyframes: () -> Unit,
    onSaveMotionPreset: (String) -> Unit,
    onAddVolumePoint: () -> Unit,
    onUpdateVolumePoint: (Int, Double) -> Unit,
    onRemoveVolumePoint: (Int) -> Unit,
    onClearVolumePoints: () -> Unit,
    onDetectBeats: () -> Unit,
    onToggleGradeSplit: () -> Unit,
    onCopyAttrs: () -> Unit,
    onPasteAttrs: () -> Unit,
    onDuplicateClip: () -> Unit,
    onFreezeFrame: () -> Unit,
    onRunHealthScan: () -> Unit,
    onToggleSafeGuides: () -> Unit,
    onSeek: (Double) -> Unit = {},
    onSetKeyframeEasing: (Int, String) -> Unit = { _, _ -> },
    onJumpKeyframe: (Int) -> Unit = {},
    onRemoveKeyframeAtPlayhead: () -> Unit = {},
    onToggleKeyframeLane: () -> Unit = {},
    onOpenGraphics: () -> Unit = {},
    /** Collapses the panel to a rail; null where there is no rail (a phone shows one pane). */
    onCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    // Once a clip is animated the sliders show the values under the playhead (what is on
    // screen), and changing one keys it there.
    val clip = state.inspectedClip
    val scroll = rememberScrollState()

    // Every slider in the panel takes one undo snapshot when a drag on it begins.
    CompositionLocalProvider(LocalEditStart provides onSnapHistory) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(palette.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Panel Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            if (onCollapse != null) {
                PanelToggleButton(glyph = "›", description = "Hide inspector", onClick = onCollapse)
            }
            Text(
                text = if (state.selectionIds.size > 1) "INSPECTOR · ${state.selectionIds.size} CLIPS" else "INSPECTOR",
                color = palette.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SmileIconButton(
                icon = SmileIconType.RESET,
                accessibleLabel = "Reset effects and transform",
                onClick = onResetClip
            )
        }

        if (clip == null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
            ) {
                Text(
                    text = "Select a clip on the timeline to edit its settings. Drag either edge to trim; drag the body to move it.",
                    color = palette.muted,
                    fontSize = 12.sp
                )
                SectionHeader("ADD TO THE TIMELINE")
                InspectorActionButton("＋ Motion graphic…", onClick = onOpenGraphics)
                SectionHeader("PROJECT TOOLS")
                InspectorActionButton("Run project health scan", onClick = onRunHealthScan)
                InspectorActionButton("Toggle safe-area guides", onClick = onToggleSafeGuides)
                val totalBytes = state.assets.sumOf { it.sizeBytes }
                Text(
                    text = "Media assets: ${state.assets.size} (${formatFileSize(totalBytes)}) · Timeline clips: ${state.clips.size} · Duration: ${formatTimecode(state.durationSec)}",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            return@Column
        }

        if (state.selectionIds.size > 1) {
            Text(
                "Several clips are selected. The controls below edit the highlighted one; move, delete, " +
                    "copy and the clip menu act on all of them.",
                color = palette.muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (clip.keyframes.isNotEmpty()) {
            Text(
                "◆ Animated: values are shown at the playhead, and changing one sets a keyframe there.",
                color = Color(0xFFFFD60A),
                fontSize = 10.5.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // 5-Tab Sticky Inspector Bar (Clip, Layout, Motion, Effects, Audio)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(palette.controlBg)
                .padding(2.dp)
        ) {
            InspectorTab.entries.forEach { tab ->
                val active = state.activeInspectorTab == tab
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                ) {
                    Text(
                        text = tab.label,
                        color = if (active) palette.text else palette.muted,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).verticalScroll(scroll)
        ) {
            val dur = TimelineEngine.clipDuration(clip, state.assetsById)

            when (state.activeInspectorTab) {
                InspectorTab.CLIP -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SectionHeader("${clip.track.label} · CLIP")
                        // Drag to retime: left shortens the clip, right lengthens it.
                        DraggableTimecode(
                            seconds = dur,
                            onDragStart = onSnapHistory,
                            onDelta = { delta ->
                                onUpdateClip { c ->
                                    c.copy(trimOut = (c.trimOut + delta).coerceAtLeast(c.trimIn + 0.05))
                                }
                            },
                            secondsPerDp = 0.02,
                            color = Color(0xFF6CB6FF),
                            fontSize = 10.sp,
                            background = Color(0x2E0A84FF)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "START",
                            color = palette.muted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(46.dp)
                        )
                        DraggableTimecode(
                            seconds = clip.startAt,
                            onDragStart = onSnapHistory,
                            onDelta = { delta ->
                                onUpdateClip { c ->
                                    c.copy(startAt = (c.startAt + delta).coerceAtLeast(0.0))
                                }
                            },
                            secondsPerDp = 0.02,
                            color = palette.text,
                            fontSize = 11.sp,
                            background = palette.controlBg
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "END",
                            color = palette.muted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(30.dp)
                        )
                        DraggableTimecode(
                            seconds = clip.startAt + dur,
                            onDragStart = onSnapHistory,
                            onDelta = { delta ->
                                onUpdateClip { c ->
                                    val end = c.startAt + TimelineEngine.clipDuration(c, state.assetsById)
                                    val target = (end + delta).coerceAtLeast(c.startAt + 0.05)
                                    c.copy(trimOut = (c.trimOut + (target - end)).coerceAtLeast(c.trimIn + 0.05))
                                }
                            },
                            secondsPerDp = 0.02,
                            color = palette.text,
                            fontSize = 11.sp,
                            background = palette.controlBg
                        )
                    }

                    OutlinedTextField(
                        value = clip.label,
                        onValueChange = { v -> onUpdateClip { it.copy(label = v) } },
                        label = { Text("Clip name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onSnapHistory() }
                    )
                    if (clip.track.isTitle) {
                        TitleAndGraphicFields(clip = clip, onUpdateClip = onUpdateClip, onSnapHistory = onSnapHistory)
                    }

                    InspectorSlider(
                        label = "Trim In",
                        valueText = "${"%.2f".format(clip.trimIn)}s",
                        value = clip.trimIn.toFloat(),
                        range = 0f..max(0.2f, (clip.trimOut - 0.1).toFloat()),
                        onValueChange = { v -> onUpdateClip { it.copy(trimIn = v.toDouble()) } }
                    )
                    InspectorSlider(
                        label = "Trim Out",
                        valueText = "${"%.2f".format(clip.trimOut)}s",
                        value = clip.trimOut.toFloat(),
                        range = (clip.trimIn + 0.1).toFloat()..max(10f, clip.trimOut.toFloat() + 10f),
                        onValueChange = { v -> onUpdateClip { it.copy(trimOut = v.toDouble()) } }
                    )

                    SectionHeader("CLIP SPEED (${clip.speed}×)")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { sp ->
                            InspectorChip(
                                text = "${sp}×",
                                selected = clip.speed == sp,
                                onClick = { onSnapHistory(); onUpdateClip { it.copy(speed = sp) } }
                            )
                        }
                    }

                    SectionHeader("WORKFLOW")
                    InspectorActionButton("Copy clip attributes", onClick = onCopyAttrs)
                    InspectorActionButton("Paste attributes to selected clip", onClick = onPasteAttrs)
                    InspectorActionButton("Duplicate clip", onClick = onDuplicateClip)
                    InspectorActionButton("Create freeze-frame image", onClick = onFreezeFrame)
                }

                InspectorTab.LAYOUT -> {
                    SectionHeader("TRANSFORM")
                    InspectorActionButton("Reset transform", onClick = onResetTransform)
                    InspectorSlider("Scale", "${clip.scale.roundToInt()}%", clip.scale.toFloat(), 10f..250f) { v ->
                        onUpdateClip { it.copy(scale = v.toDouble()) }
                    }
                    InspectorSlider("Opacity", "${clip.opacity.roundToInt()}%", clip.opacity.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(opacity = v.toDouble()) }
                    }
                    InspectorSlider("Rotation", "${clip.rotation.roundToInt()}°", clip.rotation.toFloat(), -180f..180f) { v ->
                        onUpdateClip { it.copy(rotation = v.toDouble()) }
                    }
                    InspectorSlider("Position X", "${clip.x.roundToInt()}%", clip.x.toFloat(), -100f..100f) { v ->
                        onUpdateClip { it.copy(x = v.toDouble()) }
                    }
                    InspectorSlider("Position Y", "${clip.y.roundToInt()}%", clip.y.toFloat(), -100f..100f) { v ->
                        onUpdateClip { it.copy(y = v.toDouble()) }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InspectorActionButton("Center in frame", modifier = Modifier.weight(1f)) {
                            onSnapHistory()
                            onUpdateClip { it.copy(x = 0.0, y = 0.0) }
                        }
                        InspectorActionButton("Reset transform", modifier = Modifier.weight(1f)) {
                            onSnapHistory()
                            onUpdateClip { it.copy(scale = 100.0, opacity = 100.0, rotation = 0.0, x = 0.0, y = 0.0, flipH = false, flipV = false) }
                        }
                    }

                    SectionHeader("MIRROR, BLEND & CORNERS")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clip.flipH, onCheckedChange = { v -> onSnapHistory(); onUpdateClip { it.copy(flipH = v) } })
                        Text("Flip H", color = palette.text, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Checkbox(checked = clip.flipV, onCheckedChange = { v -> onSnapHistory(); onUpdateClip { it.copy(flipV = v) } })
                        Text("Flip V", color = palette.text, fontSize = 12.sp)
                    }
                    InspectorSlider("Corner Radius", "${clip.cornerRadius}px", clip.cornerRadius.toFloat(), 0f..80f) { v ->
                        onUpdateClip { it.copy(cornerRadius = v.roundToInt()) }
                    }
                    SectionHeader("BLEND MODE")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("normal", "screen", "multiply", "overlay", "lighten").forEach { bm ->
                            InspectorChip(text = bm, selected = clip.blendMode == bm) {
                                onUpdateClip { it.copy(blendMode = bm) }
                            }
                        }
                    }
                }

                InspectorTab.MOTION -> {
                    SectionHeader("BEZIER VELOCITY CURVE (${clip.curveName})")
                    BezierCurveEditorCard(
                        bezier = clip.bezier,
                        onUpdateBezier = { nextBez, label ->
                            onUpdateClip { it.copy(bezier = nextBez, curveName = label) }
                        }
                    )

                    SectionHeader("3D CAMERA RIG & OPTICAL EQUIPMENT")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        state.allEquipmentRigs.keys.take(8).forEach { eqName ->
                            InspectorChip(
                                text = eqName.substringBefore(" ·"),
                                selected = clip.equipmentName == eqName,
                                onClick = { onApplyEquipmentRig(eqName) }
                            )
                        }
                    }
                    val rig = clip.rig
                    InspectorSlider("3D Tilt X (Pitch)", "${rig.tiltX.roundToInt()}°", rig.tiltX.toFloat(), -60f..60f) { v ->
                        onUpdateClip { it.copy(rig = it.rig.copy(tiltX = v.toDouble())) }
                    }
                    InspectorSlider("3D Swivel Y (Yaw)", "${rig.swivelY.roundToInt()}°", rig.swivelY.toFloat(), -60f..60f) { v ->
                        onUpdateClip { it.copy(rig = it.rig.copy(swivelY = v.toDouble())) }
                    }
                    InspectorSlider("Shutter Motion Blur · not rendered yet", "${rig.shutter.roundToInt()}°", rig.shutter.toFloat(), 0f..360f) { v ->
                        onUpdateClip { it.copy(rig = it.rig.copy(shutter = v.toDouble())) }
                    }
                    InspectorSlider("Chromatic Aberration · not rendered yet", "${"%.1f".format(rig.aberration)}px", rig.aberration.toFloat(), 0f..12f) { v ->
                        onUpdateClip { it.copy(rig = it.rig.copy(aberration = v.toDouble())) }
                    }
                    InspectorSlider("Anamorphic Squeeze", "${rig.anamorphic.roundToInt()}%", rig.anamorphic.toFloat(), 0f..35f) { v ->
                        onUpdateClip { it.copy(rig = it.rig.copy(anamorphic = v.toDouble())) }
                    }

                    SectionHeader("MOTION PRESETS · 56+ MOVES")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        PresetCatalog.allMotionNames.forEach { mName ->
                            InspectorChip(text = mName, selected = false) { onApplyMotion(mName) }
                        }
                    }

                    KeyframeEditor(
                        clip = clip,
                        clipStart = state.resolvedClips.find { it.clip.id == clip.id }?.start ?: clip.startAt,
                        playheadSec = state.playheadSec,
                        laneShown = state.showKeyframeLane,
                        bindings = state.bindings,
                        onAddKeyframe = onAddKeyframe,
                        onRemoveKeyframe = onRemoveKeyframe,
                        onRemoveAtPlayhead = onRemoveKeyframeAtPlayhead,
                        onClearKeyframes = onClearKeyframes,
                        onSetEasing = onSetKeyframeEasing,
                        onJump = onJumpKeyframe,
                        onSeek = onSeek,
                        onToggleLane = onToggleKeyframeLane
                    )
                }

                InspectorTab.FX -> {
                    SectionHeader("3-WAY COLOR WHEELS · LIFT / GAMMA / GAIN")
                    ThreeWayColorWheelsCard(
                        filters = clip.filters,
                        onSnapHistory = onSnapHistory,
                        onUpdateFilters = { nf -> onUpdateClip { it.copy(filters = nf) } },
                        onToggleGradeSplit = onToggleGradeSplit
                    )

                    SectionHeader("LOOKS & EFFECTS · 58 PRESETS")
                    var search by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search looks…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        PresetCatalog.lookPresets
                            .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
                            .forEach { preset ->
                                InspectorChip(text = preset.title, selected = false) { onApplyLook(preset) }
                            }
                    }

                    val f = clip.filters
                    SectionHeader("COLOR & FILM FINE-TUNING")
                    InspectorSlider("Brightness", "${f.brightness.roundToInt()}%", f.brightness.toFloat(), 0f..200f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(brightness = v.toDouble())) }
                    }
                    InspectorSlider("Contrast", "${f.contrast.roundToInt()}%", f.contrast.toFloat(), 0f..220f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(contrast = v.toDouble())) }
                    }
                    InspectorSlider("Saturation", "${f.saturation.roundToInt()}%", f.saturation.toFloat(), 0f..220f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(saturation = v.toDouble())) }
                    }
                    InspectorSlider("Blur · not rendered yet", "${"%.1f".format(f.blur)}px", f.blur.toFloat(), 0f..20f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(blur = v.toDouble())) }
                    }
                    InspectorSlider("Temperature", "${f.temperature.roundToInt()}", f.temperature.toFloat(), -100f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(temperature = v.toDouble())) }
                    }
                    InspectorSlider("Tint", "${f.tint.roundToInt()}", f.tint.toFloat(), -100f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(tint = v.toDouble())) }
                    }
                    InspectorSlider("Sharpen", "${f.sharpen.roundToInt()}", f.sharpen.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(sharpen = v.toDouble())) }
                    }
                    InspectorSlider("Vignette", "${f.vignette.roundToInt()}", f.vignette.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(vignette = v.toDouble())) }
                    }
                    InspectorSlider("Hue Shift · not rendered yet", "${f.hue.roundToInt()}°", f.hue.toFloat(), -180f..180f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(hue = v.toDouble())) }
                    }
                    InspectorSlider("Sepia", "${f.sepia.roundToInt()}%", f.sepia.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(sepia = v.toDouble())) }
                    }
                    InspectorSlider("Invert", "${f.invert.roundToInt()}%", f.invert.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(invert = v.toDouble())) }
                    }
                    InspectorSlider("Film Grain · not rendered yet", "${f.grain.roundToInt()}", f.grain.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(filters = it.filters.copy(grain = v.toDouble())) }
                    }

                    InspectorActionButton("Reset colour & effects", onClick = onResetColor)
                }

                InspectorTab.AUDIO -> {
                    SectionHeader("TRANSITIONS & GAIN")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("none", "fade", "dissolve", "wipe", "slide", "zoom").forEach { tr ->
                            InspectorChip(text = tr, selected = clip.transition == tr) {
                                onUpdateClip { it.copy(transition = tr) }
                            }
                        }
                    }
                    InspectorSlider("Transition Duration", "${"%.2f".format(clip.transitionDuration)}s", clip.transitionDuration.toFloat(), 0.1f..2.0f) { v ->
                        onUpdateClip { it.copy(transitionDuration = v.toDouble()) }
                    }
                    InspectorSlider("Audio Volume", "${clip.volume.roundToInt()}%", clip.volume.toFloat(), 0f..200f) { v ->
                        onUpdateClip { it.copy(volume = v.toDouble()) }
                    }

                    SectionHeader("STUDIO MASTERING & 5-BAND PARAMETRIC EQ")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        state.allAudioGear.keys.take(8).forEach { gName ->
                            InspectorChip(
                                text = gName,
                                selected = clip.audioPresetName == gName,
                                onClick = { onApplyAudioGear(gName) }
                            )
                        }
                    }
                    FiveBandEqEditorCard(
                        eq = clip.audioEq,
                        onUpdateBand = { bandIdx, db ->
                            val nextEq = clip.audioEq.toMutableList().apply { this[bandIdx] = db }
                            onUpdateClip { it.copy(audioEq = nextEq) }
                        }
                    )

                    InspectorSlider("Stereo Pan", "${clip.audioPan.roundToInt()}", clip.audioPan.toFloat(), -100f..100f) { v ->
                        onUpdateClip { it.copy(audioPan = v.toDouble()) }
                    }
                    InspectorSlider("Bus Compressor / Glue", "${clip.audioComp.roundToInt()}%", clip.audioComp.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(audioComp = v.toDouble()) }
                    }
                    InspectorSlider("Vocal De-Esser & Clarity", "${clip.audioDeess.roundToInt()}%", clip.audioDeess.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(audioDeess = v.toDouble()) }
                    }
                    InspectorSlider("Studio Reverb / Ambience", "${clip.audioReverb.roundToInt()}%", clip.audioReverb.toFloat(), 0f..100f) { v ->
                        onUpdateClip { it.copy(audioReverb = v.toDouble()) }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InspectorActionButton("♫ Detect Beats → Markers", isPrimary = true, modifier = Modifier.weight(1f), onClick = onDetectBeats)
                        InspectorActionButton("Reset EQ", modifier = Modifier.weight(1f)) {
                            onUpdateClip { it.copy(audioEq = listOf(0.0, 0.0, 0.0, 0.0, 0.0), audioPan = 0.0, audioComp = 0.0, audioDeess = 0.0, audioReverb = 0.0) }
                        }
                    }

                    SectionHeader("AUDIO FADES & AUTOMATION")
                    InspectorSlider("Fade In", "${"%.2f".format(clip.fadeInAudio)}s", clip.fadeInAudio.toFloat(), 0f..10f) { v ->
                        onUpdateClip { it.copy(fadeInAudio = v.toDouble()) }
                    }
                    InspectorSlider("Fade Out", "${"%.2f".format(clip.fadeOutAudio)}s", clip.fadeOutAudio.toFloat(), 0f..10f) { v ->
                        onUpdateClip { it.copy(fadeOutAudio = v.toDouble()) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InspectorActionButton("＋ Volume point here", modifier = Modifier.weight(1f), onClick = onAddVolumePoint)
                        InspectorActionButton("Clear automation", modifier = Modifier.weight(1f), onClick = onClearVolumePoints)
                    }
                    clip.volumeKeyframes.forEachIndexed { idx, vp ->
                        InspectorSlider(
                            label = "Point @ ${"%.2f".format(vp.time)}s",
                            valueText = "${vp.value.roundToInt()}%",
                            value = vp.value.toFloat(),
                            range = 0f..200f,
                            onValueChange = { v -> onUpdateVolumePoint(idx, v.toDouble()) }
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ThreeWayColorWheelsCard(
    filters: ClipFilters,
    onSnapHistory: () -> Unit,
    onUpdateFilters: (ClipFilters) -> Unit,
    onToggleGradeSplit: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ColorWheelControl(
                title = "LIFT",
                rOffset = filters.liftR,
                bOffset = filters.liftB,
                luma = filters.liftY,
                onSnap = onSnapHistory,
                onChange = { r, g, b, y -> onUpdateFilters(filters.copy(liftR = r, liftG = g, liftB = b, liftY = y)) },
                modifier = Modifier.weight(1f)
            )
            ColorWheelControl(
                title = "GAMMA",
                rOffset = filters.gammaR,
                bOffset = filters.gammaB,
                luma = filters.gammaY,
                onSnap = onSnapHistory,
                onChange = { r, g, b, y -> onUpdateFilters(filters.copy(gammaR = r, gammaG = g, gammaB = b, gammaY = y)) },
                modifier = Modifier.weight(1f)
            )
            ColorWheelControl(
                title = "GAIN",
                rOffset = filters.gainR,
                bOffset = filters.gainB,
                luma = filters.gainY,
                onSnap = onSnapHistory,
                onChange = { r, g, b, y -> onUpdateFilters(filters.copy(gainR = r, gainG = g, gainB = b, gainY = y)) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorActionButton("Reset Wheels", modifier = Modifier.weight(1f)) {
                onSnapHistory()
                onUpdateFilters(
                    filters.copy(
                        liftR = 0.0, liftG = 0.0, liftB = 0.0, liftY = 0.0,
                        gammaR = 0.0, gammaG = 0.0, gammaB = 0.0, gammaY = 0.0,
                        gainR = 0.0, gainG = 0.0, gainB = 0.0, gainY = 0.0
                    )
                )
            }
            InspectorActionButton("Split Wipe", modifier = Modifier.weight(1f), onClick = onToggleGradeSplit)
        }
    }
}

@Composable
private fun ColorWheelControl(
    title: String,
    rOffset: Double,
    bOffset: Double,
    luma: Double,
    onSnap: () -> Unit,
    onChange: (Double, Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(title, color = palette.muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .pointerInput(title) {
                    detectDragGestures(
                        onDragStart = { onSnap() },
                        onDrag = { change, _ ->
                            change.consume()
                            val nx = ((change.position.x / size.width - 0.5f) * 2f).coerceIn(-1f, 1f)
                            val ny = ((change.position.y / size.height - 0.5f) * 2f).coerceIn(-1f, 1f)
                            val nr = (nx * 35.0).roundToInt().toDouble()
                            val nb = (ny * 35.0).roundToInt().toDouble()
                            val ng = ((-nx - ny) * 18.0).roundToInt().toDouble()
                            onChange(nr, ng, nb, luma)
                        }
                    )
                }
        ) {
            val r = size.width / 2f
            for (angle in 0 until 360 step 10) {
                val rad = Math.toRadians(angle.toDouble())
                val col = Color.hsv(angle.toFloat(), 0.7f, 0.65f, 0.45f)
                drawLine(
                    color = col,
                    start = Offset(r, r),
                    end = Offset(r + (cos(rad) * r).toFloat(), r + (sin(rad) * r).toFloat()),
                    strokeWidth = 4.dp.toPx()
                )
            }
            drawLine(Color.White.copy(alpha = 0.2f), Offset(r, 4f), Offset(r, size.height - 4f), 1f)
            drawLine(Color.White.copy(alpha = 0.2f), Offset(4f, r), Offset(size.width - 4f, r), 1f)
            val px = r + (rOffset.toFloat() / 40f) * (r - 6f)
            val py = r + (bOffset.toFloat() / 40f) * (r - 6f)
            drawCircle(Color.White, radius = 4.5.dp.toPx(), center = Offset(px, py))
        }
        Slider(
            value = luma.toFloat(),
            onValueChange = { v -> onChange(rOffset, (-rOffset - bOffset) * 0.5, bOffset, v.toDouble()) },
            valueRange = -50f..50f,
            modifier = Modifier.height(22.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BezierCurveEditorCard(
    bezier: List<Double>,
    onUpdateBezier: (List<Double>, String) -> Unit
) {
    val palette = LocalEditProPalette.current
    val pts = if (bezier.size == 4) bezier else listOf(0.42, 0.0, 0.58, 1.0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.24f))
            .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF090A0E))
                .pointerInput(pts) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val pad = 14f
                        val nx = ((change.position.x - pad) / max(1f, size.width - pad * 2f)).coerceIn(0.02f, 0.98f).toDouble()
                        val ny = (1f - (change.position.y - pad) / max(1f, size.height - pad * 2f)).coerceIn(-0.4f, 1.4f).toDouble()
                        val next = pts.toMutableList()
                        if (change.position.x < size.width / 2f) {
                            next[0] = (nx * 100.0).roundToInt() / 100.0
                            next[1] = (ny * 100.0).roundToInt() / 100.0
                        } else {
                            next[2] = (nx * 100.0).roundToInt() / 100.0
                            next[3] = (ny * 100.0).roundToInt() / 100.0
                        }
                        onUpdateBezier(next, "Custom Bezier")
                    }
                }
        ) {
            val pad = 14.dp.toPx()
            val w = size.width - pad * 2f
            val h = size.height - pad * 2f
            fun toX(v: Double) = pad + (v.toFloat() * w)
            fun toY(v: Double) = size.height - pad - (v.toFloat() * h)

            drawLine(Color(0x88BF5AF2), Offset(toX(0.0), toY(0.0)), Offset(toX(pts[0]), toY(pts[1])), 1.5.dp.toPx())
            drawLine(Color(0x88BF5AF2), Offset(toX(1.0), toY(1.0)), Offset(toX(pts[2]), toY(pts[3])), 1.5.dp.toPx())

            val path = Path().apply {
                moveTo(toX(0.0), toY(0.0))
                for (i in 1..40) {
                    val u = i / 40.0
                    val v = TimelineEngine.evalCubicBezier(u, pts[0], pts[1], pts[2], pts[3])
                    lineTo(toX(u), toY(v))
                }
            }
            drawPath(path, Color(0xFF0A84FF), style = Stroke(width = 2.2.dp.toPx()))
            drawCircle(Color(0xFFFF9F0A), radius = 5.dp.toPx(), center = Offset(toX(pts[0]), toY(pts[1])))
            drawCircle(Color(0xFF64D2FF), radius = 5.dp.toPx(), center = Offset(toX(pts[2]), toY(pts[3])))
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PresetCatalog.bezierCurvePresets.forEach { (name, curve) ->
                InspectorChip(text = name, selected = false) {
                    onUpdateBezier(curve, name)
                }
            }
        }
    }
}

@Composable
private fun FiveBandEqEditorCard(
    eq: List<Double>,
    onUpdateBand: (Int, Double) -> Unit
) {
    val palette = LocalEditProPalette.current
    val bands = if (eq.size == 5) eq else listOf(0.0, 0.0, 0.0, 0.0, 0.0)
    val labels = listOf("60Hz", "250Hz", "1kHz", "4kHz", "12kHz")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.24f))
            .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF090A0E))
        ) {
            val mid = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, mid), Offset(size.width, mid), 1f)
            val path = Path()
            val pts = bands.mapIndexed { i, db ->
                val x = 18f + i * ((size.width - 36f) / 4f)
                val y = mid - (db.toFloat() / 12f) * (mid - 10f)
                Offset(x, y)
            }
            path.moveTo(0f, pts.first().y)
            pts.forEach { p -> path.lineTo(p.x, p.y) }
            path.lineTo(size.width, pts.last().y)
            drawPath(path, Color(0xFF30D158), style = Stroke(width = 2.2.dp.toPx()))
            pts.forEach { p ->
                drawCircle(Color(0xFF64D2FF), radius = 4.dp.toPx(), center = p)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        labels.forEachIndexed { idx, freq ->
            val v = bands[idx]
            InspectorSlider(
                label = "$freq Band",
                valueText = "${if (v > 0) "+" else ""}${"%.1f".format(v)} dB",
                value = v.toFloat(),
                range = -12f..12f,
                onValueChange = { nv -> onUpdateBand(idx, ((nv * 2f).roundToInt() / 2.0)) }
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    val palette = LocalEditProPalette.current
    Text(
        text = title,
        color = palette.muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

/**
 * Called once when a slider drag begins. The inspector provides "take an undo snapshot" here, so
 * a whole drag is one undo step instead of none at all.
 */
val LocalEditStart = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun InspectorSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val palette = LocalEditProPalette.current
    val onEditStart = LocalEditStart.current
    var dragging by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, color = palette.text, fontSize = 11.5.sp)
            Text(text = valueText, color = palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                if (!dragging) {
                    dragging = true
                    onEditStart()
                }
                onValueChange(v)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.line
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

private val SWATCHES = listOf(
    "#ffffff", "#000000", "#ffd60a", "#ff9f0a", "#ff3b30", "#ff375f", "#bf5af2", "#0a84ff", "#64d2ff", "#30d158"
)

/** A row of colour swatches plus a hex field, for text and accent colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatchRow(label: String, value: String, onPick: (String) -> Unit) {
    val palette = LocalEditProPalette.current
    var hex by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = palette.text, fontSize = 11.5.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SWATCHES.forEach { swatch ->
                val selected = swatch.equals(value, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(swatch))
                        .border(
                            width = if (selected) 2.dp else 0.5.dp,
                            color = if (selected) palette.accent else palette.line,
                            shape = CircleShape
                        )
                        .clickable(role = Role.Button) { onPick(swatch) }
                )
            }
            BasicTextField(
                value = hex,
                onValueChange = { typed ->
                    hex = typed.take(7)
                    if (Regex("#[0-9a-fA-F]{6}").matches(hex)) onPick(hex.lowercase())
                },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier
                    .width(74.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.controlBg)
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            )
        }
    }
}

/** Text, colours, position and — for motion graphics — the template's own settings. */
@Composable
private fun TitleAndGraphicFields(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onSnapHistory: () -> Unit
) {
    val palette = LocalEditProPalette.current
    val template: MgTemplate? = if (clip.isGraphic) MotionGraphics.find(clip.graphic) else null
    val isShape = clip.graphic.startsWith("shape_")
    if (clip.isGraphic) {
        SectionHeader("MOTION GRAPHIC · ${(template?.title ?: clip.graphic).uppercase()}")
        template?.description?.let { Text(it, color = palette.muted, fontSize = 11.sp) }
    }
    if (!isShape) {
        OutlinedTextField(
            value = clip.text,
            onValueChange = { v -> onUpdateClip { it.copy(text = v) } },
            label = { Text(if (clip.isGraphic) "Text" else "Title / Subtitle Text") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onSnapHistory() }
        )
        template?.textHint?.takeIf { it.isNotEmpty() }?.let { Text(it, color = palette.muted, fontSize = 10.5.sp) }
        InspectorSlider(
            label = "Font Size",
            valueText = "${clip.fontSize}px",
            value = clip.fontSize.toFloat(),
            range = 12f..200f,
            onValueChange = { v -> onUpdateClip { it.copy(fontSize = v.roundToInt()) } }
        )
        ColorSwatchRow("Text colour", clip.textColor) { picked ->
            onSnapHistory()
            onUpdateClip { it.copy(textColor = picked) }
        }
    }
    if (clip.isGraphic) {
        ColorSwatchRow(if (isShape) "Colour" else "Accent colour", clip.accentColor) { picked ->
            onSnapHistory()
            onUpdateClip { it.copy(accentColor = picked) }
        }
    }
    if (!clip.isGraphic || isShape) {
        InspectorSlider(
            label = if (isShape) "Outline only (0 = filled)" else "Outline Width",
            valueText = "${clip.strokeWidth.roundToInt()}px",
            value = clip.strokeWidth,
            range = 0f..8f,
            onValueChange = { v -> onUpdateClip { it.copy(strokeWidth = v) } }
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("top" to "Top", "center" to "Middle", "bottom" to "Bottom").forEach { (pos, lbl) ->
            InspectorActionButton(
                text = lbl,
                isPrimary = clip.verticalPosition == pos,
                modifier = Modifier.weight(1f)
            ) {
                onSnapHistory()
                onUpdateClip { it.copy(verticalPosition = pos) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("left" to "Left", "center" to "Centre", "right" to "Right").forEach { (align, lbl) ->
            InspectorActionButton(
                text = lbl,
                isPrimary = clip.textAlign == align,
                modifier = Modifier.weight(1f)
            ) {
                onSnapHistory()
                onUpdateClip { it.copy(textAlign = align) }
            }
        }
    }
    if (clip.isGraphic) {
        InspectorSlider(
            label = "Intro / outro length",
            valueText = "${"%.2f".format(clip.transitionDuration)}s",
            value = clip.transitionDuration.toFloat(),
            range = 0f..2f,
            onValueChange = { v -> onUpdateClip { it.copy(transitionDuration = v.toDouble()) } }
        )
        if (template?.usesBox == true) {
            InspectorSlider(
                label = "Width",
                valueText = "${"%.1f".format(clip.boxW)}%",
                value = clip.boxW.toFloat(),
                range = 0.2f..100f,
                onValueChange = { v -> onUpdateClip { it.copy(boxW = v.toDouble()) } }
            )
            InspectorSlider(
                label = "Height",
                valueText = "${"%.1f".format(clip.boxH)}%",
                value = clip.boxH.toFloat(),
                range = 0.2f..100f,
                onValueChange = { v -> onUpdateClip { it.copy(boxH = v.toDouble()) } }
            )
        }
        Text(
            "Move, scale, rotate and fade it in the Layout tab; it can be keyframed like any clip.",
            color = palette.muted,
            fontSize = 10.5.sp
        )
    }
}

/**
 * The keyframe list for the selected clip: add or update at the playhead, step between
 * keyframes, pick how each one eases into the next, and jump to any of them with a tap.
 */
@Composable
private fun KeyframeEditor(
    clip: TimelineClip,
    clipStart: Double,
    playheadSec: Double,
    laneShown: Boolean,
    bindings: ShortcutBindings,
    onAddKeyframe: () -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onRemoveAtPlayhead: () -> Unit,
    onClearKeyframes: () -> Unit,
    onSetEasing: (Int, String) -> Unit,
    onJump: (Int) -> Unit,
    onSeek: (Double) -> Unit,
    onToggleLane: () -> Unit
) {
    val palette = LocalEditProPalette.current
    val keys = clip.keyframes.sortedBy { it.time }
    val localT = playheadSec - clipStart
    val atPlayhead = KeyframeOps.indexAt(clip, localT)

    SectionHeader("KEYFRAMES · ${keys.size}")
    Text(
        "Put the playhead where a change should happen, then set Scale, Position, Rotation or Opacity " +
            "in the Layout tab. With at least one keyframe on the clip, the change is keyed automatically.",
        color = palette.muted,
        fontSize = 10.5.sp
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorActionButton("◀", modifier = Modifier.width(44.dp)) { onJump(-1) }
        InspectorActionButton(
            text = (if (atPlayhead >= 0) "◆ Update keyframe" else "◆ Add keyframe") +
                (bindings.hint(EditorShortcut.ADD_KEYFRAME)?.let { "  ·  $it" } ?: ""),
            isPrimary = true,
            modifier = Modifier.weight(1f),
            onClick = onAddKeyframe
        )
        InspectorActionButton("▶", modifier = Modifier.width(44.dp)) { onJump(1) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorActionButton("Delete here", modifier = Modifier.weight(1f), onClick = onRemoveAtPlayhead)
        InspectorActionButton("Clear all", modifier = Modifier.weight(1f), onClick = onClearKeyframes)
    }
    InspectorActionButton(
        text = if (laneShown) "Keyframe lane in timeline: On" else "Keyframe lane in timeline: Off",
        onClick = onToggleLane
    )
    keys.forEachIndexed { idx, kf ->
        val here = idx == atPlayhead
        val easingLabel = KeyframeOps.EASINGS.firstOrNull { it.first == kf.easing }?.second ?: "Clip curve"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(if (here) Color(0x33FFD60A) else palette.controlBg)
                .clickable(role = Role.Button) { onSeek(clipStart + kf.time) }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("◆ ${formatTimecode(kf.time)}", color = Color(0xFFFFD60A), fontSize = 11.sp)
                Text(
                    "${kf.scale.roundToInt()}% · x${kf.x.roundToInt()} y${kf.y.roundToInt()} · " +
                        "${kf.rotation.roundToInt()}° · ${kf.opacity.roundToInt()}% opacity",
                    color = palette.muted,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Tap to step through the easing choices for the move that starts at this keyframe.
            Text(
                text = easingLabel,
                color = palette.text,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(palette.panel2)
                    .border(0.5.dp, palette.line, RoundedCornerShape(99.dp))
                    .clickable(role = Role.Button) {
                        val order = KeyframeOps.EASINGS.map { it.first }
                        val next = order[(order.indexOf(kf.easing).coerceAtLeast(0) + 1) % order.size]
                        onSetEasing(idx, next)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                "×",
                color = palette.red,
                fontSize = 14.sp,
                modifier = Modifier.clickable(role = Role.Button) { onRemoveKeyframe(idx) }.padding(6.dp)
            )
        }
    }
}

@Composable
fun InspectorChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) palette.accent else palette.controlBg)
            .border(0.5.dp, if (selected) palette.accent else palette.line, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else palette.text,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun InspectorActionButton(
    text: String,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPrimary) palette.accent else palette.controlBg)
            .border(0.5.dp, if (isPrimary) palette.accent else palette.line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = if (isPrimary) Color.White else palette.text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// =============================================================================
// MODALS: EXPORT, TOOLS, SOLID COLOUR, PRESET & EQUIPMENT HUB, ALERT SHEET
// =============================================================================
@Composable
fun ExportVideoDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onUpdateSettings: ((ExportSettings) -> ExportSettings) -> Unit,
    onStartExport: () -> Unit,
    onQueueExport: () -> Unit,
    onRunQueue: () -> Unit,
    onOpenExport: (String) -> Unit = {},
    onShareExport: (String) -> Unit = {},
    onCancelExport: () -> Unit = {}
) {
    val palette = LocalEditProPalette.current
    val es = state.exportSettings
    var showOptions by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Export", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            val exportedPath = state.lastExportedFilePath
            val exportedInfo = remember(exportedPath) {
                exportedPath?.let { p ->
                    val f = java.io.File(p)
                    if (f.exists()) f.name to f.length() else null
                }
            }
            val justFinished = !state.isExporting && state.exportProgressPct >= 100 &&
                exportedPath != null && exportedInfo != null

            if (!justFinished) {
                val aspectLabel = when (es.aspect) {
                    "portrait" -> "9:16"
                    "square" -> "1:1"
                    "classic" -> "4:3"
                    else -> "16:9"
                }
                val resLabel = when (es.resolution) {
                    "854x480" -> "480p"
                    "1920x1080" -> "1080p"
                    else -> "720p"
                }
                Text(
                    "$resLabel · ${es.fps} fps · $aspectLabel · ${formatTimecode(state.durationSec)}",
                    color = palette.muted,
                    fontSize = 12.sp
                )

                if (showOptions) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SectionHeader("ASPECT RATIO")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("landscape" to "16:9", "portrait" to "9:16", "square" to "1:1", "classic" to "4:3").forEach { (id, lbl) ->
                                InspectorChip(lbl, selected = es.aspect == id) {
                                    onUpdateSettings { it.copy(aspect = id) }
                                }
                            }
                        }
                        SectionHeader("RESOLUTION")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("854x480" to "480p", "1280x720" to "720p", "1920x1080" to "1080p").forEach { (res, lbl) ->
                                InspectorChip(lbl, selected = es.resolution == res) {
                                    onUpdateSettings { it.copy(resolution = res) }
                                }
                            }
                        }
                        SectionHeader("FRAME RATE")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(24, 25, 30, 60).forEach { fps ->
                                InspectorChip("${fps}fps", selected = es.fps == fps) {
                                    onUpdateSettings { it.copy(fps = fps) }
                                }
                            }
                        }
                        InspectorSlider("Quality (CRF · lower = better)", "${es.crf}", es.crf.toFloat(), 18f..32f) { v ->
                            onUpdateSettings { it.copy(crf = v.roundToInt()) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = es.androidLowRamGuard,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(androidLowRamGuard = v) } }
                            )
                            Text("Memory guard on low-RAM devices", color = palette.text, fontSize = 11.5.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InspectorActionButton("Add to queue", modifier = Modifier.weight(1f), onClick = onQueueExport)
                            InspectorActionButton("Run queue (${state.renderQueue.size})", modifier = Modifier.weight(1f), onClick = onRunQueue)
                        }
                    }
                }

                if (state.isExporting) {
                    LinearProgressIndicator(
                        progress = { (state.exportProgressPct / 100f).coerceIn(0f, 1f) },
                        color = palette.accent,
                        trackColor = palette.controlBg,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp))
                    )
                    Text(state.exportStatusText, color = palette.muted, fontSize = 11.sp, maxLines = 2)
                }
            }

            // Post-export: the render is already saved to the gallery, so the only choices
            // left are sharing it or closing.
            if (justFinished && exportedPath != null && exportedInfo != null) {
                Text("Saved to Photos", color = palette.green, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${exportedInfo.first} · ${formatFileSize(exportedInfo.second)}",
                    color = palette.muted,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    InspectorActionButton("Close", modifier = Modifier.weight(1f), onClick = onDismiss)
                    InspectorActionButton("Share", isPrimary = true, modifier = Modifier.weight(1f)) {
                        onShareExport(exportedPath)
                    }
                }
            }

            if (!justFinished) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (state.isExporting) {
                        // One action at a time: while rendering, the primary button becomes Cancel.
                        InspectorActionButton(
                            text = "Cancel export",
                            modifier = Modifier.weight(1f),
                            onClick = onCancelExport
                        )
                    } else {
                        InspectorActionButton(
                            text = if (showOptions) "Hide options" else "Options",
                            modifier = Modifier.weight(1f)
                        ) { showOptions = !showOptions }
                        InspectorActionButton(
                            text = "Export",
                            isPrimary = true,
                            modifier = Modifier.weight(1f),
                            onClick = onStartExport
                        )
                    }
                }
            }
        }
    }
}

/**
 * Everything that does not earn a permanent button. Each row runs through the same action the
 * keyboard uses and shows the key currently bound to it, so the panel doubles as a reminder of
 * the shortcuts.
 */
@Composable
fun EditingToolsDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onRun: (EditorShortcut) -> Unit,
    onOpenSolidModal: () -> Unit,
    onImportSrtClick: () -> Unit,
    onMoveToPlayhead: () -> Unit,
    onToggleSafeGuides: () -> Unit,
    onToggleChecker: () -> Unit,
    onHealthScan: () -> Unit,
    onClearTimeline: () -> Unit,
    onClearMarkers: () -> Unit,
    onCollapseEmptyTracks: () -> Unit,
    onSelectUnusedMedia: () -> Unit,
    onToggleAutoFit: () -> Unit,
    onToggleCompact: () -> Unit,
    onOpenClipMenu: () -> Unit,
    onOpenAgentAccess: () -> Unit
) {
    val palette = LocalEditProPalette.current

    fun withKey(text: String, action: EditorShortcut): String =
        state.bindings.hint(action)?.let { "$text  ·  $it" } ?: text

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = minOf(600.dp, rememberDialogMaxHeight()))
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // A row that closes the panel and runs an action.
            @Composable
            fun Tool(text: String, action: EditorShortcut, primary: Boolean = false) {
                InspectorActionButton(withKey(text, action), isPrimary = primary) {
                    onDismiss()
                    onRun(action)
                }
            }

            // A row for a setting, which stays open so several can be flipped in one visit.
            @Composable
            fun Setting(text: String, on: Boolean, action: EditorShortcut? = null, onToggle: (() -> Unit)? = null) {
                val label = "$text: ${if (on) "On" else "Off"}"
                InspectorActionButton(if (action != null) withKey(label, action) else label) {
                    if (action != null) onRun(action) else onToggle?.invoke()
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Editing tools", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("✕", color = palette.muted, modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp))
            }
            Text("Extra controls stay here so the main workspace stays clean.", color = palette.muted, fontSize = 12.sp)

            Tool("Editing assistant · AI edits with your own key", EditorShortcut.ASSISTANT, primary = true)
            Tool("Keyboard shortcuts · view and change", EditorShortcut.SHORTCUTS)
            InspectorActionButton("Customise clip menu") { onDismiss(); onOpenClipMenu() }
            InspectorActionButton(
                if (state.mcpRunning) "Agent access (MCP): running" else "Agent access (MCP)"
            ) { onDismiss(); onOpenAgentAccess() }

            SectionHeader("ADD")
            Tool("Motion graphic", EditorShortcut.ADD_GRAPHIC)
            Tool("Text clip", EditorShortcut.ADD_TEXT)
            InspectorActionButton("Solid colour clip") { onDismiss(); onOpenSolidModal() }
            InspectorActionButton("Subtitles from an SRT file") { onDismiss(); onImportSrtClick() }
            Tool("Import media", EditorShortcut.IMPORT_MEDIA)

            SectionHeader("EDIT AT THE PLAYHEAD")
            Tool("Split every track", EditorShortcut.SPLIT_ALL)
            Tool("Trim start to playhead", EditorShortcut.TRIM_START)
            Tool("Trim end to playhead", EditorShortcut.TRIM_END)
            Tool("Ripple trim start", EditorShortcut.RIPPLE_TRIM_START)
            Tool("Ripple trim end", EditorShortcut.RIPPLE_TRIM_END)
            InspectorActionButton("Move selection to playhead") { onDismiss(); onMoveToPlayhead() }

            SectionHeader("SELECTION")
            Tool("Copy", EditorShortcut.COPY)
            Tool("Cut", EditorShortcut.CUT)
            Tool("Paste at playhead", EditorShortcut.PASTE)
            Tool("Duplicate", EditorShortcut.DUPLICATE)
            Tool("Ripple delete", EditorShortcut.RIPPLE_DELETE)
            Tool("Detach audio from video", EditorShortcut.DETACH_AUDIO)
            Tool("Close gaps", EditorShortcut.CLOSE_GAPS)
            Tool("Select all clips", EditorShortcut.SELECT_ALL)
            Tool("Select everything after the playhead", EditorShortcut.SELECT_FORWARD)
            Tool("Deselect", EditorShortcut.DESELECT)
            Setting("Multi-select (tap adds to selection)", state.selectionMode, EditorShortcut.SELECTION_MODE)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorActionButton("Nudge −1 frame", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.NUDGE_LEFT) }
                InspectorActionButton("Nudge +1 frame", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.NUDGE_RIGHT) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorActionButton("Select prev clip", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.SELECT_PREV) }
                InspectorActionButton("Select next clip", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.SELECT_NEXT) }
            }

            SectionHeader("MARKERS & EXPORT RANGE")
            Tool("Add marker at playhead", EditorShortcut.ADD_MARKER)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorActionButton("Prev marker", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.PREV_MARKER) }
                InspectorActionButton("Next marker", modifier = Modifier.weight(1f)) { onRun(EditorShortcut.NEXT_MARKER) }
            }
            Tool("Set export In point here", EditorShortcut.MARK_IN)
            Tool("Set export Out point here", EditorShortcut.MARK_OUT)
            Tool("Clear export range", EditorShortcut.CLEAR_RANGE)
            InspectorActionButton("Delete all markers") { onDismiss(); onClearMarkers() }

            SectionHeader("PANELS & VIEW")
            Setting("Media library", !state.libraryCollapsed, EditorShortcut.TOGGLE_LIBRARY)
            Setting("Inspector", !state.inspectorCollapsed, EditorShortcut.TOGGLE_INSPECTOR)
            Setting("Timeline", !state.timelineCollapsed, EditorShortcut.TOGGLE_TIMELINE)
            Setting("Maximised preview", state.previewMaximized, EditorShortcut.MAXIMIZE_PREVIEW)
            Tool("Full screen", EditorShortcut.FULLSCREEN)
            Setting("Keyframe lane", state.showKeyframeLane, EditorShortcut.KEYFRAME_LANE)
            Tool("Collapse / expand all tracks", EditorShortcut.COLLAPSE_TRACKS)
            InspectorActionButton("Collapse empty tracks") { onCollapseEmptyTracks() }
            Setting("Magnetic snapping", state.magneticSnap, EditorShortcut.TOGGLE_SNAP)
            Setting("Fit timeline to window", state.timelineAutoFit, onToggle = onToggleAutoFit)
            Setting("Compact timeline rows", state.timelineCompact, onToggle = onToggleCompact)
            Setting("Follow playhead", state.timelineFollowPlayhead, EditorShortcut.TOGGLE_FOLLOW)
            Setting("Playhead follows cursor", state.scrubFollow, EditorShortcut.TOGGLE_SCRUB)
            Setting("Safe-area guides", state.safeGuidesOn, onToggle = onToggleSafeGuides)
            Setting("Checkerboard background", state.checkerboardOn, onToggle = onToggleChecker)
            Setting("Light theme", state.isLightTheme, EditorShortcut.TOGGLE_THEME)

            SectionHeader("PROJECT")
            Tool("Save now", EditorShortcut.SAVE)
            Tool("Export project file (.filmcraft)", EditorShortcut.EXPORT_PROJECT_FILE)
            Tool("Import project file", EditorShortcut.OPEN_PROJECT_FILE)
            Tool("Preset packs · import / export", EditorShortcut.PRESETS)
            Tool("Back to all projects", EditorShortcut.HOME)
            InspectorActionButton("Project health scan") { onDismiss(); onHealthScan() }
            InspectorActionButton("Select unused media") { onDismiss(); onSelectUnusedMedia() }
            InspectorActionButton("Clear timeline") { onDismiss(); onClearTimeline() }
        }
    }
}

@Composable
fun SolidColorClipDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Double) -> Unit
) {
    val palette = LocalEditProPalette.current
    var name by remember { mutableStateOf("Solid #2678FF") }
    var hex by remember { mutableStateOf("#2678FF") }
    var durText by remember { mutableStateOf("5.0") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Solid colour clip", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("#2678FF", "#FF453A", "#30D158", "#FF9F0A", "#BF5AF2", "#111216").forEach { colHex ->
                    val col = Color(android.graphics.Color.parseColor(colHex))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(col)
                            .border(if (hex == colHex) 2.dp else 0.5.dp, Color.White, CircleShape)
                            .clickable {
                                hex = colHex
                                name = "Solid $colHex"
                            }
                    )
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Clip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = durText, onValueChange = { durText = it }, label = { Text("Duration (s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Cancel", modifier = Modifier.width(90.dp), onClick = onDismiss)
                Spacer(modifier = Modifier.width(8.dp))
                InspectorActionButton("Add colour clip", isPrimary = true, modifier = Modifier.width(140.dp)) {
                    onCreate(name, hex, durText.toDoubleOrNull() ?: 5.0)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetPacksHubDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onSaveStylePreset: (String) -> Unit,
    onSaveEquipmentRig: (String) -> Unit,
    onLoadExpansionPack: () -> Unit,
    onHotImportJson: (String) -> Unit,
    onApplyMotion: (String) -> Unit,
    onApplyEquipmentRig: (String) -> Unit,
    onApplyAudioGear: (String) -> Unit,
    onExportBundleClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var pasteJson by remember { mutableStateOf("") }
    var newPresetName by remember { mutableStateOf("My Studio Look") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = minOf(580.dp, rememberDialogMaxHeight()))
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Preset & Equipment Pack Hub", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Hot-import or export portable JSON bundles containing Colour Looks, Motion Collections, 3D Camera Rigs, and Studio Audio Gear—without rebuilding the app.",
                color = palette.muted,
                fontSize = 11.5.sp
            )

            OutlinedTextField(
                value = newPresetName,
                onValueChange = { newPresetName = it },
                label = { Text("New Preset / Rig Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorActionButton("Save Clip as Look", modifier = Modifier.weight(1f)) { onSaveStylePreset(newPresetName) }
                InspectorActionButton("Save Clip as Rig", modifier = Modifier.weight(1f)) { onSaveEquipmentRig(newPresetName) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorActionButton("＋ Hot-Load Expansion Pack", isPrimary = true, modifier = Modifier.weight(1f), onClick = onLoadExpansionPack)
                InspectorActionButton("Export JSON Bundle", modifier = Modifier.weight(1f), onClick = onExportBundleClick)
            }

            OutlinedTextField(
                value = pasteJson,
                onValueChange = { pasteJson = it },
                label = { Text("Paste Preset/Equipment Pack JSON to hot-import") },
                modifier = Modifier.fillMaxWidth()
            )
            if (pasteJson.isNotBlank()) {
                InspectorActionButton("Hot-Import Pasted JSON Now", isPrimary = true) {
                    onHotImportJson(pasteJson)
                    pasteJson = ""
                }
            }

            PresetCatalog.motionCollections.forEach { (groupName, motions) ->
                SectionHeader("$groupName (${motions.size})")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    motions.forEach { m ->
                        InspectorChip(m, selected = false) { onApplyMotion(m) }
                    }
                }
            }

            SectionHeader("CAMERA & LENS RIGS (${state.allEquipmentRigs.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.allEquipmentRigs.keys.forEach { rName ->
                    InspectorChip(rName, selected = false) { onApplyEquipmentRig(rName) }
                }
            }

            SectionHeader("STUDIO AUDIO MASTERING GEAR (${state.allAudioGear.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.allAudioGear.keys.forEach { aName ->
                    InspectorChip(aName, selected = false) { onApplyAudioGear(aName) }
                }
            }

            InspectorActionButton("Done", isPrimary = true, onClick = onDismiss)
        }
    }
}

@Composable
fun AlertSheetDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(14.dp))
                .padding(18.dp)
        ) {
            Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = palette.muted, fontSize = 12.5.sp)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("OK", isPrimary = true, modifier = Modifier.width(90.dp), onClick = onDismiss)
            }
        }
    }
}

/**
 * Two-button confirmation used before an action that would discard the current
 * timeline (New / Open / Load demo).
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(14.dp))
                .padding(18.dp)
        ) {
            Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = palette.muted, fontSize = 12.5.sp)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Cancel", modifier = Modifier.width(96.dp), onClick = onDismiss)
                Spacer(modifier = Modifier.width(8.dp))
                InspectorActionButton(confirmLabel, isPrimary = true, modifier = Modifier.width(140.dp), onClick = onConfirm)
            }
        }
    }
}

/**
 * Lets the user choose which actions appear in the clip context menu, and in what order.
 * Persisted by the view model, so the menu is never a hardcoded wall of options.
 */
@Composable
fun ClipMenuSettingsDialog(
    state: EditorUiState,
    onToggle: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Clip menu", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose which actions appear when you right-click a clip, and in what order.",
                color = palette.muted,
                fontSize = 11.5.sp
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionHeader("IN THE MENU")
                state.clipMenuOrder.forEachIndexed { index, id ->
                    val action = ClipMenuAction.fromId(id) ?: return@forEachIndexed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.controlBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = action.label,
                            color = palette.text,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (index > 0) MenuMiniButton("↑") { onMove(id, -1) }
                        if (index < state.clipMenuOrder.lastIndex) MenuMiniButton("↓") { onMove(id, 1) }
                        MenuMiniButton("✕") { onToggle(id) }
                    }
                }
                if (state.clipMenuOrder.isEmpty()) {
                    Text("Nothing enabled - the menu will be empty.", color = palette.muted, fontSize = 11.5.sp)
                }

                SectionHeader("AVAILABLE")
                ClipMenuAction.entries.filter { it.id !in state.clipMenuOrder }.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = action.label,
                            color = palette.muted,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        MenuMiniButton("＋") { onToggle(action.id) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Reset to defaults", modifier = Modifier.weight(1f), onClick = onReset)
                InspectorActionButton("Done", isPrimary = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun MenuMiniButton(label: String, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.controlBg)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Text(label, color = palette.text, fontSize = 12.sp)
    }
}

/**
 * Agent access (MCP): shows the local endpoint and pairing token an AI agent needs, and lets the
 * user start or stop the server. Nothing listens until Start is pressed.
 */
@Composable
fun AgentAccessDialog(
    state: EditorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    val clipboard = LocalClipboardManager.current
    val endpoint = "http://127.0.0.1:${state.mcpPort}/mcp"

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Agent access (MCP)", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "A local MCP server so an AI agent can read and edit this project. It stays on in " +
                    "the background until you turn it off. Editing only - it cannot export or clear " +
                    "the timeline.",
                color = palette.muted,
                fontSize = 11.5.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (state.mcpRunning) palette.green else palette.muted)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (state.mcpRunning) "Running" else "Stopped",
                    color = if (state.mcpRunning) palette.green else palette.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            SectionHeader("ENDPOINT")
            Text(endpoint, color = palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

            SectionHeader("PAIRING TOKEN")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.mcpToken.ifBlank { "-" },
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                InspectorActionButton("Copy", modifier = Modifier.width(84.dp)) {
                    clipboard.setText(AnnotatedString(state.mcpToken))
                }
            }
            Text(
                "Send it as  Authorization: Bearer <token>  or  ?token=<token>",
                color = palette.muted,
                fontSize = 10.5.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.mcpRunning) {
                    InspectorActionButton("Stop", modifier = Modifier.weight(1f), onClick = onStop)
                } else {
                    InspectorActionButton("Start", isPrimary = true, modifier = Modifier.weight(1f), onClick = onStart)
                }
                InspectorActionButton("New token", modifier = Modifier.weight(1f), onClick = onRegenerate)
            }

            if (state.mcpLog.isNotEmpty()) {
                SectionHeader("RECENT ACTIVITY")
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.mcpLog.forEach { line ->
                        Text(
                            text = line,
                            color = palette.muted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }
            }

            InspectorActionButton("Done", onClick = onDismiss)
        }
    }
}

/**
 * The key map, and the place to change it. Every keyboard action is listed with the keys bound to
 * it; Change waits for the next key press and binds it. A key already used elsewhere is only
 * taken over after a confirmation, so nothing is rebound by accident.
 */
@Composable
fun ShortcutsDialog(
    bindings: ShortcutBindings,
    onRebind: (EditorShortcut, String) -> Unit,
    onResetOne: (EditorShortcut) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var capturing by remember { mutableStateOf<EditorShortcut?>(null) }
    var conflict by remember { mutableStateOf<Triple<EditorShortcut, String, EditorShortcut>?>(null) }
    var query by remember { mutableStateOf("") }
    val captureFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
                .focusRequester(captureFocus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    val target = capturing ?: return@onPreviewKeyEvent false
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    // Ctrl, Shift and Alt on their own are not a shortcut yet: keep waiting.
                    val name = chordKeyName(ev.key.nativeKeyCode) ?: return@onPreviewKeyEvent true
                    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
                    if (name == "Escape" && !ctrl && !ev.isAltPressed && !ev.isShiftPressed) {
                        capturing = null
                        return@onPreviewKeyEvent true
                    }
                    val chord = ShortcutChords.of(name, ctrl = ctrl, alt = ev.isAltPressed, shift = ev.isShiftPressed)
                    val holder = bindings.resolve(chord)
                    if (holder != null && holder != target) conflict = Triple(target, chord, holder)
                    else onRebind(target, chord)
                    capturing = null
                    true
                }
        ) {
            Text("Keyboard shortcuts", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Press Change, then the keys you want. Ctrl also answers to the Meta (Cmd) key, and " +
                    "shortcuts stand aside while you type in a text field. Right-click or long-press a clip, " +
                    "a track name, a marker or empty timeline space for its menu.",
                color = palette.muted,
                fontSize = 11.5.sp
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 12.5.sp),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.controlBg)
                            .padding(horizontal = 10.dp)
                    ) {
                        if (query.isEmpty()) Text("Search actions or keys…", color = palette.muted, fontSize = 12.5.sp)
                        inner()
                    }
                }
            )

            conflict?.let { (action, chord, holder) ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.orange.copy(alpha = 0.16f))
                        .border(0.5.dp, palette.orange.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "${ShortcutChords.pretty(chord)} already runs \"${holder.label}\". " +
                            "Use it for \"${action.label}\" instead?",
                        color = palette.text,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InspectorActionButton("Keep as it is", modifier = Modifier.weight(1f)) { conflict = null }
                        InspectorActionButton("Reassign", isPrimary = true, modifier = Modifier.weight(1f)) {
                            onRebind(action, chord)
                            conflict = null
                        }
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = minOf(420.dp, rememberDialogMaxHeight() - 200.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                val needle = query.trim()
                EditorShortcut.groups.forEach { group ->
                    val rows = EditorShortcut.entries.filter { action ->
                        action.group == group && (
                            needle.isEmpty() ||
                                action.label.contains(needle, ignoreCase = true) ||
                                bindings.display(action).contains(needle, ignoreCase = true)
                            )
                    }
                    if (rows.isNotEmpty()) SectionHeader(group.uppercase())
                    rows.forEach { action ->
                        val waiting = capturing == action
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (waiting) palette.accent.copy(alpha = 0.22f) else palette.controlBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                action.label,
                                color = palette.text,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (waiting) "Press keys… (Esc cancels)" else bindings.display(action),
                                color = if (bindings.isCustom(action) && !waiting) palette.orange else palette.accent,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            ShortcutRowButton(if (waiting) "Cancel" else "Change") {
                                if (waiting) {
                                    capturing = null
                                } else {
                                    conflict = null
                                    capturing = action
                                    runCatching { captureFocus.requestFocus() }
                                }
                            }
                            if (bindings.isCustom(action)) {
                                ShortcutRowButton("Reset") { onResetOne(action) }
                            } else if (bindings.chords(action).isNotEmpty()) {
                                ShortcutRowButton("Clear") { onRebind(action, "") }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Reset all to defaults", modifier = Modifier.weight(1f), onClick = onResetAll)
                InspectorActionButton("Done", isPrimary = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ShortcutRowButton(label: String, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Text(
        text = label,
        color = palette.text,
        fontSize = 10.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.panel2)
            .border(0.5.dp, palette.line, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/**
 * The motion-graphics picker. Each entry shows a frame of the real graphic, drawn by the same
 * renderer the preview and the export use. Picking one adds it at the playhead as an ordinary
 * clip, ready to be retyped, recoloured, trimmed and keyframed.
 */
@Composable
fun MotionGraphicsDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = minOf(620.dp, rememberDialogMaxHeight()))
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Motion graphics", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("✕", color = palette.muted, modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp))
            }
            Text(
                "Pick one to add it at the playhead. It becomes a normal clip: change its text and colours in " +
                    "the inspector, drag its edges to retime it, and keyframe it like anything else.",
                color = palette.muted,
                fontSize = 12.sp
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            ) {
                MotionGraphics.categories.forEach { category ->
                    SectionHeader(category.uppercase())
                    MotionGraphics.templates.filter { it.category == category }.forEach { template ->
                        val preview = remember(template.id) { renderGraphicPreview(template) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(palette.controlBg)
                                .clickable(role = Role.Button) { onPick(template.id) }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(63.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10151F))
                            ) {
                                if (preview != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = preview,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(template.title, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(template.description, color = palette.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One representative frame of a template, a little past the middle of its run. */
private fun renderGraphicPreview(template: MgTemplate): androidx.compose.ui.graphics.ImageBitmap? = try {
    val width = 320
    val height = 180
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val backdrop = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            android.graphics.Color.parseColor("#2a3f66"), android.graphics.Color.parseColor("#141b2b"),
            android.graphics.Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdrop)
    val clip = MotionGraphics.createClip(template, 0.0, 10.0)
    val duration = TimelineEngine.clipDuration(clip, emptyMap())
    TitleRenderer.drawClip(canvas, width, height, clip, duration * 0.6, duration)
    bitmap.asImageBitmap()
} catch (_: Exception) {
    null
}
