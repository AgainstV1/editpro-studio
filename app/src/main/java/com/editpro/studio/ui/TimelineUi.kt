package com.editpro.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.KeyframeOps
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineMarker
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.WorkspaceMode
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.viewmodel.EditProViewModel
import com.editpro.studio.viewmodel.EditorUiState
import com.editpro.studio.viewmodel.WorkspacePanel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
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

/** One track row as laid out on screen, in root coordinates. */
data class TimelineRowGeometry(val track: TimelineTrack, val topPx: Float, val heightPx: Float)

/**
 * Where the timeline sits on screen, in root coordinates, so a drag coming from the media library
 * can be turned into a track and a time. Reported by [TimelinePanel] as it lays out. Rows carry
 * their own heights because a collapsed track is much shorter than an open one.
 */
data class TimelineDropGeometry(
    val contentStartX: Float,
    val panelTopY: Float,
    val pxPerSecond: Float,
    val rows: List<TimelineRowGeometry>
) {
    fun trackAt(yRoot: Float): TimelineTrack? =
        rows.firstOrNull { yRoot >= it.topPx && yRoot < it.topPx + it.heightPx }?.track
}

/** A track row inside the timeline, in dp from the top of the track area. */
internal data class TrackRow(val track: TimelineTrack, val state: TrackState, val topDp: Float, val heightDp: Float)

private val RULER_HEIGHT = 26.dp
private val KEYFRAME_LANE_HEIGHT = 30.dp
private const val COLLAPSED_ROW_DP = 22f

/** Widest the timeline content may get; beyond this a layout can no longer be measured. */
private const val MAX_CONTENT_PX = 180_000f
private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 300.0

/**
 * The multi-track timeline: toolbar, ruler, optional keyframe lane and the track rows.
 *
 * Editing gestures: tap a clip to select it (Ctrl adds, Shift selects a range, and the Multi
 * toggle does the same for touch); drag a clip — or a whole selection — to move it, dragging a
 * single clip up or down changes its track; drag an edge to trim. Drag across empty space with a
 * mouse, or long-press and drag with a finger, to select with a marquee. Right-click (or
 * long-press) a clip, a track header, a marker or empty space for its menu. Ctrl + mouse wheel
 * zooms around the pointer and Shift + wheel scrolls sideways.
 */
