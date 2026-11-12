package com.editpro.studio

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.ShortcutChords
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.WorkspaceMode
import com.editpro.studio.model.formatFileSize
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.ui.AgentAccessDialog
import com.editpro.studio.ui.AgentChatDialog
import com.editpro.studio.ui.AlertSheetDialog
import com.editpro.studio.ui.ClipMenuSettingsDialog
import com.editpro.studio.ui.CollapsedPanelRail
import com.editpro.studio.ui.ConfirmDialog
import com.editpro.studio.ui.EditProStudioTheme
import com.editpro.studio.ui.EditingToolsDialog
import com.editpro.studio.ui.EditorContextMenu
import com.editpro.studio.ui.EditorMenuDivider
import com.editpro.studio.ui.EditorMenuItem
import com.editpro.studio.ui.ExportVideoDialog
import com.editpro.studio.ui.InspectorPanel
import com.editpro.studio.ui.LocalEditProPalette
import com.editpro.studio.ui.MobilePane
import com.editpro.studio.ui.MobilePaneSwitcher
import com.editpro.studio.ui.MotionGraphicsDialog
import com.editpro.studio.ui.NewProjectDialog
import com.editpro.studio.ui.PanelToggleButton
import com.editpro.studio.ui.PointerModifierState
import com.editpro.studio.ui.PresetPacksHubDialog
import com.editpro.studio.ui.PreviewCenterPanel
import com.editpro.studio.ui.ProjectHomeScreen
import com.editpro.studio.ui.ShortcutsDialog
import com.editpro.studio.ui.SmileIconButton
import com.editpro.studio.ui.SmileIconType
import com.editpro.studio.ui.SolidColorClipDialog
import com.editpro.studio.ui.TextPromptDialog
import com.editpro.studio.ui.TimelineDropGeometry
import com.editpro.studio.ui.TimelinePanel
import com.editpro.studio.ui.chordKeyName
import com.editpro.studio.ui.onAnyPress
import com.editpro.studio.ui.onSecondaryClick
import com.editpro.studio.ui.parseHexColor
import com.editpro.studio.ui.rememberIsCompact
import com.editpro.studio.ui.rememberThumbnail
import com.editpro.studio.ui.trackPointerModifiers
import com.editpro.studio.viewmodel.EditProViewModel
import com.editpro.studio.viewmodel.EditorUiState
import com.editpro.studio.viewmodel.PendingConfirm
import com.editpro.studio.viewmodel.WorkspacePanel
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

    override fun onStop() {
        super.onStop()
        // Leaving the app is when work is most likely to be lost, so it is written now rather
        // than left to the debounced autosave.
        runCatching { viewModel.saveProjectQuietly() }
    }
}

/** Keys that keep acting while held down. Everything else fires once per press. */
private val REPEATING_SHORTCUTS = setOf(
    EditorShortcut.STEP_BACK, EditorShortcut.STEP_FORWARD, EditorShortcut.JUMP_BACK, EditorShortcut.JUMP_FORWARD,
    EditorShortcut.NUDGE_LEFT, EditorShortcut.NUDGE_RIGHT, EditorShortcut.NUDGE_LEFT_BIG, EditorShortcut.NUDGE_RIGHT_BIG,
    EditorShortcut.ZOOM_IN, EditorShortcut.ZOOM_OUT, EditorShortcut.UNDO, EditorShortcut.REDO,
    EditorShortcut.PREV_EDIT, EditorShortcut.NEXT_EDIT, EditorShortcut.PREV_KEYFRAME, EditorShortcut.NEXT_KEYFRAME,
    EditorShortcut.SELECT_PREV, EditorShortcut.SELECT_NEXT
)

