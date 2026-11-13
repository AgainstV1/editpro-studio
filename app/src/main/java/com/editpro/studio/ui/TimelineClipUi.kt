package com.editpro.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.MotionGraphics
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.viewmodel.EditProViewModel
import com.editpro.studio.viewmodel.EditorUiState
import kotlin.math.max
import kotlin.math.min

/**
 * One clip on the timeline.
 *
 * The block is positioned absolutely inside the track area and keyed by clip id by its caller, so
 * it — and any gesture running on it — survives the clip moving to another track. Every gesture
 * block reads the clip, the zoom and the row layout through `rememberUpdatedState`: the blocks
 * outlive recompositions, and a captured copy would send the second drag of a clip back to where
 * the first one started.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineClipBlock(
    resolved: ResolvedClip,
    asset: MediaAsset?,
    state: EditorUiState,
    row: TrackRow,
    rows: List<TrackRow>,
    scaleDpPerSec: Double,
    viewModel: EditProViewModel,
    pointerModifiers: PointerModifierState
) {
    val palette = LocalEditProPalette.current
    val screen = LocalDensity.current
    val c = resolved.clip
    val clipId = c.id
    val isPrimary = clipId == state.selectedClipId
    val isSelected = clipId in state.selectionIds
    val collapsed = row.state.collapsed
    var menuAt by remember { mutableStateOf<Offset?>(null) }

    val currentResolved by rememberUpdatedState(resolved)
    val currentScale by rememberUpdatedState(scaleDpPerSec)
    val currentRows by rememberUpdatedState(rows)
    val currentRow by rememberUpdatedState(row)

    val inset = if (collapsed) 3f else 5f
    val leftDp = (resolved.start * scaleDpPerSec).toFloat()
    val widthDp = max(4.0, resolved.duration * scaleDpPerSec - 1.0).toFloat()
    val heightDp = max(8f, row.heightDp - inset * 2f)
    val handleDp = min(12f, widthDp / 4f)
    val roomy = !collapsed && widthDp >= 34f

    // Makes sure the clip a gesture starts on is part of the selection, without throwing away a
    // multi-selection it already belongs to.
    fun ensureSelected() {
        val s = viewModel.uiState.value
        if (clipId !in s.selectionIds) {
            viewModel.selectClipsByIds(setOf(clipId), additive = s.selectionMode || pointerModifiers.ctrl)
        }
    }

    val bgColor = when {
        asset?.kind == AssetKind.COLOR -> parseHexColor(asset.colorHex, palette.clipVideo)
        asset?.kind == AssetKind.AUDIO || c.track.isAudio -> palette.clipAudio
        asset?.kind == AssetKind.IMAGE -> palette.clipImage
        c.isGraphic -> palette.purple
        c.track.isTitle -> palette.clipText
        else -> palette.clipVideo
    }
    val dimmed = row.state.hidden || (row.state.muted && c.track.isAudio)

    Box(
        modifier = Modifier
            .offset(x = leftDp.dp, y = (row.topDp + inset).dp)
            .width(widthDp.dp)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(if (collapsed) 3.dp else 6.dp))
            .background(bgColor.copy(alpha = if (dimmed) 0.45f else 1f))
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = when {
                    isPrimary -> Color.White
                    isSelected -> Color(0xFFFFD60A)
                    else -> Color.White.copy(alpha = 0.28f)
                },
                shape = RoundedCornerShape(if (collapsed) 3.dp else 6.dp)
            )
            .combinedClickable(
                onClick = {
                    viewModel.selectClip(clipId, additive = pointerModifiers.ctrl, range = pointerModifiers.shift)
                },
                onLongClick = {
                    ensureSelected()
                    menuAt = Offset(8f, 8f)
                }
            )
            // Right-click (mouse / trackpad secondary button) opens the same menu as long-press.
            .onSecondaryClick(clipId) { pos ->
                ensureSelected()
                menuAt = pos
            }
            // Drag the body to move the clip, or the whole selection when it is part of one. A
            // single clip also follows the pointer up and down to another track.
            .pointerInput(clipId) {
                var originStart = 0.0
                var group: Set<String> = emptySet()
                var travel = Offset.Zero
                var pointerY = 0f
                var active = false
                detectDragGestures(
                    onDragStart = { offset ->
                        val rc = currentResolved
                        val locked = viewModel.uiState.value.trackStates[rc.clip.track]?.locked == true
                        active = !locked
                        if (locked) {
                            viewModel.showToast("${rc.clip.track.label} is locked. Unlock it to move its clips.")
                        } else {
                            ensureSelected()
                            val selection = viewModel.uiState.value.selectionIds
                            group = if (clipId in selection) selection else setOf(clipId)
                            viewModel.snapHistory()
                            originStart = rc.start
                            travel = Offset.Zero
                            pointerY = (currentRow.topDp + inset) * screen.density + offset.y
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val scale = currentScale
                        if (active && scale > 0.0) {
                            travel += amount
                            val desired = originStart + (travel.x / screen.density) / scale
                            val target = if (group.size == 1) {
                                val yDp = (pointerY + travel.y) / screen.density
                                currentRows.firstOrNull { yDp >= it.topDp && yDp < it.topDp + it.heightDp }?.track
                            } else null
                            viewModel.dragClipsTo(clipId, group, desired, target, scale)
                        }
                    }
                )
            }
            .semantics { contentDescription = "Clip ${c.label} on ${c.track.label}" }
    ) {
        // Audio waveform
        if (!collapsed && (asset?.kind == AssetKind.AUDIO || c.track.isAudio)) {
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

        // Entrance / exit transition cues
        if (!collapsed && c.transition != "none") {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFF7D774), Color(0xFFFF9F0A))))
            )
        }
        if (!collapsed && c.transitionOut != "none") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF9F0A), Color(0xFFF7D774))))
            )
        }

        // Thumbnail, name and length — only where there is room to read them.
        if (roomy) {
            Row(modifier = Modifier.fillMaxSize().padding(start = handleDp.dp, end = handleDp.dp)) {
                val thumbPath = when (asset?.kind) {
                    AssetKind.IMAGE -> asset.localPath
                    AssetKind.VIDEO -> asset.filmstripPaths.firstOrNull()
                    else -> null
                }
                if (thumbPath != null && !c.track.isAudio && widthDp >= 96f) {
                    val thumb = rememberThumbnail(thumbPath, maxEdgePx = 160)
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .fillMaxHeight()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 3.dp)) {
                    Text(
                        text = clipTitle(c, asset),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (heightDp >= 30f) {
                        Text(
                            text = "${formatTimecode(resolved.duration)}${if (c.speed != 1.0) " · ${c.speed}×" else ""}",
                            color = Color(0xFFD2D9E7),
                            fontSize = 8.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Keyframe diamonds and volume automation dots along the bottom edge
        if (c.keyframes.isNotEmpty() || c.volumeKeyframes.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dur = max(0.1, resolved.duration)
                val y = size.height - 5.dp.toPx()
                val r = 3.5.dp.toPx()
                c.keyframes.forEach { kf ->
                    val x = ((kf.time / dur).coerceIn(0.0, 1.0) * size.width).toFloat()
                    val diamond = Path().apply {
                        moveTo(x, y - r)
                        lineTo(x + r, y)
                        lineTo(x, y + r)
                        lineTo(x - r, y)
                        close()
                    }
                    drawPath(diamond, Color(0xFFFFD60A))
                }
                c.volumeKeyframes.forEach { vp ->
                    val x = ((vp.time / dur).coerceIn(0.0, 1.0) * size.width).toFloat()
                    drawCircle(Color(0xFF64D2FF), radius = r, center = Offset(x, y))
                }
            }
        }

        // Trim handles: drag an edge to shorten or lengthen the clip.
        if (!collapsed && handleDp >= 2f) {
            TrimHandle(
                clipId = clipId,
                head = true,
                widthDp = handleDp,
                resolved = { currentResolved },
                scale = { currentScale },
                onBegin = { ensureSelected() },
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            TrimHandle(
                clipId = clipId,
                head = false,
                widthDp = handleDp,
                resolved = { currentResolved },
                scale = { currentScale },
                onBegin = { ensureSelected() },
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        ClipContextMenu(
            position = menuAt,
            clip = c,
            asset = asset,
            state = state,
            viewModel = viewModel,
            onDismiss = { menuAt = null }
        )
    }
}

private fun clipTitle(c: TimelineClip, asset: MediaAsset?): String {
    val firstLine = c.text.lineSequence().firstOrNull { it.isNotBlank() }
    return when {
        c.isGraphic -> {
            val template = MotionGraphics.find(c.graphic)?.title ?: c.label
            if (firstLine != null) "◇ $template · $firstLine" else "◇ $template"
        }
        firstLine != null -> firstLine
        else -> asset?.name ?: c.label
    }
}

@Composable
private fun TrimHandle(
    clipId: String,
    head: Boolean,
    widthDp: Float,
    resolved: () -> ResolvedClip,
    scale: () -> Double,
    onBegin: () -> Unit,
    viewModel: EditProViewModel,
    modifier: Modifier = Modifier
) {
    val screen = LocalDensity.current
    Box(
        modifier = modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.22f))
            .pointerInput(clipId, head) {
                var origin: TimelineClip? = null
                var originStart = 0.0
                var travel = 0f
                detectDragGestures(
                    onDragStart = {
                        // The clip as it is now is the reference for the whole drag, so dragging
                        // back restores exactly what was trimmed away.
                        val rc = resolved()
                        origin = rc.clip
                        originStart = rc.start
                        travel = 0f
                        onBegin()
                        viewModel.snapHistory()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        travel += amount.x
                        val start = origin
                        val s = scale()
                        if (start != null && s > 0.0) {
                            viewModel.trimClipEdge(start, originStart, head, (travel / screen.density) / s, s)
                        }
                    }
                )
            }
            .semantics { contentDescription = if (head) "Trim start" else "Trim end" }
    )
}

/**
 * The clip menu: the user's own list of actions (Tools → Customise clip menu), followed by the
 * tracks the selection can move to. Shortcuts shown are the keys currently bound.
 */