@Composable
fun TimelinePanel(
    state: EditorUiState,
    viewModel: EditProViewModel,
    pointerModifiers: PointerModifierState,
    onRun: (EditorShortcut) -> Unit,
    onGeometry: (TimelineDropGeometry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    // On a phone the toolbar has more controls than fit, so it scrolls instead of clipping.
    val compact = rememberIsCompact()
    var panelTopY by remember { mutableStateOf(0f) }
    // The zoom actually on screen, which differs from the stored zoom while Fit is on.
    var shownScale by remember { mutableStateOf(state.timelineZoomPxPerSec) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.timelineBg)
            .onGloballyPositioned { panelTopY = it.positionInRoot().y }
    ) {
        TimelineToolbar(
            state = state,
            viewModel = viewModel,
            onRun = onRun,
            shownScale = shownScale,
            compact = compact
        )

        if (state.timelineCollapsed) return@Column

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val headerWidth = if (compact) 70.dp else 96.dp
            val viewportDp = max(120f, (maxWidth - headerWidth).value)
            val viewportPx = viewportDp * density.density
            val totalDur = max(10.0, state.durationSec + 5.0)
            val widthLimit = (MAX_CONTENT_PX / density.density) / totalDur
            val scale = (
                if (state.timelineAutoFit) viewportDp / totalDur
                else state.timelineZoomPxPerSec
                ).coerceIn(0.2, MAX_ZOOM).coerceAtMost(max(0.2, widthLimit))
            val pxPerSec = (scale * density.density).toFloat()
            val contentWidthDp = max(viewportDp.toDouble(), totalDur * scale).dp

            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            val resolved = state.resolvedClips
            val assetsById = state.assetsById

            // Row layout: a collapsed track is a thin strip, audio rows grow in Music mode.
            val baseRow = if (state.timelineCompact) 42f else 54f
            val rows = remember(state.trackStates, state.timelineCompact, state.workspaceMode) {
                var top = 0f
                TimelineTrack.entries.map { track ->
                    val ts = state.trackStates[track] ?: TrackState()
                    val h = when {
                        ts.collapsed -> COLLAPSED_ROW_DP
                        state.workspaceMode == WorkspaceMode.MUSIC && track.isAudio && !state.timelineCompact -> 66f
                        else -> baseRow
                    }
                    TrackRow(track, ts, top, h).also { top += h }
                }
            }
            val rowsHeightDp = rows.sumOf { it.heightDp.toDouble() }.toFloat()

            // ---- Zoom keeps a point still: the pointer for the wheel, otherwise the playhead.
            var zoomAnchor by remember { mutableStateOf<Pair<Double, Float>?>(null) }
            var lastScale by remember { mutableStateOf(scale) }
            LaunchedEffect(scale) {
                shownScale = scale
                viewModel.noteVisibleTimelineZoom(scale)
                val previous = lastScale
                lastScale = scale
                if (previous == scale || state.timelineAutoFit) {
                    zoomAnchor = null
                    return@LaunchedEffect
                }
                val oldPxPerSec = previous * density.density
                val anchor = zoomAnchor ?: run {
                    val playheadX = (state.playheadSec * oldPxPerSec - hScroll.value).toFloat()
                    if (playheadX in 0f..viewportPx) state.playheadSec to playheadX
                    else ((hScroll.value + viewportPx / 2f) / oldPxPerSec) to viewportPx / 2f
                }
                zoomAnchor = null
                // The wider (or narrower) content is laid out on the next frame; scroll after it.
                withFrameNanos { }
                hScroll.scrollTo((anchor.first * pxPerSec - anchor.second).roundToInt().coerceAtLeast(0))
            }

            // ---- Follow the playhead by paging the view when it leaves the visible part.
            LaunchedEffect(state.playheadSec, state.timelineFollowPlayhead, pxPerSec) {
                if (!state.timelineFollowPlayhead || state.timelineAutoFit) return@LaunchedEffect
                val x = (state.playheadSec * pxPerSec).toFloat()
                val left = hScroll.value.toFloat()
                if (x < left || x > left + viewportPx - 24f) {
                    hScroll.scrollTo((x - viewportPx * 0.15f).roundToInt().coerceAtLeast(0))
                }
            }

            fun timeAt(xPx: Float): Double = if (pxPerSec > 0f) max(0.0, (xPx / pxPerSec).toDouble()) else 0.0
            fun rowAt(yPx: Float): TrackRow? {
                val yDp = yPx / density.density
                return rows.firstOrNull { yDp >= it.topDp && yDp < it.topDp + it.heightDp }
            }

            // Values the long-lived gesture blocks need at their current value.
            val currentState by rememberUpdatedState(state)
            val currentResolved by rememberUpdatedState(resolved)
            val currentRows by rememberUpdatedState(rows)
            val currentPxPerSec by rememberUpdatedState(pxPerSec)
            val currentScale by rememberUpdatedState(scale)

            var marquee by remember { mutableStateOf<Rect?>(null) }
            var marqueeBase by remember { mutableStateOf<Set<String>>(emptySet()) }
            var emptyMenu by remember { mutableStateOf<Offset?>(null) }
            var headerMenu by remember { mutableStateOf<Pair<TimelineTrack, Offset>?>(null) }
            var rulerMenu by remember { mutableStateOf<Offset?>(null) }
            var markerMenu by remember { mutableStateOf<Pair<TimelineMarker, Offset>?>(null) }
            var renamingMarker by remember { mutableStateOf<TimelineMarker?>(null) }

            fun applyMarquee(rect: Rect) {
                val pps = currentPxPerSec
                if (pps <= 0f) return
                val topDp = min(rect.top, rect.bottom) / density.density
                val bottomDp = max(rect.top, rect.bottom) / density.density
                val tracks = currentRows.filter { it.topDp < bottomDp && it.topDp + it.heightDp > topDp }
                    .map { it.track }.toSet()
                val from = (min(rect.left, rect.right) / pps).toDouble()
                val to = (max(rect.left, rect.right) / pps).toDouble()
                val hit = TimelineOps.clipsInRange(currentResolved, tracks, from, to)
                viewModel.selectClipsByIds(marqueeBase + hit)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // ------------------------------------------------ sticky track headers
                Column(
                    modifier = Modifier
                        .width(headerWidth)
                        .fillMaxHeight()
                        .background(palette.panel)
                        .border(0.5.dp, palette.line)
                ) {
                    val allCollapsed = rows.all { it.state.collapsed }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RULER_HEIGHT)
                            .background(palette.rulerBg)
                            .border(0.5.dp, palette.line)
                            .clickable(role = Role.Button) { viewModel.toggleAllTracksCollapsed() }
                            .semantics {
                                contentDescription = if (allCollapsed) "Expand all tracks" else "Collapse all tracks"
                            }
                            .padding(horizontal = 6.dp)
                    ) {
                        Text("TRACKS", color = palette.muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        Text(if (allCollapsed) "▸" else "▾", color = palette.muted, fontSize = 10.sp)
                    }
                    if (state.showKeyframeLane) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(KEYFRAME_LANE_HEIGHT)
                                .border(0.5.dp, palette.line)
                                .clickable(role = Role.Button) { viewModel.addOrUpdateKeyframeAtPlayhead() }
                                .semantics { contentDescription = "Add keyframe at playhead" }
                                .padding(horizontal = 6.dp)
                        ) {
                            Text("KEYS", color = Color(0xFFFFD60A), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            Text("◆＋", color = Color(0xFFFFD60A), fontSize = 9.sp)
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(modifier = Modifier.verticalScroll(vScroll)) {
                            rows.forEach { row ->
                                TrackHeader(
                                    row = row,
                                    onToggleCollapsed = { viewModel.toggleTrackCollapsed(row.track) },
                                    onToggleLock = { viewModel.toggleTrackLock(row.track) },
                                    onToggleHidden = { viewModel.toggleTrackHidden(row.track) },
                                    onToggleMute = { viewModel.toggleTrackMute(row.track) },
                                    onToggleSolo = { viewModel.toggleTrackSolo(row.track) },
                                    onMenu = { pos ->
                                        headerMenu = row.track to Offset(pos.x, pos.y + (row.topDp * density.density) - vScroll.value)
                                    }
                                )
                            }
                        }
                        headerMenu?.let { (track, at) ->
                            val ts = state.trackStates[track] ?: TrackState()
                            EditorContextMenu(position = at, onDismiss = { headerMenu = null }) {
                                EditorMenuItem(if (ts.collapsed) "Expand ${track.label}" else "Collapse ${track.label}") {
                                    headerMenu = null; viewModel.toggleTrackCollapsed(track)
                                }
                                EditorMenuItem(
                                    label = if (rows.all { it.state.collapsed }) "Expand all tracks" else "Collapse all tracks",
                                    shortcut = state.bindings.hint(EditorShortcut.COLLAPSE_TRACKS)
                                ) { headerMenu = null; viewModel.toggleAllTracksCollapsed() }
                                EditorMenuItem("Collapse empty tracks") { headerMenu = null; viewModel.collapseEmptyTracks() }
                                EditorMenuDivider()
                                EditorMenuItem(if (ts.locked) "Unlock track" else "Lock track") {
                                    headerMenu = null; viewModel.toggleTrackLock(track)
                                }
                                EditorMenuItem(if (ts.hidden) "Show track" else "Hide track") {
                                    headerMenu = null; viewModel.toggleTrackHidden(track)
                                }
                                if (!track.isTitle) {
                                    EditorMenuItem(if (ts.muted) "Unmute track" else "Mute track") {
                                        headerMenu = null; viewModel.toggleTrackMute(track)
                                    }
                                    EditorMenuItem(if (ts.solo) "Stop soloing track" else "Solo track") {
                                        headerMenu = null; viewModel.toggleTrackSolo(track)
                                    }
                                }
                                EditorMenuDivider()
                                EditorMenuItem("Select all clips on this track") {
                                    headerMenu = null; viewModel.selectAllClips(track)
                                }
                                EditorMenuItem("Close gaps on this track") { headerMenu = null; viewModel.closeGaps(track) }
                            }
                        }
                    }
                }

                // ----------------------------------- scrolling ruler, lane, tracks and playhead
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(hScroll)
                ) {
                    Column(
                        modifier = Modifier
                            .width(contentWidthDp)
                            // Mouse wheel: Ctrl zooms around the pointer, Shift scrolls sideways.
                            // Seen on the way down so the scroll containers do not take it first.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type != PointerEventType.Scroll) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        val keys = event.keyboardModifiers
                                        val delta = change.scrollDelta
                                        if (keys.isCtrlPressed || keys.isMetaPressed) {
                                            val amount = if (delta.y != 0f) delta.y else delta.x
                                            if (amount != 0f) {
                                                val pps = currentPxPerSec
                                                if (pps > 0f) {
                                                    zoomAnchor = (change.position.x / pps).toDouble() to
                                                        (change.position.x - hScroll.value)
                                                }
                                                val factor = if (amount < 0f) 1.2 else 1.0 / 1.2
                                                viewModel.setTimelineZoom(currentScale * factor)
                                            }
                                            change.consume()
                                        } else if (keys.isShiftPressed && delta.y != 0f) {
                                            scope.launch { hScroll.scrollBy(delta.y * 72f) }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                            // Optional scrub mode: moving a mouse over the timeline moves the
                            // playhead, no click needed.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (!currentState.scrubFollow) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        if (event.type == PointerEventType.Move && change.type == PointerType.Mouse &&
                                            !event.buttons.isPrimaryPressed && currentPxPerSec > 0f
                                        ) {
                                            viewModel.seekTo(max(0.0, (change.position.x / currentPxPerSec).toDouble()))
                                        }
                                    }
                                }
                            }
                    ) {
                        // ---------------------------------------------------------- ruler
                        Box(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
                            TimelineRuler(
                                totalDurationSec = totalDur,
                                pxPerSec = pxPerSec,
                                scaleDpPerSec = scale,
                                visibleFromPx = { hScroll.value.toFloat() },
                                viewportPx = viewportPx,
                                rangeIn = state.exportSettings.rangeInSec,
                                rangeOut = state.exportSettings.rangeOutSec,
                                onSeek = viewModel::seekTo,
                                onMenu = { rulerMenu = it },
                                modifier = Modifier.fillMaxSize()
                            )
                            for (marker in state.markers) {
                                key(marker.time, marker.label) {
                                    MarkerPin(
                                        marker = marker,
                                        xDp = (marker.time * scale).toFloat(),
                                        onSeek = { viewModel.seekTo(marker.time) },
                                        onMenu = { pos ->
                                            markerMenu = marker to Offset((marker.time * pxPerSec).toFloat() + pos.x, pos.y)
                                        }
                                    )
                                }
                            }
                            rulerMenu?.let { at ->
                                val t = timeAt(at.x)
                                EditorContextMenu(position = at, onDismiss = { rulerMenu = null }) {
                                    EditorMenuItem("Add marker at ${formatTimecode(t)}", state.bindings.hint(EditorShortcut.ADD_MARKER)) {
                                        rulerMenu = null; viewModel.addMarkerAt(t)
                                    }
                                    EditorMenuDivider()
                                    EditorMenuItem("Set export In here", state.bindings.hint(EditorShortcut.MARK_IN)) {
                                        rulerMenu = null; viewModel.seekTo(t); viewModel.setExportRangePointAtPlayhead(true)
                                    }
                                    EditorMenuItem("Set export Out here", state.bindings.hint(EditorShortcut.MARK_OUT)) {
                                        rulerMenu = null; viewModel.seekTo(t); viewModel.setExportRangePointAtPlayhead(false)
                                    }
                                    EditorMenuItem(
                                        label = "Clear export range",
                                        shortcut = state.bindings.hint(EditorShortcut.CLEAR_RANGE),
                                        enabled = state.exportSettings.rangeInSec != null || state.exportSettings.rangeOutSec != null
                                    ) { rulerMenu = null; viewModel.clearExportRange() }
                                }
                            }
                            markerMenu?.let { (marker, at) ->
                                EditorContextMenu(position = at, onDismiss = { markerMenu = null }) {
                                    EditorMenuItem("Go to \"${marker.label}\"") { markerMenu = null; viewModel.seekTo(marker.time) }
                                    EditorMenuItem("Rename marker…") { markerMenu = null; renamingMarker = marker }
                                    EditorMenuItem("Set export In at marker") {
                                        markerMenu = null; viewModel.seekTo(marker.time); viewModel.setExportRangePointAtPlayhead(true)
                                    }
                                    EditorMenuItem("Set export Out at marker") {
                                        markerMenu = null; viewModel.seekTo(marker.time); viewModel.setExportRangePointAtPlayhead(false)
                                    }
                                    EditorMenuDivider()
                                    EditorMenuItem("Delete marker", destructive = true) { markerMenu = null; viewModel.removeMarker(marker) }
                                    EditorMenuItem("Delete all markers", destructive = true) { markerMenu = null; viewModel.clearMarkers() }
                                }
                            }
                        }

                        // -------------------------------------------------- keyframe lane
                        if (state.showKeyframeLane) {
                            KeyframeLane(
                                state = state,
                                resolved = resolved,
                                pxPerSec = pxPerSec,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxWidth().height(KEYFRAME_LANE_HEIGHT)
                            )
                        }

                        // ----------------------------------------------------- track rows
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowsHeightDp.dp)
                                    .onGloballyPositioned { coords ->
                                        val origin = coords.positionInRoot()
                                        onGeometry(
                                            TimelineDropGeometry(
                                                contentStartX = origin.x,
                                                panelTopY = panelTopY,
                                                pxPerSecond = pxPerSec,
                                                rows = rows.map {
                                                    TimelineRowGeometry(
                                                        it.track,
                                                        origin.y + it.topDp * density.density,
                                                        it.heightDp * density.density
                                                    )
                                                }
                                            )
                                        )
                                    }
                            ) {
                                // Row backgrounds. They draw only, so pointer events fall through
                                // to the gesture layer below wherever there is no clip.
                                Column(modifier = Modifier.fillMaxSize()) {
                                    rows.forEach { row ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(row.heightDp.dp)
                                                .background(
                                                    when {
                                                        row.state.locked -> palette.panel.copy(alpha = 0.45f)
                                                        row.state.hidden -> palette.panel.copy(alpha = 0.25f)
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .border(0.5.dp, palette.line)
                                        )
                                    }
                                }

                                // Empty-space gestures: tap to place the playhead, drag for a
                                // marquee, right-click or long-press for the menu.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures { offset ->
                                                viewModel.seekTo(timeAt(offset.x))
                                                if (!pointerModifiers.ctrl && !pointerModifiers.shift &&
                                                    !currentState.selectionMode
                                                ) {
                                                    viewModel.selectClip(null)
                                                }
                                            }
                                        }
                                        .onSecondaryClick(Unit) { emptyMenu = it }
                                        // Mouse: a plain drag draws the marquee straight away.
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = true)
                                                if (down.type != PointerType.Mouse) return@awaitEachGesture
                                                val start = down.position
                                                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                                    ?: return@awaitEachGesture
                                                marqueeBase = if (pointerModifiers.ctrl || pointerModifiers.shift ||
                                                    currentState.selectionMode
                                                ) currentState.selectionIds else emptySet()
                                                marquee = Rect(start, slop.position)
                                                applyMarquee(Rect(start, slop.position))
                                                drag(slop.id) { change ->
                                                    change.consume()
                                                    val rect = Rect(start, change.position)
                                                    marquee = rect
                                                    applyMarquee(rect)
                                                }
                                                marquee = null
                                            }
                                        }
                                        // Touch: a plain drag scrolls, so the marquee starts with a
                                        // long-press. Letting go without moving opens the menu.
                                        .pointerInput(Unit) {
                                            var start = Offset.Zero
                                            var moved = false
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    start = offset
                                                    moved = false
                                                    marqueeBase = if (currentState.selectionMode) currentState.selectionIds else emptySet()
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                                    if (moved) {
                                                        val rect = Rect(start, change.position)
                                                        marquee = rect
                                                        applyMarquee(rect)
                                                    }
                                                },
                                                onDragEnd = {
                                                    marquee = null
                                                    if (!moved) emptyMenu = start
                                                },
                                                onDragCancel = { marquee = null }
                                            )
                                        }
                                )

                                // Clips live in one layer and are keyed by id, so a clip keeps its
                                // gesture going while it is dragged from one track to another.
                                for (rc in resolved) {
                                    val row = rows.firstOrNull { it.track == rc.clip.track } ?: continue
                                    key(rc.clip.id) {
                                        TimelineClipBlock(
                                            resolved = rc,
                                            asset = rc.clip.assetId?.let { assetsById[it] },
                                            state = state,
                                            row = row,
                                            rows = rows,
                                            scaleDpPerSec = scale,
                                            viewModel = viewModel,
                                            pointerModifiers = pointerModifiers
                                        )
                                    }
                                }

                                marquee?.let { rect ->
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val topLeft = Offset(min(rect.left, rect.right), min(rect.top, rect.bottom))
                                        val size = Size(abs(rect.right - rect.left), abs(rect.bottom - rect.top))
                                        drawRect(palette.accent.copy(alpha = 0.16f), topLeft, size)
                                        drawRect(palette.accent, topLeft, size, style = Stroke(width = 1.dp.toPx()))
                                    }
                                }

                                emptyMenu?.let { at ->
                                    val t = timeAt(at.x)
                                    val row = rowAt(at.y)
                                    EditorContextMenu(position = at, onDismiss = { emptyMenu = null }) {
                                        EditorMenuItem(
                                            label = "Paste here",
                                            shortcut = state.bindings.hint(EditorShortcut.PASTE),
                                            enabled = state.clipClipboard.isNotEmpty()
                                        ) { emptyMenu = null; viewModel.pasteClips(t) }
                                        EditorMenuItem("Add text here") { emptyMenu = null; viewModel.addBlankTextClip(t) }
                                        EditorMenuItem("Add motion graphic here…") {
                                            emptyMenu = null; viewModel.seekTo(t); viewModel.setShowGraphicsModal(true)
                                        }
                                        EditorMenuItem("Add marker here") { emptyMenu = null; viewModel.addMarkerAt(t) }
                                        if (row != null) {
                                            EditorMenuDivider()
                                            EditorMenuItem("Close this gap") { emptyMenu = null; viewModel.closeGapAt(row.track, t) }
                                            EditorMenuItem("Insert 1 s of space here") {
                                                emptyMenu = null; viewModel.insertGapAt(row.track, t, 1.0)
                                            }
                                            EditorMenuItem("Close all gaps on ${row.track.label}") {
                                                emptyMenu = null; viewModel.closeGaps(row.track)
                                            }
                                            EditorMenuDivider()
                                            EditorMenuItem("Select all on ${row.track.label}") {
                                                emptyMenu = null; viewModel.selectAllClips(row.track)
                                            }
                                        } else {
                                            EditorMenuDivider()
                                        }
                                        EditorMenuItem("Select everything after here") {
                                            emptyMenu = null; viewModel.seekTo(t); viewModel.selectClipsFromPlayhead()
                                        }
                                        EditorMenuItem("Select all clips", state.bindings.hint(EditorShortcut.SELECT_ALL)) {
                                            emptyMenu = null; viewModel.selectAllClips()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Red playhead line with its cap, over the ruler, the lane and the tracks.
                    Canvas(
                        modifier = Modifier
                            .offset(x = (state.playheadSec * scale).dp)
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

            renamingMarker?.let { marker ->
                TextPromptDialog(
                    title = "Rename marker",
                    initial = marker.label,
                    confirmLabel = "Rename",
                    onConfirm = { label ->
                        viewModel.renameMarker(marker, label)
                        renamingMarker = null
                    },
                    onDismiss = { renamingMarker = null }
                )
            }
        }
    }
}

@Composable
private fun TimelineToolbar(
    state: EditorUiState,
    viewModel: EditProViewModel,
    onRun: (EditorShortcut) -> Unit,
    shownScale: Double,
    compact: Boolean
) {
    val palette = LocalEditProPalette.current
    // Key badges are for keyboards; on a phone they only take room.
    fun hint(action: EditorShortcut): String? = if (compact) null else state.bindings.hint(action)
    val selectedCount = state.selectionIds.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 52.dp else 44.dp)
            .background(palette.panel.copy(alpha = 0.9f))
            .border(0.5.dp, palette.line)
            .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 10.dp)
    ) {
        PanelToggleButton(
            glyph = if (state.timelineCollapsed) "▴" else "▾",
            description = if (state.timelineCollapsed) "Show timeline" else "Hide timeline",
            onClick = { viewModel.togglePanel(WorkspacePanel.TIMELINE) }
        )
        SmileIconButton(
            icon = SmileIconType.ADD_TEXT,
            accessibleLabel = "Add text clip",
            shortcutBadge = hint(EditorShortcut.ADD_TEXT),
            onClick = { onRun(EditorShortcut.ADD_TEXT) }
        )
        SmileIconButton(
            icon = SmileIconType.MODE_MOTION,
            accessibleLabel = "Add motion graphic",
            shortcutBadge = hint(EditorShortcut.ADD_GRAPHIC),
            onClick = { onRun(EditorShortcut.ADD_GRAPHIC) }
        )
        SmileIconButton(
            icon = SmileIconType.SPLIT,
            accessibleLabel = "Split at playhead",
            shortcutBadge = hint(EditorShortcut.SPLIT),
            onClick = { onRun(EditorShortcut.SPLIT) }
        )
        SmileIconButton(
            icon = SmileIconType.TRASH,
            accessibleLabel = "Delete selection",
            isDanger = true,
            onClick = { onRun(EditorShortcut.DELETE) }
        )
        SmileIconButton(
            icon = SmileIconType.UNDO,
            accessibleLabel = "Undo",
            enabled = state.canUndo,
            onClick = { onRun(EditorShortcut.UNDO) }
        )
        SmileIconButton(
            icon = SmileIconType.REDO,
            accessibleLabel = "Redo",
            enabled = state.canRedo,
            onClick = { onRun(EditorShortcut.REDO) }
        )

        // The playhead readout doubles as a scrub control - drag it sideways to seek.
        DraggableTimecode(
            seconds = state.playheadSec,
            onDelta = { delta -> viewModel.seekTo((state.playheadSec + delta).coerceAtLeast(0.0)) },
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
            maxLines = 1
        )
        if (selectedCount > 1) {
            Text(
                text = "$selectedCount selected",
                color = Color(0xFFFFD60A),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        if (!compact) Spacer(modifier = Modifier.weight(1f))

        ToolbarToggle("Multi", state.selectionMode, "Multi-select: every tap adds to the selection") {
            onRun(EditorShortcut.SELECTION_MODE)
        }
        ToolbarToggle("Snap", state.magneticSnap, "Magnetic snapping") { onRun(EditorShortcut.TOGGLE_SNAP) }
        ToolbarToggle("◆ Keys", state.showKeyframeLane, "Keyframe editor lane") { onRun(EditorShortcut.KEYFRAME_LANE) }

        SmileIconButton(
            icon = SmileIconType.ZOOM_OUT,
            accessibleLabel = "Zoom out",
            onClick = { viewModel.setTimelineZoom(shownScale / 1.25) }
        )
        // The slider works in powers so the same travel covers a second and an hour.
        Slider(
            value = ln(shownScale.coerceIn(MIN_ZOOM, MAX_ZOOM)).toFloat(),
            onValueChange = { viewModel.setTimelineZoom(exp(it.toDouble())) },
            valueRange = ln(MIN_ZOOM).toFloat()..ln(MAX_ZOOM).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.line
            ),
            modifier = Modifier.width(96.dp)
        )
        SmileIconButton(
            icon = SmileIconType.ZOOM_IN,
            accessibleLabel = "Zoom in",
            onClick = { viewModel.setTimelineZoom(shownScale * 1.25) }
        )
        ToolbarToggle("Fit", state.timelineAutoFit, "Fit the whole edit in the window") { viewModel.toggleAutoFitTimeline() }
        SmileIconButton(
            icon = SmileIconType.FOLLOW,
            accessibleLabel = "Follow playhead",
            shortcutBadge = hint(EditorShortcut.TOGGLE_FOLLOW),
            isActive = state.timelineFollowPlayhead,
            onClick = { onRun(EditorShortcut.TOGGLE_FOLLOW) }
        )
    }
}

