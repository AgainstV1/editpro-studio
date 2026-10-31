package com.editpro.studio

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.editpro.studio.mcp.AgentHost
import com.editpro.studio.mcp.McpService
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.WorkspaceMode
import com.editpro.studio.model.formatFileSize
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.ui.AgentAccessDialog
import com.editpro.studio.ui.AgentChatDialog
import com.editpro.studio.ui.AlertSheetDialog
import com.editpro.studio.ui.ClipMenuSettingsDialog
import com.editpro.studio.ui.ConfirmDialog
import com.editpro.studio.ui.EditProStudioTheme
import com.editpro.studio.ui.EditingToolsDialog
import com.editpro.studio.ui.ExportVideoDialog
import com.editpro.studio.ui.InspectorPanel
import com.editpro.studio.ui.LocalEditProPalette
import com.editpro.studio.ui.AppWordmark
import com.editpro.studio.ui.PresetPacksHubDialog
import com.editpro.studio.ui.PreviewCenterPanel
import com.editpro.studio.ui.ProjectHomeScreen
import com.editpro.studio.ui.ShortcutsDialog
import com.editpro.studio.ui.SmileIconButton
import com.editpro.studio.ui.SmileIconType
import com.editpro.studio.ui.SolidColorClipDialog
import com.editpro.studio.ui.TimelineDropGeometry
import com.editpro.studio.ui.TimelinePanel
import com.editpro.studio.viewmodel.EditProViewModel
import com.editpro.studio.viewmodel.EditorUiState
import com.editpro.studio.viewmodel.PendingConfirm
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // Process-wide, so the always-on MCP service and this UI act on the same project state.
    private val viewModel: EditProViewModel by lazy { AgentHost.ensure(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Agent access is on by default: bring the keep-alive service up with the editor.
        if (viewModel.uiState.value.mcpAutoStart) {
            runCatching { McpService.start(this) }
        }
        setContent {
            val state by viewModel.uiState.collectAsState()
            EditProStudioTheme(isLight = state.isLightTheme) {
                EditProWorkspaceScreen(viewModel = viewModel, state = state)
            }
        }
    }
}