@Composable
private fun ClipContextMenu(
    position: Offset?,
    clip: TimelineClip,
    asset: MediaAsset?,
    state: EditorUiState,
    viewModel: EditProViewModel,
    onDismiss: () -> Unit
) {
    if (position == null) return
    val count = state.selectionIds.size
    EditorContextMenu(position = position, onDismiss = onDismiss) {
        val actions = state.clipMenuOrder.mapNotNull { ClipMenuAction.fromId(it) }
        var lastGroup: Int? = null
        actions.forEach { action ->
            if (lastGroup != null && action.group != lastGroup) EditorMenuDivider()
            lastGroup = action.group
            val enabled = when (action) {
                ClipMenuAction.PASTE -> state.clipClipboard.isNotEmpty()
                ClipMenuAction.PASTE_PROPS -> state.copiedClipTemplate != null
                ClipMenuAction.DETACH_AUDIO -> asset?.kind == AssetKind.VIDEO && clip.track.isVisual
                ClipMenuAction.REPLACE_MEDIA -> state.selectedAssetId != null && clip.assetId != null
                ClipMenuAction.DETECT_BEATS -> asset?.kind == AssetKind.AUDIO
                else -> true
            }
            EditorMenuItem(
                label = if (count > 1 && action in MULTI_CLIP_ACTIONS) "${action.label} ($count clips)" else action.label,
                shortcut = action.shortcutAction?.let { state.bindings.hint(it) },
                destructive = action.destructive,
                enabled = enabled
            ) {
                onDismiss()
                viewModel.performClipMenuAction(action)
            }
        }
        if (actions.isNotEmpty()) EditorMenuDivider()
        TimelineTrack.entries
            .filter { it != clip.track && TimelineOps.canPlace(clip, asset, it) }
            .forEach { track ->
                EditorMenuItem("Move to ${track.label}") {
                    onDismiss()
                    viewModel.moveSelectionToTrack(track)
                }
            }
        EditorMenuItem("Select all on ${clip.track.label}") {
            onDismiss()
            viewModel.selectAllClips(clip.track)
        }
        EditorMenuDivider()
        EditorMenuItem("Customise this menu…") {
            onDismiss()
            viewModel.setShowClipMenuModal(true)
        }
    }
}

/** Menu entries that act on every selected clip, so their label can say how many. */
private val MULTI_CLIP_ACTIONS = setOf(
    ClipMenuAction.CUT, ClipMenuAction.COPY, ClipMenuAction.DUPLICATE, ClipMenuAction.DELETE,
    ClipMenuAction.RIPPLE_DELETE
)