/** A small text toggle for the timeline toolbar. */
@Composable
private fun ToolbarToggle(label: String, active: Boolean, description: String, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    val minHeight = if (rememberIsCompact()) 44.dp else 30.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.accent.copy(alpha = 0.28f) else palette.controlBg)
            .border(0.5.dp, if (active) palette.accent.copy(alpha = 0.7f) else palette.line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = "$description: ${if (active) "on" else "off"}" }
            .padding(horizontal = 9.dp)
    ) {
        Text(
            text = label,
            color = if (active) palette.text else palette.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackHeader(
    row: TrackRow,
    onToggleCollapsed: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onMenu: (Offset) -> Unit
) {
    val palette = LocalEditProPalette.current
    val ts = row.state
    val track = row.track
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(row.heightDp.dp)
            .border(0.5.dp, palette.line)
            .combinedClickable(
                onClick = onToggleCollapsed,
                onLongClick = { onMenu(Offset(12f, 12f)) },
                onClickLabel = if (ts.collapsed) "Expand ${track.label}" else "Collapse ${track.label}"
            )
            .onSecondaryClick(track) { onMenu(it) }
            .padding(horizontal = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (ts.collapsed) "▸" else "▾", color = palette.muted, fontSize = 8.sp)
            Text(
                text = track.label,
                color = if (ts.hidden || ts.muted) palette.muted.copy(alpha = 0.55f) else palette.muted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ts.collapsed && ts.locked) Text("L", color = palette.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        if (!ts.collapsed) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TrackMiniToggle("L", ts.locked, "Lock ${track.label}", onToggleLock)
                TrackMiniToggle("H", ts.hidden, "Hide ${track.label}", onToggleHidden)
                // Picture tracks have sound too: a video clip carries its own audio.
                if (!track.isTitle) {
                    TrackMiniToggle("M", ts.muted, "Mute ${track.label}", onToggleMute)
                    TrackMiniToggle("S", ts.solo, "Solo ${track.label}", onToggleSolo)
                }
            }
        }
    }
}

