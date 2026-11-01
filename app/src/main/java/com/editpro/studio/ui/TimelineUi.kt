package com.editpro.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineMarker
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.WorkspaceMode
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.viewmodel.EditorUiState
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

fun niceTimelineStep(raw: Double): Double {
    val v = max(0.05, raw)
    val p = 10.0.pow(floor(log10(v)))
    val n = v / p
    val stepFactor = when {
        n <= 1.0 -> 1.0
        n <= 2.0 -> 2.0
        n <= 5.0 -> 5.0
        else -> 10.0
    }
    return stepFactor * p
}

/**
 * Where the timeline content sits on screen, in root coordinates, so a drag coming from the media
 * library can be turned into a track and a time. Reported by [TimelinePanel] as it lays out.
 */
data class TimelineDropGeometry(
    val contentStartX: Float,
    val tracksTopY: Float,
    val rowHeightPx: Float,
    val pxPerSecond: Float
)

@Composable
fun TimelinePanel(
    state: EditorUiState,
    onAddText: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onRippleDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFollow: () -> Unit,
    onZoomChange: (Double) -> Unit,
    onSeek: (Double) -> Unit,
    onSelectClip: (String?, Boolean) -> Unit,
    onMoveOrTrimClip: (String, TimelineTrack?, Double?, Double?, Double?, Double) -> Unit,
    onSnapHistory: () -> Unit,
    onUpdateSelectedClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onToggleTrackLock: (TimelineTrack) -> Unit,
    onToggleTrackHidden: (TimelineTrack) -> Unit,
    onToggleTrackMute: (TimelineTrack) -> Unit,
    onToggleTrackSolo: (TimelineTrack) -> Unit,
    onAddKeyframeAtPlayhead: () -> Unit,
    onCopyClipProps: () -> Unit,
    onPasteClipProps: () -> Unit,
    onResetClipProps: () -> Unit,
    onSetWorkspaceMode: (WorkspaceMode) -> Unit,
    onApplyEquipmentRig: (String) -> Unit,
    onApplyAudioGear: (String) -> Unit,
    onDetectBeats: () -> Unit,
    onOpenPresetHub: () -> Unit,
    onToggleGradeSplit: () -> Unit,
    onGeometry: (TimelineDropGeometry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    // On a phone the toolbar has more controls than fit, so it scrolls instead of clipping.
    val compact = LocalConfiguration.current.screenWidthDp < 600

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.timelineBg)
    ) {
        // Focused Timeline Toolbar matching `#edit-pro-clean-workspace`
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(palette.panel.copy(alpha = 0.9f))
                .border(0.5.dp, palette.line)
                .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 10.dp)
        ) {
            SmileIconButton(icon = SmileIconType.ADD_TEXT, accessibleLabel = "＋ Text", shortcutBadge = "T", onClick = onAddText)
            SmileIconButton(icon = SmileIconType.SPLIT, accessibleLabel = "Split (S)", shortcutBadge = "S", onClick = onSplit)
            SmileIconButton(icon = SmileIconType.TRASH, accessibleLabel = "Delete", isDanger = true, onClick = onDelete)
            SmileIconButton(icon = SmileIconType.UNDO, accessibleLabel = "Undo (Ctrl+Z)", enabled = state.canUndo, onClick = onUndo)
            SmileIconButton(icon = SmileIconType.REDO, accessibleLabel = "Redo (Ctrl+Shift+Z)", enabled = state.canRedo, onClick = onRedo)

            // The playhead readout doubles as a scrub control - drag it sideways to seek.
            DraggableTimecode(
                seconds = state.playheadSec,
                onDelta = { delta -> onSeek((state.playheadSec + delta).coerceAtLeast(0.0)) },
                secondsPerDp = 0.05,
                color = palette.text,
                fontSize = 12.sp,
                background = palette.controlBg
            )
            Text(
                text = "/ ${formatTimecode(state.durationSec)}",
                color = palette.muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (!compact) Spacer(modifier = Modifier.weight(1f))

            Slider(
                value = state.timelineZoomPxPerSec.toFloat().coerceIn(5f, 180f),
                onValueChange = { onZoomChange(it.toDouble()) },
                valueRange = 5f..180f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = palette.accent,
                    inactiveTrackColor = palette.line
                ),
                modifier = Modifier.width(96.dp)
            )

            SmileIconButton(
                icon = SmileIconType.FOLLOW,
                accessibleLabel = "Follow Playhead",
                shortcutBadge = "F",
                isActive = state.timelineFollowPlayhead,
                onClick = onToggleFollow
            )
        }

        // Scrollable Multi-Track Area
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val viewportWidthDp = maxWidth
            val trackHeaderWidthDp = 78.dp
            val totalDur = max(10.0, state.durationSec + 5.0)
            val effectiveScaleDpPerSec = if (state.timelineAutoFit) {
                val availDp = max(180f, (viewportWidthDp - trackHeaderWidthDp).value)
                (availDp / totalDur).coerceIn(4.0, 180.0)
            } else {
                state.timelineZoomPxPerSec.coerceIn(4.0, 180.0)
            }

            val contentWidthDp = maxOf(viewportWidthDp - trackHeaderWidthDp, (totalDur * effectiveScaleDpPerSec).dp)
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
            val rowHeightDp = if (state.timelineCompact) 42.dp else 54.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Sticky Left Column: Header + Track Labels with L / H / M / S controls
                Column(
                    modifier = Modifier
                        .width(trackHeaderWidthDp)
                        .fillMaxHeight()
                        .background(palette.panel)
                        .border(0.5.dp, palette.line)
                ) {
                    // Top-left corner matching Ruler height
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(palette.rulerBg)
                            .border(0.5.dp, palette.line)
                    ) {
                        Text("TRACKS", color = palette.muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Column(modifier = Modifier.verticalScroll(vScroll)) {
                        TimelineTrack.entries.forEach { track ->
                            val ts = state.trackStates[track] ?: TrackState()
                            val trackHeight = if (state.workspaceMode == WorkspaceMode.MUSIC && track.isAudio && !state.timelineCompact) {
                                66.dp
                            } else rowHeightDp

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(trackHeight)
                                    .border(0.5.dp, palette.line)
                                    .padding(horizontal = 3.dp)
                            ) {
                                Text(
                                    text = track.label,
                                    color = palette.muted,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TrackMiniToggle("L", ts.locked) { onToggleTrackLock(track) }
                                    TrackMiniToggle("H", ts.hidden) { onToggleTrackHidden(track) }
                                    if (track.isAudio) {
                                        TrackMiniToggle("M", ts.muted) { onToggleTrackMute(track) }
                                        TrackMiniToggle("S", ts.solo) { onToggleTrackSolo(track) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Horizontally Scrollable Ruler + Track Lanes + Playhead
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(hScroll)
                        .then(
                            // Optional scrub mode: with a mouse, just moving over the timeline
                            // moves the playhead - no click needed.
                            if (state.scrubFollow) {
                                Modifier.pointerInput(state.scrubFollow, effectiveScaleDpPerSec) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type == PointerEventType.Move &&
                                                !event.buttons.isPrimaryPressed
                                            ) {
                                                val x = event.changes.firstOrNull()?.position?.x
                                                if (x != null) {
                                                    val sec = ((x + hScroll.value) / density.density) /
                                                        effectiveScaleDpPerSec
                                                    onSeek(max(0.0, sec))
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    val localDensity = LocalDensity.current
                    Column(
                        modifier = Modifier
                            .width(contentWidthDp)
                            .onGloballyPositioned { coords ->
                                val origin = coords.positionInRoot()
                                onGeometry(
                                    TimelineDropGeometry(
                                        contentStartX = origin.x,
                                        tracksTopY = origin.y + with(localDensity) { 26.dp.toPx() },
                                        rowHeightPx = with(localDensity) { rowHeightDp.toPx() },
                                        pxPerSecond = (effectiveScaleDpPerSec * localDensity.density).toFloat()
                                    )
                                )
                            }
                    ) {
                        // Sticky Time Ruler + Markers
                        TimelineRuler(
                            totalDurationSec = totalDur,
                            scaleDpPerSec = effectiveScaleDpPerSec,
                            markers = state.markers,
                            onSeek = onSeek,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                        )

                        // Track Content Rows
                        Column(modifier = Modifier.verticalScroll(vScroll)) {
                            TimelineTrack.entries.forEach { track ->
                                val ts = state.trackStates[track] ?: TrackState()
                                val trackHeight = if (state.workspaceMode == WorkspaceMode.MUSIC && track.isAudio && !state.timelineCompact) {
                                    66.dp
                                } else rowHeightDp
                                val trackClips = state.resolvedClips.filter { it.clip.track == track }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(trackHeight)
                                        .background(
                                            if (ts.locked) palette.panel.copy(alpha = 0.35f)
                                            else Color.Transparent
                                        )
                                        .border(0.5.dp, palette.line)
                                        .pointerInput(effectiveScaleDpPerSec) {
                                            detectTapGestures { offset ->
                                                val sec = (offset.x / density.density) / effectiveScaleDpPerSec
                                                onSeek(max(0.0, sec))
                                            }
                                        }
                                ) {
                                    for (rc in trackClips) {
                                        TimelineClipBlock(
                                            resolved = rc,
                                            state = state,
                                            scaleDpPerSec = effectiveScaleDpPerSec,
                                            trackHeightDp = trackHeight.value,
                                            onSelect = { additive -> onSelectClip(rc.clip.id, additive) },
                                            onSnapHistory = onSnapHistory,
                                            onMoveOrTrim = { newStart, newIn, newOut ->
                                                onMoveOrTrimClip(
                                                    rc.clip.id,
                                                    rc.clip.track,
                                                    newStart,
                                                    newIn,
                                                    newOut,
                                                    effectiveScaleDpPerSec
                                                )
                                            },
                                            onUpdateSelectedClip = onUpdateSelectedClip,
                                            onSplit = onSplit,
                                            onAddKeyframe = onAddKeyframeAtPlayhead,
                                            onDuplicate = onDuplicate,
                                            onRippleDelete = onRippleDelete,
                                            onDelete = onDelete,
                                            onCopyProps = onCopyClipProps,
                                            onPasteProps = onPasteClipProps,
                                            onResetProps = onResetClipProps,
                                            onSetWorkspaceMode = onSetWorkspaceMode,
                                            onApplyEquipmentRig = onApplyEquipmentRig,
                                            onApplyAudioGear = onApplyAudioGear,
                                            onDetectBeats = onDetectBeats,
                                            onOpenPresetHub = onOpenPresetHub,
                                            onToggleGradeSplit = onToggleGradeSplit
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Red Playhead Line + Top Triangle Cap
                    val playheadOffsetDp = (state.playheadSec * effectiveScaleDpPerSec).dp
                    Canvas(
                        modifier = Modifier
                            .offset(x = playheadOffsetDp)
                            .width(2.dp)
                            .fillMaxHeight()
                    ) {
                        val red = Color(0xFFFF453A)
                        drawLine(red, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx())
                        val cap = Path().apply {
                            moveTo(-5.dp.toPx(), 0f)
                            lineTo(5.dp.toPx(), 0f)
                            lineTo(0f, 7.dp.toPx())
                            close()
                        }
                        drawPath(cap, red)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMiniToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 16.dp, height = 15.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) palette.accent else palette.controlBg)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (active) Color.White else palette.muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TimelineRuler(
    totalDurationSec: Double,
    scaleDpPerSec: Double,
    markers: List<TimelineMarker>,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    val tickStep = niceTimelineStep(82.0 / max(0.1, scaleDpPerSec))

    Box(
        modifier = modifier
            .background(palette.rulerBg)
            .border(0.5.dp, palette.line)
            .pointerInput(scaleDpPerSec) {
                detectTapGestures { offset ->
                    val sec = (offset.x / density.density) / scaleDpPerSec
                    onSeek(max(0.0, sec))
                }
            }
            .pointerInput(scaleDpPerSec) {
                detectDragGestures(
                    // Snap to the press point straight away, then follow the pointer, so a scrub
                    // responds on the first movement instead of lagging behind it.
                    onDragStart = { offset ->
                        val sec = (offset.x / density.density) / scaleDpPerSec
                        onSeek(max(0.0, sec))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val sec = (change.position.x / density.density) / scaleDpPerSec
                        onSeek(max(0.0, sec))
                    }
                )
            }
    ) {
        var t = 0.0
        while (t <= totalDurationSec + tickStep * 0.01) {
            val leftDp = (t * scaleDpPerSec).dp
            Text(
                text = formatTimecode(t).take(5),
                color = palette.muted,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .offset(x = leftDp, y = 5.dp)
            )
            t += tickStep
        }

        // Orange Marker Pins
        for (m in markers) {
            val leftDp = (m.time * scaleDpPerSec).dp
            Text(
                text = "▼",
                color = Color(0xFFFF9F0A),
                fontSize = 9.sp,
                modifier = Modifier
                    .offset(x = leftDp, y = 0.dp)
                    .clickable { onSeek(m.time) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineClipBlock(
    resolved: ResolvedClip,
    state: EditorUiState,
    scaleDpPerSec: Double,
    trackHeightDp: Float,
    onSelect: (Boolean) -> Unit,
    onSnapHistory: () -> Unit,
    onMoveOrTrim: (Double?, Double?, Double?) -> Unit,
    onUpdateSelectedClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onSplit: () -> Unit,
    onAddKeyframe: () -> Unit,
    onDuplicate: () -> Unit,
    onRippleDelete: () -> Unit,
    onDelete: () -> Unit,
    onCopyProps: () -> Unit,
    onPasteProps: () -> Unit,
    onResetProps: () -> Unit,
    onSetWorkspaceMode: (WorkspaceMode) -> Unit,
    onApplyEquipmentRig: (String) -> Unit,
    onApplyAudioGear: (String) -> Unit,
    onDetectBeats: () -> Unit,
    onOpenPresetHub: () -> Unit,
    onToggleGradeSplit: () -> Unit
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    val c = resolved.clip
    val asset = c.assetId?.let { state.assetsById[it] }
    val isPrimarySelected = c.id == state.selectedClipId
    val isMultiSelected = c.id in state.selectedClipIds && !isPrimarySelected
    var showContextMenu by remember { mutableStateOf(false) }

    fun runClipMenuAction(action: ClipMenuAction) {
        when (action) {
            ClipMenuAction.SPLIT -> onSplit()
            ClipMenuAction.KEYFRAME -> onAddKeyframe()
            ClipMenuAction.DUPLICATE -> onDuplicate()
            ClipMenuAction.COPY_PROPS -> onCopyProps()
            ClipMenuAction.PASTE_PROPS -> onPasteProps()
            ClipMenuAction.RESET_PROPS -> onResetProps()
            ClipMenuAction.RIPPLE_DELETE -> onRippleDelete()
            ClipMenuAction.DELETE -> onDelete()
            ClipMenuAction.DETECT_BEATS -> onDetectBeats()
            ClipMenuAction.PRESET_HUB -> onOpenPresetHub()
            ClipMenuAction.GRADE_SPLIT -> onToggleGradeSplit()
            ClipMenuAction.MODE_EDIT -> onSetWorkspaceMode(WorkspaceMode.EDIT)
            ClipMenuAction.MODE_COLOR -> onSetWorkspaceMode(WorkspaceMode.COLOR)
            ClipMenuAction.MODE_MOTION -> onSetWorkspaceMode(WorkspaceMode.MOTION)
            ClipMenuAction.MODE_MUSIC -> onSetWorkspaceMode(WorkspaceMode.MUSIC)
            ClipMenuAction.RIG_ARRI -> onApplyEquipmentRig("ARRI Alexa Mini LF · Cooke S4 35mm")
            ClipMenuAction.RIG_RED -> onApplyEquipmentRig("RED V-Raptor 8K · Atlas Orion 2x Anamorphic")
            ClipMenuAction.AUDIO_SSL -> onApplyAudioGear("SSL 4000G Master Bus Glue")
        }
    }

    val bgColor = when {
        asset?.kind == AssetKind.COLOR -> try {
            Color(android.graphics.Color.parseColor(asset.colorHex ?: "#2678ff"))
        } catch (_: Exception) {
            palette.clipVideo
        }
        asset?.kind == AssetKind.AUDIO || c.track.isAudio -> palette.clipAudio
        asset?.kind == AssetKind.IMAGE -> palette.clipImage
        c.track == TimelineTrack.TEXT -> palette.clipText
        else -> palette.clipVideo
    }

    val leftDp = (resolved.start * scaleDpPerSec).dp
    val widthDp = max(42.0, resolved.duration * scaleDpPerSec - 3.0).dp
    val blockHeightDp = (trackHeightDp - 10f).dp

    Box(
        modifier = Modifier
            .offset(x = leftDp, y = 5.dp)
            .width(widthDp)
            .height(blockHeightDp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = if (isPrimarySelected || isMultiSelected) 2.dp else 0.5.dp,
                color = when {
                    isPrimarySelected -> Color.White
                    isMultiSelected -> Color(0xFFFFD60A)
                    else -> Color.White.copy(alpha = 0.28f)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .combinedClickable(
                onClick = { onSelect(false) },
                onLongClick = {
                    onSelect(false)
                    showContextMenu = true
                }
            )
            // Right-click (mouse / trackpad secondary button) opens the same context menu as
            // long-press. Long-press alone is useless with a mouse, and the menu was previously
            // unreachable that way.
            .pointerInput(c.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            onSelect(false)
                            showContextMenu = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(c.id, scaleDpPerSec) {
                var accumDxPx = 0f
                detectDragGestures(
                    onDragStart = {
                        onSelect(false)
                        onSnapHistory()
                        accumDxPx = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumDxPx += dragAmount.x
                        val deltaSec = (accumDxPx / density.density) / scaleDpPerSec
                        onMoveOrTrim(max(0.0, resolved.start + deltaSec), null, null)
                    }
                )
            }
    ) {
        // Audio Waveform Canvas & Fade Shading
        if (asset?.kind == AssetKind.AUDIO || c.track.isAudio) {
            val peaks = asset?.waveformPeaks ?: emptyList()
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                val mid = size.height / 2f
                val count = max(1, peaks.size)
                val barW = max(1f, (size.width / count) * 0.6f)
                peaks.forEachIndexed { idx, peak ->
                    val amp = max(2f, peak * mid * 0.9f)
                    val x = idx * (size.width / count)
                    drawRect(
                        color = Color(0xAA9DF5DC),
                        topLeft = Offset(x, mid - amp),
                        size = Size(barW, amp * 2f)
                    )
                }
            }
        }

        // Entrance / Exit Transition Cues
        if (c.transition != "none") {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFF7D774), Color(0xFFFF9F0A))))
            )
        }
        if (c.transitionOut != "none") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF9F0A), Color(0xFFF7D774))))
            )
        }

        // Clip Title & Duration Label
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 3.dp)
        ) {
            Text(
                text = c.text.takeIf { it.isNotBlank() } ?: asset?.name ?: c.label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatTimecode(resolved.duration)}${if (c.speed != 1.0) " · ${c.speed}×" else ""}",
                color = Color(0xFFD2D9E7),
                fontSize = 8.5.sp,
                maxLines = 1
            )
        }

        // Keyframe Diamonds & Volume Automation Dots along bottom edge
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dur = max(0.1, resolved.duration)
            c.keyframes.forEach { kf ->
                val x = ((kf.time / dur).coerceIn(0.02, 0.98) * size.width).toFloat()
                drawCircle(Color(0xFFFFD60A), radius = 3.5.dp.toPx(), center = Offset(x, size.height - 5.dp.toPx()))
            }
            c.volumeKeyframes.forEach { vp ->
                val x = ((vp.time / dur).coerceIn(0.02, 0.98) * size.width).toFloat()
                drawCircle(Color(0xFF64D2FF), radius = 3.5.dp.toPx(), center = Offset(x, size.height - 5.dp.toPx()))
            }
        }

        // Left Trim Handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(12.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.22f))
                .pointerInput(c.id, scaleDpPerSec) {
                    var accumPx = 0f
                    detectDragGestures(
                        onDragStart = { onSnapHistory(); accumPx = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumPx += dragAmount.x
                            val deltaSec = (accumPx / density.density) / scaleDpPerSec
                            val nextIn = (c.trimIn + deltaSec).coerceIn(0.0, max(0.0, c.trimOut - 0.1))
                            val nextStart = max(0.0, resolved.start + (nextIn - c.trimIn))
                            onMoveOrTrim(nextStart, nextIn, c.trimOut)
                        }
                    )
                }
        )

        // Right Trim Handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(12.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.22f))
                .pointerInput(c.id, scaleDpPerSec) {
                    var accumPx = 0f
                    detectDragGestures(
                        onDragStart = { onSnapHistory(); accumPx = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumPx += dragAmount.x
                            val deltaSec = (accumPx / density.density) / scaleDpPerSec
                            val nextOut = max(c.trimIn + 0.1, c.trimOut + deltaSec)
                            onMoveOrTrim(resolved.start, c.trimIn, nextOut)
                        }
                    )
                }
        )

        // Native Context Menu — built from the user's configured action list, styled compact
        // with the shortcut hint right-aligned and separators between action groups.
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier
                .widthIn(min = 232.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.panel2)
                .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
        ) {
            val actions = state.clipMenuOrder.mapNotNull { ClipMenuAction.fromId(it) }
            var lastGroup: Int? = null
            actions.forEach { action ->
                if (lastGroup != null && action.group != lastGroup) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(1.dp)
                            .background(palette.line)
                    )
                }
                lastGroup = action.group
                ClipMenuItemRow(
                    label = action.label,
                    shortcut = action.shortcut,
                    destructive = action.destructive
                ) {
                    showContextMenu = false
                    runClipMenuAction(action)
                }
            }
            if (actions.isEmpty()) {
                ClipMenuItemRow("No actions enabled", null, false) { showContextMenu = false }
            }
        }
    }
}

/**
 * One row of the clip context menu: label on the left, shortcut hint right-aligned, destructive
 * actions tinted. Kept deliberately compact so the menu reads like a desktop menu rather than a
 * list of full-width buttons.
 */
@Composable
private fun ClipMenuItemRow(
    label: String,
    shortcut: String?,
    destructive: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = if (destructive) palette.red else palette.text,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!shortcut.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(20.dp))
            Text(
                text = shortcut,
                color = palette.muted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