@Composable
fun EditProWorkspaceScreen(
    viewModel: EditProViewModel,
    state: EditorUiState
) {
    val palette = LocalEditProPalette.current
    val context = LocalContext.current
    val hostView = LocalView.current

    // Text fields take priority over the editor shortcuts. While one of them has focus the
    // global key handler below stays out of the way, so typing "s", space, Delete, "t" and
    // so on is not hijacked by Split / Play / Delete-clip / Add-text.
    var projectNameFocused by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }

    val hostActivity = context as? Activity
    var immersive by remember { mutableStateOf(false) }

    // Asset drag-and-drop: the dragged asset, the pointer in root coordinates, and the timeline's
    // layout (reported by TimelinePanel) so a drop can be turned into a track and a time.
    var dragAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var timelineGeometry by remember { mutableStateOf<TimelineDropGeometry?>(null) }
    var showAgentAccess by remember { mutableStateOf(false) }

    // Ctrl / Shift at the moment of a click, for multi-selection in the timeline and library.
    val pointerModifiers = remember { PointerModifierState() }

    // Phone vs tablet. Drives the workspace (three panes or one) and the header (scrollable).
    val isCompact = rememberIsCompact()
    var mobilePane by remember { mutableStateOf(MobilePane.Preview) }

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
                viewModel.showToast("Project file exported to ${uri.lastPathSegment ?: "file"}")
            } else {
                viewModel.showAlert(
                    "EXPORT FAILED",
                    "The project file was not written. ${failure.message ?: "Unknown error."}"
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

    val requestImportProjectFile: () -> Unit = { openProjectLauncher.launch(arrayOf("*/*")) }
    val requestExportProjectFile: () -> Unit = {
        saveProjectLauncher.launch("${sanitizeFileName(state.projectName)}.filmcraft")
    }
    val requestImportMedia: () -> Unit = {
        mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "image/*"))
    }

    // Turns a drop point into a track and a time. A drop outside the timeline adds nothing: a
    // drag that is let go over the library or the preview was abandoned, not aimed.
    fun dropAsset(asset: MediaAsset) {
        val geom = timelineGeometry
        val pos = dragPosition
        if (geom == null || pos.y < geom.panelTopY) {
            viewModel.showToast("Drop media on a timeline track to add it")
            return
        }
        val track = TimelineOps.dropTrack(asset, geom.trackAt(pos.y))
        val at = if (geom.pxPerSecond > 0f) {
            ((pos.x - geom.contentStartX) / geom.pxPerSecond).toDouble().coerceAtLeast(0.0)
        } else 0.0
        viewModel.addAssetToTimeline(asset, track, at)
    }

    // Immersive full screen, reached from the Tools panel or its shortcut.
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

    // Every keyboard action goes through the user's key map. The handful the view model cannot
    // perform itself (the system file pickers, full screen) are finished here.
    val runShortcut: (EditorShortcut) -> Unit = { action ->
        if (!viewModel.performShortcut(action)) {
            when (action) {
                EditorShortcut.FULLSCREEN -> toggleFullscreen()
                EditorShortcut.OPEN_PROJECT_FILE -> requestImportProjectFile()
                EditorShortcut.EXPORT_PROJECT_FILE -> requestExportProjectFile()
                EditorShortcut.IMPORT_MEDIA -> requestImportMedia()
                else -> Unit
            }
        }
    }

    // Compose only delivers hardware key events to a focused node. The workspace root takes
    // focus itself and reclaims it whenever a dialog closes.
    val rootFocus = remember { FocusRequester() }
    val anyDialogOpen = state.showExportModal || state.showToolsModal || state.showSolidModal ||
        state.showPresetsModal || state.showShortcutsModal || state.showClipMenuModal || showAgentAccess ||
        state.showAgentChat || state.showGraphicsModal || state.showNewProjectModal ||
        state.pendingConfirm != null || state.alertTitle != null
    LaunchedEffect(state.showHome, anyDialogOpen) {
        if (!state.showHome && !anyDialogOpen) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Touching the timeline or the preview hands the keyboard back to the editor, so shortcuts
    // work again after typing in a text field without having to tab out of it first.
    val reclaimFocus: () -> Unit = { runCatching { rootFocus.requestFocus() } }

    // Back leaves the editor for the project list instead of closing the app.
    BackHandler(enabled = !state.showHome) {
        if (state.previewMaximized) viewModel.toggleMaximizePreview() else viewModel.goHome()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(palette.bg)
            .trackPointerModifiers(pointerModifiers)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Never swallow keys that belong to a text field, and stay idle on the home screen.
                if (state.showHome || projectNameFocused || searchFocused || hostView.onCheckIsTextEditor()) {
                    return@onPreviewKeyEvent false
                }
                val keyName = chordKeyName(ev.key.nativeKeyCode) ?: return@onPreviewKeyEvent false
                val chord = ShortcutChords.of(
                    key = keyName,
                    ctrl = ev.isCtrlPressed || ev.isMetaPressed,
                    alt = ev.isAltPressed,
                    shift = ev.isShiftPressed
                )
                val action = state.bindings.resolve(chord) ?: return@onPreviewKeyEvent false
                if (ev.nativeKeyEvent.repeatCount > 0 && action !in REPEATING_SHORTCUTS) {
                    return@onPreviewKeyEvent true
                }
                runShortcut(action)
                true
            }
    ) {
        // The editor is only built while a project is on screen. Behind the project list it
        // would still take taps that fall through the list's empty space, and keep rendering
        // preview frames nobody can see.
        if (!state.showHome) Column(modifier = Modifier.fillMaxSize()) {
            TopHeaderBar(
                state = state,
                onProjectNameChange = viewModel::setProjectName,
                onProjectNameFocusChange = { projectNameFocused = it },
                onSelectMode = viewModel::setWorkspaceMode,
                onHome = viewModel::goHome,
                onOpenGraphics = { viewModel.setShowGraphicsModal(true) },
                onOpenPresets = { viewModel.setShowPresetsModal(true) },
                onOpenTools = { viewModel.setShowToolsModal(true) },
                onSaveProject = viewModel::saveProjectNow,
                onOpenExport = { viewModel.setShowExportModal(true) },
                compact = isCompact
            )

            // Workspace: three panes side by side on a tablet, one at a time on a phone. Either
            // side panel can be collapsed to a rail, and the preview can take the whole window.
            val maximized = state.previewMaximized
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isCompact && !maximized) {
                    MobilePaneSwitcher(active = mobilePane, onSelect = { mobilePane = it })
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val showLibrary = if (isCompact) mobilePane == MobilePane.Library && !maximized
                    else !maximized && !state.libraryCollapsed
                    val showInspector = if (isCompact) mobilePane == MobilePane.Inspector && !maximized
                    else !maximized && !state.inspectorCollapsed
                    val showPreview = !isCompact || maximized || mobilePane == MobilePane.Preview

                    if (!isCompact && !maximized && state.libraryCollapsed) {
                        CollapsedPanelRail(title = "MEDIA", expandGlyph = "›") {
                            viewModel.togglePanel(WorkspacePanel.LIBRARY)
                        }
                    }
                    if (showLibrary) {
                        MediaLibraryPanel(
                            state = state,
                            viewModel = viewModel,
                            pointerModifiers = pointerModifiers,
                            onImportClick = requestImportMedia,
                            onSearchFocusChange = { searchFocused = it },
                            onAssetDragStart = { asset, pos ->
                                dragAsset = asset
                                dragPosition = pos
                            },
                            onAssetDragMove = { pos -> dragPosition = pos },
                            onAssetDragEnd = { drop ->
                                val dropped = dragAsset
                                dragAsset = null
                                if (dropped != null && drop) dropAsset(dropped)
                            },
                            showCollapse = !isCompact,
                            modifier = if (isCompact) Modifier.fillMaxSize() else Modifier.width(state.leftPanelWidthDp.dp)
                        )
                        // Left splitter (tablet only - a phone has one pane, nothing to split)
                        if (!isCompact) {
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
                        }
                    }

                    if (showPreview) {
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
                            onStepFrames = viewModel::stepFrames,
                            onToggleMaximize = viewModel::toggleMaximizePreview,
                            modifier = Modifier.weight(1f).onAnyPress(reclaimFocus)
                        )
                    }

                    if (showInspector) {
                        if (!isCompact) {
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
                        }
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
                            onSeek = viewModel::seekTo,
                            onSetKeyframeEasing = viewModel::setKeyframeEasing,
                            onJumpKeyframe = viewModel::jumpToKeyframe,
                            onRemoveKeyframeAtPlayhead = viewModel::removeKeyframeAtPlayhead,
                            onToggleKeyframeLane = viewModel::toggleKeyframeLane,
                            onOpenGraphics = { viewModel.setShowGraphicsModal(true) },
                            onCollapse = if (isCompact) null else ({ viewModel.togglePanel(WorkspacePanel.INSPECTOR) }),
                            modifier = if (isCompact) Modifier.fillMaxSize() else Modifier.width(state.rightPanelWidthDp.dp)
                        )
                    }
                    if (!isCompact && !maximized && state.inspectorCollapsed) {
                        CollapsedPanelRail(title = "INSPECTOR", expandGlyph = "‹") {
                            viewModel.togglePanel(WorkspacePanel.INSPECTOR)
                        }
                    }
                }
            }

            if (!maximized) {
                // Horizontal splitter above the timeline; nothing to resize while it is collapsed.
                if (!state.timelineCollapsed) {
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
                }

                TimelinePanel(
                    state = state,
                    viewModel = viewModel,
                    pointerModifiers = pointerModifiers,
                    onRun = runShortcut,
                    onGeometry = { timelineGeometry = it },
                    modifier = (if (state.timelineCollapsed) Modifier else Modifier.height(state.timelineHeightDp.dp))
                        .onAnyPress(reclaimFocus)
                )
            }
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
                onRun = runShortcut,
                onOpenSolidModal = { viewModel.setShowSolidModal(true) },
                onImportSrtClick = { importSrtLauncher.launch(arrayOf("*/*")) },
                onMoveToPlayhead = viewModel::moveSelectedClipToPlayhead,
                onToggleSafeGuides = viewModel::toggleSafeGuides,
                onToggleChecker = viewModel::toggleCheckerboard,
                onHealthScan = viewModel::runProjectHealthScan,
                onClearTimeline = viewModel::clearTimeline,
                onClearMarkers = viewModel::clearMarkers,
                onCollapseEmptyTracks = viewModel::collapseEmptyTracks,
                onSelectUnusedMedia = viewModel::selectUnusedAssets,
                onToggleAutoFit = viewModel::toggleAutoFitTimeline,
                onToggleCompact = viewModel::toggleCompactTimeline,
                onOpenClipMenu = { viewModel.setShowClipMenuModal(true) },
                onOpenAgentAccess = { showAgentAccess = true }
            )
        }

        if (state.showGraphicsModal) {
            MotionGraphicsDialog(
                onPick = viewModel::addMotionGraphic,
                onDismiss = { viewModel.setShowGraphicsModal(false) }
            )
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

        // The project list, shown instead of the workspace and below the dialogs it can raise.
        if (state.showHome) {
            ProjectHomeScreen(
                state = state,
                onNewProject = { viewModel.setShowNewProjectModal(true) },
                onOpenProject = viewModel::openProject,
                onImportProjectFile = requestImportProjectFile,
                onDemoReel = viewModel::loadProDemoReel,
                onRenameProject = viewModel::renameProject,
                onDuplicateProject = viewModel::duplicateProject,
                onDeleteProject = viewModel::requestDeleteProject,
                onOpenShortcuts = { viewModel.setShowShortcutsModal(true) }
            )
        }

        if (state.showShortcutsModal) {
            ShortcutsDialog(
                bindings = state.bindings,
                onRebind = viewModel::rebindShortcut,
                onResetOne = viewModel::resetShortcut,
                onResetAll = viewModel::requestResetShortcuts,
                onDismiss = { viewModel.setShowShortcutsModal(false) }
            )
        }

        if (state.showNewProjectModal) {
            NewProjectDialog(
                onCreate = { name, settings -> viewModel.createProject(name, settings) },
                onDismiss = { viewModel.setShowNewProjectModal(false) }
            )
        }

        if (state.alertTitle != null && state.alertMessage != null) {
            AlertSheetDialog(
                title = state.alertTitle,
                message = state.alertMessage,
                onDismiss = viewModel::dismissAlert
            )
        }

        state.pendingConfirm?.let { pending ->
            val title: String
            val message: String
            val confirmLabel: String
            when (pending) {
                is PendingConfirm.DeleteProject -> {
                    title = "DELETE THIS PROJECT?"
                    message = "\"${pending.name}\" and the media imported into it will be removed from this " +
                        "device. Videos you have already exported are kept. This cannot be undone."
                    confirmLabel = "Delete project"
                }
                is PendingConfirm.DeleteAssets -> {
                    val n = pending.ids.size
                    val it = if (n == 1) "it" else "them"
                    title = if (n == 1) "REMOVE THIS MEDIA?" else "REMOVE $n MEDIA FILES?"
                    message = when (pending.clipCount) {
                        0 -> "Nothing on the timeline uses $it."
                        1 -> "1 clip on the timeline uses $it and will be removed too."
                        else -> "${pending.clipCount} clips on the timeline use $it and will be removed too."
                    } + " Undo brings everything back."
                    confirmLabel = "Remove"
                }
                PendingConfirm.ResetShortcuts -> {
                    title = "RESET ALL SHORTCUTS?"
                    message = "Every action goes back to its default key and your own key choices are discarded."
                    confirmLabel = "Reset shortcuts"
                }
            }
            ConfirmDialog(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                onConfirm = viewModel::confirmPending,
                onDismiss = viewModel::clearConfirm
            )
        }

        // Floating Bottom Toast Pill
        state.toastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0xEB3A3A3E))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(text = msg, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TopHeaderBar(
    state: EditorUiState,
    onProjectNameChange: (String) -> Unit,
    onProjectNameFocusChange: (Boolean) -> Unit,
    onSelectMode: (WorkspaceMode) -> Unit,
    onHome: () -> Unit,
    onOpenGraphics: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenTools: () -> Unit,
    onSaveProject: () -> Unit,
    onOpenExport: () -> Unit,
    compact: Boolean = false
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
            // On a phone the header is wider than the screen, so it scrolls rather than clipping
            // half the controls off the edge.
            .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = if (compact) 8.dp else 14.dp)
    ) {
        SmileIconButton(
            icon = SmileIconType.OPEN_PROJ,
            accessibleLabel = "All projects",
            label = "Projects",
            onClick = onHome
        )

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
                .width(if (compact) 132.dp else 170.dp)
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
                val shortcut = when (mode) {
                    WorkspaceMode.EDIT -> EditorShortcut.MODE_EDIT
                    WorkspaceMode.COLOR -> EditorShortcut.MODE_COLOR
                    WorkspaceMode.MOTION -> EditorShortcut.MODE_MOTION
                    WorkspaceMode.MUSIC -> EditorShortcut.MODE_MUSIC
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
                    // The key shown is the one currently bound, not a fixed label.
                    state.bindings.hint(shortcut)?.let { hint ->
                        Text(
                            text = hint,
                            color = palette.muted.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        if (!compact) {
            Text(
                text = state.saveStatus,
                color = palette.muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }

        SmileIconButton(icon = SmileIconType.MODE_MOTION, accessibleLabel = "Motion graphics", label = "Graphics", onClick = onOpenGraphics)
        SmileIconButton(icon = SmileIconType.PRESETS, accessibleLabel = "Presets", label = "Presets", onClick = onOpenPresets)
        SmileIconButton(icon = SmileIconType.TOOLS, accessibleLabel = "Tools", label = "Tools", onClick = onOpenTools)
        SmileIconButton(icon = SmileIconType.SAVE_PROJ, accessibleLabel = "Save project", label = "Save", onClick = onSaveProject)
        SmileIconButton(icon = SmileIconType.EXPORT_VID, accessibleLabel = "Export video", label = "Export", isPrimary = true, onClick = onOpenExport)
    }
}

/**
 * The media library. A tap selects; Ctrl-tap adds to the selection and Shift-tap selects a range,
 * and the Select button gives touch screens the same thing. Double-tap adds to the timeline, a
 * long-press drags onto a track, and a right-click (or a long-press that is let go without
 * moving) opens the menu for the item — or for the whole selection when it is part of one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaLibraryPanel(
    state: EditorUiState,
    viewModel: EditProViewModel,
    pointerModifiers: PointerModifierState,
    onImportClick: () -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onAssetDragStart: (MediaAsset, Offset) -> Unit,
    onAssetDragMove: (Offset) -> Unit,
    onAssetDragEnd: (Boolean) -> Unit,
    showCollapse: Boolean,
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
    val visibleOrder = filteredAssets.map { it.id }
    val usedAssetIds = remember(state.clips) { state.clips.mapNotNull { it.assetId }.toSet() }
    var selectMode by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MediaAsset?>(null) }
    val multi = state.selectedAssetIds.size > 1

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(palette.panel)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = "MEDIA LIBRARY",
                color = palette.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            PanelToggleButton(
                glyph = if (selectMode) "☑" else "☐",
                description = if (selectMode) "Stop selecting several items" else "Select several items",
                onClick = {
                    selectMode = !selectMode
                    if (!selectMode) viewModel.clearAssetSelection()
                }
            )
            SmileIconButton(
                icon = SmileIconType.IMPORT_MEDIA,
                accessibleLabel = "Import media",
                isPrimary = true,
                onClick = onImportClick
            )
            if (showCollapse) {
                PanelToggleButton(glyph = "‹", description = "Hide media library", onClick = {
                    viewModel.togglePanel(WorkspacePanel.LIBRARY)
                })
            }
        }

        // Search input
        BasicTextField(
            value = state.assetSearchQuery,
            onValueChange = viewModel::setAssetSearchQuery,
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
                        .clickable { viewModel.setMediaFilter(key) }
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

        // Bulk actions, shown while several items are selected (or selecting is switched on).
        if (multi || selectMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            ) {
                Text(
                    text = "${state.selectedAssetIds.size} selected",
                    color = palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LibraryBulkButton("Add") { viewModel.addAssetsToTimeline() }
                LibraryBulkButton("All") { viewModel.selectAllAssets(visibleOrder) }
                LibraryBulkButton("Unused") { viewModel.selectUnusedAssets() }
                LibraryBulkButton("Remove", danger = true) { viewModel.requestDeleteAssets() }
                LibraryBulkButton("Done") {
                    selectMode = false
                    viewModel.clearAssetSelection()
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
                    text = if (state.assets.isEmpty()) "No media in this project yet.\nTap Import to add video, audio or images."
                    else "No media matches this filter.",
                    color = palette.muted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                if (state.assets.isEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    com.editpro.studio.ui.InspectorActionButton(
                        text = "＋ Import media",
                        isPrimary = true,
                        onClick = onImportClick
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
                    val selected = asset.id in state.selectedAssetIds || asset.id == state.selectedAssetId
                    val checked = asset.id in state.selectedAssetIds && (multi || selectMode)
                    var tileOrigin by remember { mutableStateOf(Offset.Zero) }
                    var menuAt by remember { mutableStateOf<Offset?>(null) }
                    // The gesture blocks below outlive recompositions, so they read the current
                    // asset and mode through these instead of a captured copy.
                    val currentAsset by rememberUpdatedState(asset)
                    val currentOrder by rememberUpdatedState(visibleOrder)
                    val currentSelectMode by rememberUpdatedState(selectMode)
                    val targetCount = if (asset.id in state.selectedAssetIds) state.selectedAssetIds.size else 1

                    Box {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { tileOrigin = it.positionInRoot() }
                                .combinedClickable(
                                    onClick = {
                                        viewModel.selectAsset(
                                            assetId = currentAsset.id,
                                            additive = pointerModifiers.ctrl || currentSelectMode,
                                            range = pointerModifiers.shift,
                                            visibleOrder = currentOrder
                                        )
                                    },
                                    onDoubleClick = { viewModel.addAssetToTimeline(currentAsset) }
                                )
                                .onSecondaryClick(asset.id) { pos ->
                                    if (currentAsset.id !in viewModel.uiState.value.selectedAssetIds) {
                                        viewModel.selectAsset(currentAsset.id)
                                    }
                                    menuAt = pos
                                }
                                // Long-press starts a drag onto the timeline. Letting go without
                                // moving opens the menu instead, which is how touch reaches it.
                                .pointerInput(asset.id) {
                                    var moved = false
                                    var pressAt = Offset.Zero
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            moved = false
                                            pressAt = offset
                                            onAssetDragStart(currentAsset, tileOrigin + offset)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            if ((change.position - pressAt).getDistance() > viewConfiguration.touchSlop) {
                                                moved = true
                                            }
                                            onAssetDragMove(tileOrigin + change.position)
                                        },
                                        onDragEnd = {
                                            onAssetDragEnd(moved)
                                            if (!moved) {
                                                if (currentAsset.id !in viewModel.uiState.value.selectedAssetIds) {
                                                    viewModel.selectAsset(currentAsset.id)
                                                }
                                                menuAt = pressAt
                                            }
                                        },
                                        onDragCancel = { onAssetDragEnd(false) }
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
                                        if (asset.kind == AssetKind.COLOR) parseHexColor(asset.colorHex, Color(0xFF111113))
                                        else Color(0xFF111113)
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
                                val thumb = rememberThumbnail(thumbPath)
                                if (thumb != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = thumb,
                                        contentDescription = asset.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Play badge stays readable on top of a video still.
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
                                if (checked) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(5.dp)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(palette.accent)
                                    ) {
                                        Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (asset.id in usedAssetIds) {
                                    Text(
                                        text = "IN USE",
                                        color = Color.White,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(5.dp)
                                            .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
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
                                        .clickable { viewModel.toggleAssetFavorite(asset.id) }
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

                        EditorContextMenu(position = menuAt, onDismiss = { menuAt = null }) {
                            EditorMenuItem(if (targetCount > 1) "Add $targetCount to timeline" else "Add to timeline") {
                                menuAt = null
                                viewModel.addAssetsToTimeline(asset.id)
                            }
                            if (targetCount == 1) {
                                EditorMenuItem("Add at playhead") {
                                    menuAt = null
                                    viewModel.addAssetToTimeline(asset, null, state.playheadSec)
                                }
                                EditorMenuItem(
                                    label = "Replace selected clip's media",
                                    enabled = state.selectedClip?.assetId != null
                                ) {
                                    menuAt = null
                                    viewModel.replaceSelectedClipMedia(asset.id)
                                }
                                EditorMenuItem("Rename…") {
                                    menuAt = null
                                    renaming = asset
                                }
                                EditorMenuItem(if (asset.favorite) "Remove from favourites" else "Add to favourites") {
                                    menuAt = null
                                    viewModel.toggleAssetFavorite(asset.id)
                                }
                            }
                            EditorMenuDivider()
                            EditorMenuItem("Select all") {
                                menuAt = null
                                viewModel.selectAllAssets(visibleOrder)
                            }
                            EditorMenuItem("Select unused media") {
                                menuAt = null
                                viewModel.selectUnusedAssets()
                            }
                            EditorMenuDivider()
                            EditorMenuItem(
                                label = if (targetCount > 1) "Remove $targetCount from project…" else "Remove from project…",
                                destructive = true
                            ) {
                                menuAt = null
                                viewModel.requestDeleteAssets(asset.id)
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { asset ->
        TextPromptDialog(
            title = "Rename media",
            initial = asset.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                viewModel.renameAsset(asset.id, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun LibraryBulkButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Text(
        text = label,
        color = if (danger) palette.red else palette.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(palette.controlBg)
            .border(0.5.dp, palette.line, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
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