@Composable
private fun TrackMiniToggle(label: String, active: Boolean, description: String, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 16.dp, height = 15.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) palette.accent else palette.controlBg)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text(
            text = label,
            color = if (active) Color.White else palette.muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The time ruler. Only the ticks inside the visible part are drawn, straight onto a canvas, so a
 * long edit at high zoom costs no more than a short one.
 */
@Composable
private fun TimelineRuler(
    totalDurationSec: Double,
    pxPerSec: Float,
    scaleDpPerSec: Double,
    visibleFromPx: () -> Float,
    viewportPx: Float,
    rangeIn: Double?,
    rangeOut: Double?,
    onSeek: (Double) -> Unit,
    onMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    val tickStep = niceTimelineStep(82.0 / max(0.1, scaleDpPerSec))
    val currentPxPerSec by rememberUpdatedState(pxPerSec)
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    labelPaint.color = palette.muted.toArgb()
    labelPaint.textSize = with(density) { 9.5.sp.toPx() }
    labelPaint.typeface = android.graphics.Typeface.MONOSPACE

    Canvas(
        modifier = modifier
            .background(palette.rulerBg)
            .border(0.5.dp, palette.line)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset -> onMenu(offset) },
                    onTap = { offset ->
                        if (currentPxPerSec > 0f) onSeek(max(0.0, (offset.x / currentPxPerSec).toDouble()))
                    }
                )
            }
            .onSecondaryClick(Unit) { onMenu(it) }
            .pointerInput(Unit) {
                detectDragGestures(
                    // Snap to the press point straight away, then follow the pointer, so a scrub
                    // responds on the first movement instead of lagging behind it.
                    onDragStart = { offset ->
                        if (currentPxPerSec > 0f) onSeek(max(0.0, (offset.x / currentPxPerSec).toDouble()))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (currentPxPerSec > 0f) onSeek(max(0.0, (change.position.x / currentPxPerSec).toDouble()))
                    }
                )
            }
    ) {
        if (pxPerSec <= 0f) return@Canvas
        // The stretch that will be exported, when an In or Out point is set.
        if (rangeIn != null || rangeOut != null) {
            val from = ((rangeIn ?: 0.0) * pxPerSec).toFloat()
            val to = ((rangeOut ?: totalDurationSec) * pxPerSec).toFloat()
            if (to > from) {
                drawRect(
                    color = Color(0xFF30D158).copy(alpha = 0.30f),
                    topLeft = Offset(from, size.height - 5.dp.toPx()),
                    size = Size(to - from, 5.dp.toPx())
                )
            }
        }
        val left = visibleFromPx()
        val firstTick = floor((left / pxPerSec) / tickStep) * tickStep
        val lastTime = min(totalDurationSec, ((left + viewportPx) / pxPerSec).toDouble() + tickStep)
        var t = max(0.0, firstTick)
        val baseline = 5.dp.toPx() + labelPaint.textSize
        val tickColor = palette.line
        drawIntoCanvas { canvas ->
            while (t <= lastTime + tickStep * 0.01) {
                val x = (t * pxPerSec).toFloat()
                drawLine(tickColor, Offset(x, size.height - 6.dp.toPx()), Offset(x, size.height), strokeWidth = 1f)
                canvas.nativeCanvas.drawText(formatTimecode(t).take(5), x + 3.dp.toPx(), baseline, labelPaint)
                t += tickStep
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerPin(
    marker: TimelineMarker,
    xDp: Float,
    onSeek: () -> Unit,
    onMenu: (Offset) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset(x = xDp.dp - 5.dp, y = 0.dp)
            .combinedClickable(onClick = onSeek, onLongClick = { onMenu(Offset(0f, 8f)) })
            .onSecondaryClick(marker) { onMenu(it) }
            .semantics { contentDescription = "Marker ${marker.label}" }
    ) {
        Text(text = "▼", color = Color(0xFFFF9F0A), fontSize = 9.sp)
        Text(
            text = marker.label,
            color = Color(0xFFFF9F0A),
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(54.dp).padding(start = 1.dp)
        )
    }
}

/**
 * The keyframe lane: the selected clip's keyframes on the timeline's own time axis.
 *
 * Tap a diamond to jump to it, drag it to retime it, right-click or long-press it for easing and
 * delete, and double-tap an empty spot inside the clip to add a keyframe there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyframeLane(
    state: EditorUiState,
    resolved: List<ResolvedClip>,
    pxPerSec: Float,
    viewModel: EditProViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val density = LocalDensity.current
    val rc = resolved.find { it.clip.id == state.selectedClipId }
    val keys = rc?.clip?.keyframes?.sortedBy { it.time }.orEmpty()
    val currentRc by rememberUpdatedState(rc)
    val currentPxPerSec by rememberUpdatedState(pxPerSec)
    var menu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }

    Box(
        modifier = modifier
            .background(palette.rulerBg.copy(alpha = 0.6f))
            .border(0.5.dp, palette.line)
    ) {
        if (rc == null) {
            Text(
                "Select a clip to see and edit its keyframes here.",
                color = palette.muted,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
            )
            return@Box
        }
        val startPx = (rc.start * pxPerSec).toFloat()
        val widthPx = max(4f, (rc.duration * pxPerSec).toFloat())
        // The clip's span; double-tap inside it adds a keyframe at that moment.
        Box(
            modifier = Modifier
                .offset(x = (startPx / density.density).dp, y = 9.dp)
                .width((widthPx / density.density).dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFFD60A).copy(alpha = 0.14f))
                .border(0.5.dp, Color(0xFFFFD60A).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val r = currentRc ?: return@detectTapGestures
                            if (currentPxPerSec > 0f) viewModel.seekTo(r.start + offset.x / currentPxPerSec)
                        },
                        onDoubleTap = { offset ->
                            if (currentPxPerSec > 0f) viewModel.addKeyframeAt((offset.x / currentPxPerSec).toDouble())
                        }
                    )
                }
        )
        if (keys.isEmpty()) {
            Text(
                "No keyframes yet — double-tap this bar, or press ◆＋, to add one.",
                color = palette.muted,
                fontSize = 9.5.sp,
                maxLines = 1,
                modifier = Modifier
                    .offset(x = (startPx / density.density).dp + 8.dp)
                    .align(Alignment.CenterStart)
            )
        }
        val playheadLocal = state.playheadSec - rc.start
        keys.forEachIndexed { index, kf ->
            val atPlayhead = abs(kf.time - playheadLocal) <= KeyframeOps.MERGE_TOLERANCE
            val x = startPx + (kf.time.coerceIn(0.0, rc.duration) * pxPerSec).toFloat()
            key(rc.clip.id, index) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = (x / density.density).dp - 11.dp, y = 4.dp)
                        .size(22.dp)
                        .combinedClickable(
                            onClick = { currentRc?.let { r -> r.clip.keyframes.sortedBy { it.time }.getOrNull(index)?.let { k -> viewModel.seekTo(r.start + k.time) } } },
                            onLongClick = { menu = index to Offset(x, 22f) }
                        )
                        .onSecondaryClick(index) { menu = index to Offset(x, 22f) }
                        .pointerInput(rc.clip.id, index) {
                            var moving = index
                            var time = 0.0
                            detectDragGestures(
                                onDragStart = {
                                    val r = currentRc
                                    moving = index
                                    time = r?.clip?.keyframes?.sortedBy { it.time }?.getOrNull(index)?.time ?: 0.0
                                    viewModel.snapHistory()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (currentPxPerSec > 0f) {
                                        time += amount.x / currentPxPerSec
                                        // The index changes when a keyframe is dragged past another.
                                        moving = viewModel.moveKeyframe(moving, time)
                                    }
                                }
                            )
                        }
                        .semantics { contentDescription = "Keyframe at ${formatTimecode(kf.time)}" }
                ) {
                    Text(
                        text = "◆",
                        color = if (atPlayhead) Color.White else Color(0xFFFFD60A),
                        fontSize = if (atPlayhead) 15.sp else 12.sp
                    )
                }
            }
        }

        menu?.let { (index, at) ->
            val kf = keys.getOrNull(index)
            EditorContextMenu(position = at, onDismiss = { menu = null }) {
                if (kf != null) {
                    EditorMenuItem("Go to keyframe") { menu = null; viewModel.seekTo(rc.start + kf.time) }
                    EditorMenuDivider()
                    KeyframeOps.EASINGS.forEach { (id, label) ->
                        EditorMenuItem((if (kf.easing == id) "● " else "○ ") + label) {
                            menu = null
                            viewModel.setKeyframeEasing(index, id)
                        }
                    }
                    EditorMenuDivider()
                    EditorMenuItem("Delete keyframe", destructive = true) { menu = null; viewModel.removeKeyframeAtIndex(index) }
                }
            }
        }
    }
}