@Composable
fun EditProWorkspaceScreen(
    viewModel: EditProViewModel,
    state: EditorUiState
) {
    val palette = LocalEditProPalette.current
    val context = LocalContext.current

    // Text fields take priority over the editor shortcuts. While one of them has focus the
    // global key handler below stays out of the way, so typing "s", space, Delete, "t" and
    // so on is not hijacked by Split / Play / Delete-clip / Add-text.
    var projectNameFocused by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val anyTextFieldFocused = projectNameFocused || searchFocused

    val hostActivity = context as? Activity
    var immersive by remember { mutableStateOf(false) }

    // Asset drag-and-drop: the dragged asset, the pointer in root coordinates, and the timeline's
    // layout (reported by TimelinePanel) so a drop can be turned into a track and a time.
    var dragAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var timelineGeometry by remember { mutableStateOf<TimelineDropGeometry?>(null) }
    var showAgentAccess by remember { mutableStateOf(false) }

    // Native Android Storage Access Framework Launchers
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importMediaUris(uris)
    }

    val openProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val read = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            val text = read.getOrNull()
            when {
                read.isFailure -> viewModel.showAlert(
                    "OPEN FAILED",
                    "The project file could not be read. ${read.exceptionOrNull()?.message ?: ""}".trim()
                )
                text.isNullOrBlank() -> viewModel.showAlert(
                    "OPEN FAILED",
                    "The selected file is empty or unreadable."
                )
                else -> viewModel.openProjectJson(text)
            }
        }
    }

    val saveProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val failure = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("The chosen file could not be opened for writing.")
                stream.bufferedWriter().use { writer ->
                    writer.write(viewModel.exportProjectJson())
                    writer.flush()
                }
            }.exceptionOrNull()
            // Success is reported only once the write has actually completed without error.
            if (failure == null) {
                viewModel.recordRecentProject()
                viewModel.showToast("Project saved to ${uri.lastPathSegment ?: "file"}")
            } else {
                viewModel.showAlert(
                    "SAVE FAILED",
                    "The project was not saved. ${failure.message ?: "Unknown error."}"
                )
            }
        }
    }

    val importSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                viewModel.showAlert("IMPORT FAILED", "The subtitle file could not be read.")
            } else {
                viewModel.importSrtContent(text)
            }
        }
    }

    val exportPresetBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val failure = runCatching {
                val json = ProjectSerializer.exportPresetBundleJson(
                    customStyles = state.customStylePresets,
                    customMotions = state.customMotionPresets,
                    customEquipment = state.customEquipmentRigs,
                    customAudioGear = state.customAudioGear
                )
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("The chosen file could not be opened for writing.")
                stream.bufferedWriter().use { it.write(json) }
            }.exceptionOrNull()
            if (failure == null) {
                viewModel.showToast("Exported Universal Preset & Equipment Pack JSON.")
            } else {
                viewModel.showAlert("EXPORT FAILED", failure.message ?: "Unknown error.")
            }
        }
    }

    // Shared by the keyboard shortcuts so the "discard current work?" guard is not bypassed.
    val requestNewProject: () -> Unit = {
        if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.NEW_PROJECT)
        else viewModel.startNewProject()
    }
    val requestOpenProject: () -> Unit = {
        if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.OPEN_PROJECT)
        else openProjectLauncher.launch(arrayOf("*/*"))
    }
    val requestImportMedia: () -> Unit = {
        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "image/*"))
    }

    // Turns a drop point into a track and a time. Falls back to the natural track when the point
    // is outside the timeline, or lands on a track that cannot hold this kind of media.
    fun dropAsset(asset: MediaAsset) {
        val natural = if (asset.kind == AssetKind.AUDIO) TimelineTrack.AUDIO else TimelineTrack.VIDEO
        val geom = timelineGeometry
        val pos = dragPosition
        val target = if (geom == null) natural else {
            val index = ((pos.y - geom.tracksTopY) / geom.rowHeightPx).toInt()
            val track = TimelineTrack.entries.getOrNull(index)
            when {
                track == null || track == TimelineTrack.TEXT -> natural
                track.isAudio != (asset.kind == AssetKind.AUDIO) -> natural
                else -> track
            }
        }
        val at = if (geom != null && geom.pxPerSecond > 0f) {
            ((pos.x - geom.contentStartX) / geom.pxPerSecond).toDouble().coerceAtLeast(0.0)
        } else 0.0
        viewModel.addAssetToTimeline(asset, target, at)
    }

    // Immersive full screen, now reached from the Tools panel rather than a macOS-style dot.
    val toggleFullscreen: () -> Unit = {
        immersive = !immersive
        hostActivity?.let { act ->
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Compose only delivers hardware key events to a focused node. Nothing in the app ever took
    // focus, so Space / S / Backspace / Delete never reached the handler at all. The workspace
    // root now takes focus itself and reclaims it whenever a dialog closes.
    val rootFocus = remember { FocusRequester() }
    val anyDialogOpen = state.showExportModal || state.showToolsModal || state.showSolidModal ||
        state.showPresetsModal || state.showShortcutsModal || state.showClipMenuModal || showAgentAccess ||
        state.showAgentChat ||
        state.pendingConfirm != null || state.alertTitle != null
    LaunchedEffect(state.showHome, anyDialogOpen) {
        if (!state.showHome && !anyDialogOpen) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Never swallow keys that belong to a text field, and stay idle on the home screen.
                if (anyTextFieldFocused || state.showHome) return@onPreviewKeyEvent false
                val ctrl = ev.isCtrlPressed || ev.isMetaPressed
                val alt = ev.isAltPressed
                val shift = ev.isShiftPressed

                // Shortcut-Only Light Mode toggle: Alt+L or Ctrl+Shift+L
                if ((alt && !ctrl && ev.key == Key.L) || (ctrl && shift && ev.key == Key.L)) {
                    viewModel.toggleLightTheme()
                    return@onPreviewKeyEvent true
                }
                // Workspace Mode Switching: Alt+1..4
                if (alt && !ctrl) {
                    when (ev.key) {
                        Key.One -> { viewModel.setWorkspaceMode(WorkspaceMode.EDIT); return@onPreviewKeyEvent true }
                        Key.Two -> { viewModel.setWorkspaceMode(WorkspaceMode.COLOR); return@onPreviewKeyEvent true }
                        Key.Three -> { viewModel.setWorkspaceMode(WorkspaceMode.MOTION); return@onPreviewKeyEvent true }
                        Key.Four -> { viewModel.setWorkspaceMode(WorkspaceMode.MUSIC); return@onPreviewKeyEvent true }
                        Key.S -> { viewModel.toggleScopesPinned(); return@onPreviewKeyEvent true }
                        Key.F -> { viewModel.toggleScrubFollow(); return@onPreviewKeyEvent true }
                        else -> Unit
                    }
                }

                // File / panel shortcuts that the header buttons advertise on their badges.
                // These previously did nothing at all, so the badges were misleading.
                if (ctrl) {
                    when (ev.key) {
                        Key.N -> { requestNewProject(); return@onPreviewKeyEvent true }
                        Key.S -> {
                            saveProjectLauncher.launch("${sanitizeFileName(state.projectName)}.filmcraft")
                            return@onPreviewKeyEvent true
                        }
                        Key.O -> { requestOpenProject(); return@onPreviewKeyEvent true }
                        Key.E -> { viewModel.setShowExportModal(true); return@onPreviewKeyEvent true }
                        Key.K -> { viewModel.setShowToolsModal(true); return@onPreviewKeyEvent true }
                        Key.I -> { requestImportMedia(); return@onPreviewKeyEvent true }
                        Key.P -> if (alt) {
                            viewModel.setShowPresetsModal(true)
                            return@onPreviewKeyEvent true
                        } else Unit
                        else -> Unit
                    }
                }

                when (ev.key) {
                    Key.Spacebar -> { viewModel.togglePlayPause(); true }
                    Key.S -> if (!ctrl && !alt) { viewModel.splitAtPlayhead(); true } else false
                    Key.Delete, Key.Backspace -> {
                        if (shift) viewModel.rippleDeleteSelectedClip() else viewModel.deleteSelectedClips()
                        true
                    }
                    Key.Z -> if (ctrl) { if (shift) viewModel.redo() else viewModel.undo(); true } else false
                    Key.D -> if (ctrl) { viewModel.duplicateSelectedClips(); true } else false
                    Key.M -> if (ctrl) { viewModel.addMarkerAtPlayhead(); true } else false
                    Key.T -> if (!ctrl && !alt) { viewModel.addBlankTextClip(); true } else false
                    Key.Backslash -> { viewModel.toggleGradeSplitWipe(); true }
                    Key.DirectionLeft -> { viewModel.seekTo(state.playheadSec - 0.1); true }
                    Key.DirectionRight -> { viewModel.seekTo(state.playheadSec + 0.1); true }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header Bar (52dp) with macOS Traffic Lights, Mode Switcher Pill Bar, and Smile Action Buttons
            TopHeaderBar(
                state = state,
                onProjectNameChange = viewModel::setProjectName,
                onProjectNameFocusChange = { projectNameFocused = it },
                onSelectMode = viewModel::setWorkspaceMode,
                onNewProject = {
                    if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.NEW_PROJECT)
                    else viewModel.startNewProject()
                },
                onLoadDemoReel = {
                    if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.DEMO_REEL)
                    else viewModel.loadProDemoReel()
                },
                onOpenPresets = { viewModel.setShowPresetsModal(true) },
                onOpenTools = { viewModel.setShowToolsModal(true) },
                onSaveProject = { saveProjectLauncher.launch("${sanitizeFileName(state.projectName)}.filmcraft") },
                onOpenProject = {
                    if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.OPEN_PROJECT)
                    else openProjectLauncher.launch(arrayOf("*/*"))
                },
                onOpenExport = { viewModel.setShowExportModal(true) }
            )

            // Main 3-Pane Split Workspace (Media Library | Preview Center | 5-Tab Inspector)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MediaLibraryPanel(
                    state = state,
                    onImportClick = { mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "image/*")) },
                    onDemoClick = viewModel::loadProDemoReel,
                    onSearchChange = viewModel::setAssetSearchQuery,
                    onSearchFocusChange = { searchFocused = it },
                    onFilterChange = viewModel::setMediaFilter,
                    onSelectAsset = viewModel::selectAsset,
                    onAddAssetToTimeline = { asset -> viewModel.addAssetToTimeline(asset) },
                    onToggleFavorite = viewModel::toggleAssetFavorite,
                    onAssetDragStart = { asset, pos ->
                        dragAsset = asset
                        dragPosition = pos
                    },
                    onAssetDragMove = { pos -> dragPosition = pos },
                    onAssetDragEnd = {
                        val dropped = dragAsset
                        dragAsset = null
                        if (dropped != null) dropAsset(dropped)
                    },
                    modifier = Modifier.width(state.leftPanelWidthDp.dp)
                )

                // Left Vertical Splitter
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(palette.bg)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewModel.resizePanels(leftDeltaDp = dragAmount.x / density)
                            }
                        }
                )

                PreviewCenterPanel(
                    state = state,
                    onPlayPause = viewModel::togglePlayPause,
                    onSeek = viewModel::seekTo,
                    onUpdateSelectedClip = viewModel::updateSelectedClip,
                    onSnapHistory = viewModel::snapHistory,
                    onToggleScopes = viewModel::toggleScopesPinned,
                    onSelectScopeMode = viewModel::setScopesMode,
                    onToggleGradeSplit = viewModel::toggleGradeSplitWipe,
                    onGradeSplitChange = viewModel::setGradeSplitPercent,
                    modifier = Modifier.weight(1f)
                )

                // Right Vertical Splitter
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(palette.bg)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewModel.resizePanels(rightDeltaDp = -dragAmount.x / density)
                            }
                        }
                )

                InspectorPanel(
                    state = state,
                    onSelectTab = viewModel::setInspectorTab,
                    onSnapHistory = viewModel::snapHistory,
                    onUpdateClip = viewModel::updateSelectedClip,
                    onResetClip = viewModel::resetSelectedClipEffectsAndTransform,
                    onResetColor = viewModel::resetSelectedClipColor,
                    onResetTransform = viewModel::resetSelectedClipTransform,
                    onApplyLook = viewModel::applyLookPreset,
                    onApplyMotion = viewModel::applyMotionPreset,
                    onApplyEquipmentRig = viewModel::applyEquipmentRig,
                    onApplyAudioGear = viewModel::applyAudioGearPreset,
                    onAddKeyframe = viewModel::addOrUpdateKeyframeAtPlayhead,
                    onRemoveKeyframe = viewModel::removeKeyframeAtIndex,
                    onClearKeyframes = viewModel::clearKeyframesOnSelectedClip,
                    onSaveMotionPreset = viewModel::saveCurrentClipAsMotionPreset,
                    onAddVolumePoint = viewModel::addVolumeAutomationPointAtPlayhead,
                    onUpdateVolumePoint = viewModel::updateVolumeAutomationPoint,
                    onRemoveVolumePoint = viewModel::removeVolumeAutomationPoint,
                    onClearVolumePoints = viewModel::clearVolumeAutomation,
                    onDetectBeats = viewModel::detectBeatsOnSelectedClip,
                    onToggleGradeSplit = viewModel::toggleGradeSplitWipe,
                    onCopyAttrs = viewModel::copySelectedClipAttributes,
                    onPasteAttrs = viewModel::pasteClipAttributesToSelected,
                    onDuplicateClip = viewModel::duplicateSelectedClips,
                    onFreezeFrame = viewModel::createFreezeFrameFromSelectedClip,
                    onRunHealthScan = viewModel::runProjectHealthScan,
                    onToggleSafeGuides = viewModel::toggleSafeGuides,
                    modifier = Modifier.width(state.rightPanelWidthDp.dp)
                )
            }

            // Horizontal Timeline Splitter
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(palette.bg)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewModel.resizePanels(timelineDeltaDp = -dragAmount.y / density)
                        }
                    }
            )

            // Bottom Multi-Track Timeline Panel
            TimelinePanel(
                state = state,
                onAddText = viewModel::addBlankTextClip,
                onSplit = viewModel::splitAtPlayhead,
                onDelete = viewModel::deleteSelectedClips,
                onRippleDelete = viewModel::rippleDeleteSelectedClip,
                onDuplicate = viewModel::duplicateSelectedClips,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onToggleFollow = viewModel::toggleFollowPlayhead,
                onZoomChange = viewModel::setTimelineZoom,
                onSeek = viewModel::seekTo,
                onSelectClip = viewModel::selectClip,
                onMoveOrTrimClip = viewModel::moveOrTrimClip,
                onSnapHistory = viewModel::snapHistory,
                onUpdateSelectedClip = viewModel::updateSelectedClip,
                onToggleTrackLock = viewModel::toggleTrackLock,
                onToggleTrackHidden = viewModel::toggleTrackHidden,
                onToggleTrackMute = viewModel::toggleTrackMute,
                onToggleTrackSolo = viewModel::toggleTrackSolo,
                onAddKeyframeAtPlayhead = viewModel::addOrUpdateKeyframeAtPlayhead,
                onCopyClipProps = viewModel::copySelectedClipAttributes,
                onPasteClipProps = viewModel::pasteClipAttributesToSelected,
                onResetClipProps = viewModel::resetSelectedClipEffectsAndTransform,
                onSetWorkspaceMode = viewModel::setWorkspaceMode,
                onApplyEquipmentRig = viewModel::applyEquipmentRig,
                onApplyAudioGear = viewModel::applyAudioGearPreset,
                onDetectBeats = viewModel::detectBeatsOnSelectedClip,
                onOpenPresetHub = { viewModel.setShowPresetsModal(true) },
                onToggleGradeSplit = viewModel::toggleGradeSplitWipe,
                onGeometry = { timelineGeometry = it },
                modifier = Modifier.height(state.timelineHeightDp.dp)
            )
        }

        // Floating ghost while an asset is being dragged from the library onto the timeline.
        dragAsset?.let { dragged ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(dragPosition.x.roundToInt(), dragPosition.y.roundToInt()) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.accent.copy(alpha = 0.92f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = dragged.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Modals
        if (state.showExportModal) {
            ExportVideoDialog(
                state = state,
                onDismiss = { viewModel.setShowExportModal(false) },
                onUpdateSettings = viewModel::updateExportSettings,
                onStartExport = viewModel::startExportNow,
                onQueueExport = viewModel::queueCurrentExport,
                onRunQueue = viewModel::runRenderQueue,
                onOpenExport = { path -> openExportedFile(context, path) },
                onShareExport = { path -> shareExportedFile(context, path) },
                onCancelExport = viewModel::cancelExport
            )
        }

        if (state.showToolsModal) {
            EditingToolsDialog(
                state = state,
                onDismiss = { viewModel.setShowToolsModal(false) },
                onOpenPresets = { viewModel.setShowPresetsModal(true) },
                onSetRangeIn = { viewModel.setExportRangePointAtPlayhead(true) },
                onSetRangeOut = { viewModel.setExportRangePointAtPlayhead(false) },
                onAddMarker = { viewModel.addMarkerAtPlayhead() },
                onOpenSolidModal = { viewModel.setShowSolidModal(true) },
                onImportSrtClick = { importSrtLauncher.launch(arrayOf("*/*")) },
                onDuplicate = viewModel::duplicateSelectedClips,
                onRippleDelete = viewModel::rippleDeleteSelectedClip,
                onMoveToPlayhead = viewModel::moveSelectedClipToPlayhead,
                onNudgeBack = { viewModel.nudgeSelectedClip(-0.5) },
                onNudgeForward = { viewModel.nudgeSelectedClip(0.5) },
                onPrevMarker = { viewModel.jumpToMarker(-1) },
                onNextMarker = { viewModel.jumpToMarker(1) },
                onToggleSafeGuides = viewModel::toggleSafeGuides,
                onToggleChecker = viewModel::toggleCheckerboard,
                onHealthScan = viewModel::runProjectHealthScan,
                onSelectNext = { viewModel.selectAdjacentClip(1) },
                onSelectPrev = { viewModel.selectAdjacentClip(-1) },
                onClearTimeline = viewModel::clearTimeline,
                onToggleSnap = viewModel::toggleMagneticSnap,
                onToggleAutoFit = viewModel::toggleAutoFitTimeline,
                onToggleCompact = viewModel::toggleCompactTimeline,
                onOpenShortcuts = { viewModel.setShowShortcutsModal(true) },
                onToggleFullscreen = toggleFullscreen,
                onOpenClipMenu = { viewModel.setShowClipMenuModal(true) },
                onToggleScrubFollow = viewModel::toggleScrubFollow,
                onOpenAgentAccess = { showAgentAccess = true },
                onOpenAgentChat = { viewModel.setShowAgentChat(true) }
            )
        }

        if (state.showShortcutsModal) {
            ShortcutsDialog(onDismiss = { viewModel.setShowShortcutsModal(false) })
        }

        if (state.showClipMenuModal) {
            ClipMenuSettingsDialog(
                state = state,
                onToggle = viewModel::toggleClipMenuItem,
                onMove = viewModel::moveClipMenuItem,
                onReset = viewModel::resetClipMenu,
                onDismiss = { viewModel.setShowClipMenuModal(false) }
            )
        }

        if (showAgentAccess) {
            AgentAccessDialog(
                state = state,
                onStart = { viewModel.setAgentAccessEnabled(true) },
                onStop = { viewModel.setAgentAccessEnabled(false) },
                onRegenerate = { viewModel.regenerateMcpToken() },
                onDismiss = { showAgentAccess = false }
            )
        }

        if (state.showAgentChat) {
            AgentChatDialog(
                state = state,
                onSend = viewModel::sendAgentMessage,
                onStop = viewModel::stopAgent,
                onClear = viewModel::clearAgentChat,
                onSetBaseUrl = viewModel::setAgentBaseUrl,
                onSetModel = viewModel::setAgentModel,
                onSaveKey = viewModel::saveAgentKey,
                onClearKey = viewModel::clearAgentKey,
                onDismiss = { viewModel.setShowAgentChat(false) }
            )
        }

        if (state.showSolidModal) {
            SolidColorClipDialog(
                onDismiss = { viewModel.setShowSolidModal(false) },
                onCreate = viewModel::addSolidColorClip
            )
        }

        if (state.showPresetsModal) {
            PresetPacksHubDialog(
                state = state,
                onDismiss = { viewModel.setShowPresetsModal(false) },
                onSaveStylePreset = viewModel::saveCurrentClipAsStylePreset,
                onSaveEquipmentRig = viewModel::saveCurrentClipAsEquipmentRig,
                onLoadExpansionPack = viewModel::loadBuiltinExpansionPack,
                onHotImportJson = viewModel::hotImportPresetBundleJson,
                onApplyMotion = viewModel::applyMotionPreset,
                onApplyEquipmentRig = viewModel::applyEquipmentRig,
                onApplyAudioGear = viewModel::applyAudioGearPreset,
                onExportBundleClick = { exportPresetBundleLauncher.launch("EditPro_Universal_Equipment_Bundle.json") }
            )
        }

        if (state.alertTitle != null && state.alertMessage != null) {
            AlertSheetDialog(
                title = state.alertTitle,
                message = state.alertMessage,
                onDismiss = viewModel::dismissAlert
            )
        }

        state.pendingConfirm?.let { action ->
            ConfirmDialog(
                title = when (action) {
                    PendingConfirm.NEW_PROJECT -> "START A NEW PROJECT?"
                    PendingConfirm.OPEN_PROJECT -> "OPEN ANOTHER PROJECT?"
                    PendingConfirm.DEMO_REEL -> "LOAD THE DEMO REEL?"
                },
                message = "Your current timeline will be replaced. Imported media stays in the library, " +
                    "and an autosave of the current edit is kept, so nothing is silently lost.",
                confirmLabel = when (action) {
                    PendingConfirm.NEW_PROJECT -> "Start new"
                    PendingConfirm.OPEN_PROJECT -> "Open project"
                    PendingConfirm.DEMO_REEL -> "Load demo"
                },
                onConfirm = {
                    viewModel.clearConfirm()
                    when (action) {
                        PendingConfirm.NEW_PROJECT -> viewModel.startNewProject()
                        PendingConfirm.OPEN_PROJECT -> openProjectLauncher.launch(arrayOf("*/*"))
                        PendingConfirm.DEMO_REEL -> viewModel.loadProDemoReel()
                    }
                },
                onDismiss = viewModel::clearConfirm
            )
        }

        // Floating Bottom Toast Pill
        state.toastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0xEB3A3A3E))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(text = msg, color = Color.White, fontSize = 13.sp)
            }
        }

        // Landing screen overlay — drawn last so it sits above the workspace.
        if (state.showHome) {
            ProjectHomeScreen(
                state = state,
                onNewProject = {
                    if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.NEW_PROJECT)
                    else viewModel.startNewProject()
                },
                onOpenProject = {
                    if (viewModel.hasWorkToDiscard()) viewModel.requestConfirm(PendingConfirm.OPEN_PROJECT)
                    else openProjectLauncher.launch(arrayOf("*/*"))
                },
                onContinue = { viewModel.setShowHome(false) },
                onRemoveRecent = viewModel::removeRecentProject
            )
        }
    }
}

@Composable
private fun TopHeaderBar(
    state: EditorUiState,
    onProjectNameChange: (String) -> Unit,
    onProjectNameFocusChange: (Boolean) -> Unit,
    onSelectMode: (WorkspaceMode) -> Unit,
    onNewProject: () -> Unit,
    onLoadDemoReel: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenTools: () -> Unit,
    onSaveProject: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenExport: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(palette.panel.copy(alpha = 0.88f))
            .border(0.5.dp, palette.line)
            .padding(horizontal = 14.dp)
    ) {
        AppWordmark(title = "Edit Pro")

        BasicTextField(
            value = state.projectName,
            onValueChange = onProjectNameChange,
            singleLine = true,
            textStyle = TextStyle(
                color = palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier
                .onFocusChanged { onProjectNameFocusChange(it.isFocused) }
                .width(170.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(palette.controlBg)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )

        // Unified Workspace Mode Switcher Pill Bar (Normal | Colour | Motion | Music)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = if (palette.isLight) 0.08f else 0.28f))
                .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
                .padding(3.dp)
        ) {
            WorkspaceMode.entries.forEach { mode ->
                val active = state.workspaceMode == mode
                val iconType = when (mode) {
                    WorkspaceMode.EDIT -> SmileIconType.MODE_EDIT
                    WorkspaceMode.COLOR -> SmileIconType.MODE_COLOR
                    WorkspaceMode.MOTION -> SmileIconType.MODE_MOTION
                    WorkspaceMode.MUSIC -> SmileIconType.MODE_MUSIC
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    com.editpro.studio.ui.SmileVectorIcon(
                        type = iconType,
                        tint = if (active) palette.text else palette.muted
                    )
                    Text(
                        text = mode.label,
                        color = if (active) palette.text else palette.muted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = mode.shortcutHint,
                        color = palette.muted.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        SmileIconButton(icon = SmileIconType.DEMO, accessibleLabel = "Demo Reel", onClick = onLoadDemoReel)
        SmileIconButton(icon = SmileIconType.NEW_PROJ, accessibleLabel = "New", onClick = onNewProject)
        SmileIconButton(icon = SmileIconType.PRESETS, accessibleLabel = "Presets", onClick = onOpenPresets)
        SmileIconButton(icon = SmileIconType.TOOLS, accessibleLabel = "Tools", onClick = onOpenTools)
        SmileIconButton(icon = SmileIconType.SAVE_PROJ, accessibleLabel = "Save", onClick = onSaveProject)
        SmileIconButton(icon = SmileIconType.OPEN_PROJ, accessibleLabel = "Open", onClick = onOpenProject)
        SmileIconButton(icon = SmileIconType.EXPORT_VID, accessibleLabel = "Export", isPrimary = true, onClick = onOpenExport)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaLibraryPanel(
    state: EditorUiState,
    onImportClick: () -> Unit,
    onDemoClick: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onFilterChange: (String) -> Unit,
    onSelectAsset: (String) -> Unit,
    onAddAssetToTimeline: (MediaAsset) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAssetDragStart: (MediaAsset, Offset) -> Unit,
    onAssetDragMove: (Offset) -> Unit,
    onAssetDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val filteredAssets = state.assets.filter { a ->
        val matchesKind = state.mediaFilter == "all" ||
            a.kind.id == state.mediaFilter ||
            (state.mediaFilter == "image" && a.kind == AssetKind.COLOR)
        val matchesBin = state.activeBin == "all" ||
            (state.activeBin == "Favorites" && a.favorite) ||
            a.bin == state.activeBin
        val matchesQuery = state.assetSearchQuery.isBlank() ||
            a.name.contains(state.assetSearchQuery.trim(), ignoreCase = true)
        matchesKind && matchesBin && matchesQuery
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(palette.panel)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "MEDIA LIBRARY",
                color = palette.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp
            )
            SmileIconButton(
                icon = SmileIconType.IMPORT_MEDIA,
                accessibleLabel = "＋ Import",
                isPrimary = true,
                onClick = onImportClick
            )
        }

        // Search input
        BasicTextField(
            value = state.assetSearchQuery,
            onValueChange = onSearchChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.text, fontSize = 12.sp),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.onFocusChanged { onSearchFocusChange(it.isFocused) },
            decorationBox = { inner ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.controlBg)
                        .padding(horizontal = 10.dp)
                ) {
                    if (state.assetSearchQuery.isEmpty()) {
                        Text("Search media…", color = palette.muted, fontSize = 12.sp)
                    }
                    inner()
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Filter Tabs (All, Video, Audio, Images)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.controlBg)
                .padding(2.dp)
        ) {
            listOf("all" to "All", "video" to "Video", "audio" to "Audio", "image" to "Images").forEach { (key, label) ->
                val active = state.mediaFilter == key
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) Color.White.copy(alpha = 0.17f) else Color.Transparent)
                        .clickable { onFilterChange(key) }
                ) {
                    Text(
                        text = label,
                        color = if (active) palette.text else palette.muted,
                        fontSize = 11.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredAssets.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = if (state.assets.isEmpty()) "No media imported yet.\nTap ＋ Import or load the Pro Demo Reel."
                    else "No media matches this filter.",
                    color = palette.muted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                if (state.assets.isEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    com.editpro.studio.ui.InspectorActionButton(
                        text = "▶ Load Pro Demo Reel",
                        isPrimary = true,
                        onClick = onDemoClick
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filteredAssets, key = { it.id }) { asset ->
                    val selected = asset.id == state.selectedAssetId
                    var tileOrigin by remember { mutableStateOf(Offset.Zero) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { tileOrigin = it.positionInRoot() }
                            .combinedClickable(
                                onClick = { onSelectAsset(asset.id) },
                                onDoubleClick = { onAddAssetToTimeline(asset) }
                            )
                            // Long-press starts a drag onto the timeline. Double-tap still adds the
                            // clip at the end of its track, which is quicker for simple assembly.
                            .pointerInput(asset.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        onAssetDragStart(asset, tileOrigin + offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        onAssetDragMove(tileOrigin + change.position)
                                    },
                                    onDragEnd = { onAssetDragEnd() },
                                    onDragCancel = { onAssetDragEnd() }
                                )
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (asset.kind == AssetKind.COLOR) {
                                        try { Color(android.graphics.Color.parseColor(asset.colorHex ?: "#2678ff")) }
                                        catch (_: Exception) { Color(0xFF111113) }
                                    } else Color(0xFF111113)
                                )
                                .border(
                                    width = 2.dp,
                                    color = if (selected) palette.accent else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            // Real thumbnail where one can be produced: an image decodes from its
                            // local copy, a video uses the first extracted filmstrip frame.
                            val thumbPath = when (asset.kind) {
                                AssetKind.IMAGE -> asset.localPath
                                AssetKind.VIDEO -> asset.filmstripPaths.firstOrNull()
                                else -> null
                            }
                            val thumb = remember(thumbPath) { loadThumbnail(thumbPath) }
                            if (thumb != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = thumb,
                                    contentDescription = asset.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Audio badge stays readable on top of a video still.
                                if (asset.kind == AssetKind.VIDEO) {
                                    Text(
                                        text = "▶",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(Color(0x8C000000), RoundedCornerShape(99.dp))
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = asset.kind.label,
                                    color = palette.muted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = asset.name,
                                color = palette.text,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (asset.favorite) "★" else "☆",
                                color = if (asset.favorite) Color(0xFFFFD60A) else palette.muted,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { onToggleFavorite(asset.id) }
                                    .padding(horizontal = 4.dp)
                            )
                        }
                        Text(
                            text = buildString {
                                append("${asset.kind.id.uppercase()} · ${formatTimecode(asset.durationSec)} · ${formatFileSize(asset.sizeBytes)}")
                                if (asset.localPath == null) append("  ·  ⚠ not stored locally")
                            },
                            color = if (asset.localPath == null) palette.orange else palette.muted,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decodes a small thumbnail for a library tile. Sampled down heavily because these are shown at
 * roughly 120dp wide; returning null falls back to the plain kind label.
 */
private fun loadThumbnail(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return try {
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun sanitizeFileName(raw: String): String {
    val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
    return cleaned.take(60).ifEmpty { "Untitled_Project" }
}

// ---------------------------------------------------------------------------
// EXPORTED FILE DELIVERY
// Hands the rendered MP4 to the system through the app's existing FileProvider so
// the user can open it in a player or share/save it. The provider authority is
// declared in AndroidManifest.xml as "${applicationId}.fileprovider", and
// res/xml/file_paths.xml already exposes the cache "exports/" directory.
// ---------------------------------------------------------------------------

private fun exportedFileUri(context: Context, path: String): Uri? {
    val file = File(path)
    if (!file.exists()) return null
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }
}

private fun openExportedFile(context: Context, path: String) {
    val uri = exportedFileUri(context, path)
    if (uri == null) {
        Toast.makeText(context, "Exported file is no longer available.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found that can play this video.", Toast.LENGTH_LONG).show()
    }
}

private fun shareExportedFile(context: Context, path: String) {
    val uri = exportedFileUri(context, path)
    if (uri == null) {
        Toast.makeText(context, "Exported file is no longer available.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share exported video"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to share this file.", Toast.LENGTH_LONG).show()
    }
}
