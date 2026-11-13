package com.editpro.studio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.editpro.studio.model.ExportSettings
import com.editpro.studio.model.ProjectSettings
import com.editpro.studio.model.ProjectSummary
import com.editpro.studio.model.formatDurationShort
import com.editpro.studio.model.formatFileSize
import com.editpro.studio.model.formatRelativeTime
import com.editpro.studio.viewmodel.EditorUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The front page: every project stored on this device, as a card.
 *
 * Each project is a folder of its own holding the edit and the media imported into it, so opening
 * one never touches another and deleting one removes exactly that project's files. A card opens
 * with a tap; its menu (the ⋯ button, a right-click or a long-press) renames, duplicates or
 * deletes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectHomeScreen(
    state: EditorUiState,
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onImportProjectFile: () -> Unit,
    onDemoReel: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenShortcuts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    val compact = rememberIsCompact()
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    val now = remember(state.projects) { System.currentTimeMillis() }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = if (compact) 14.dp else 28.dp)
    ) {
        Spacer(modifier = Modifier.height(if (compact) 14.dp else 24.dp))
        Text("Edit Pro", color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = when (state.projects.size) {
                0 -> "Start a project to begin editing."
                1 -> "1 project on this device"
                else -> "${state.projects.size} projects on this device"
            },
            color = palette.muted,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InspectorActionButton("＋ New project", isPrimary = true, modifier = Modifier.widthIn(min = 150.dp), onClick = onNewProject)
            InspectorActionButton("Import project file", modifier = Modifier.widthIn(min = 150.dp), onClick = onImportProjectFile)
            InspectorActionButton("Open the demo reel", modifier = Modifier.widthIn(min = 150.dp), onClick = onDemoReel)
            InspectorActionButton("Keyboard shortcuts", modifier = Modifier.widthIn(min = 150.dp), onClick = onOpenShortcuts)
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (state.projects.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                Text("No projects yet", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Create a project, or open the demo reel to try the editor with sample media.",
                    color = palette.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (compact) 150.dp else 220.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "PROJECTS",
                        color = palette.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.7.sp
                    )
                }
                items(state.projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        isOpen = project.id == state.currentProjectId,
                        edited = formatRelativeTime(now, project.modifiedAtMs) { dateFormat.format(Date(it)) },
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renaming = project },
                        onDuplicate = { onDuplicateProject(project.id) },
                        onDelete = { onDeleteProject(project.id) }
                    )
                }
            }
        }
    }

    renaming?.let { project ->
        TextPromptDialog(
            title = "Rename project",
            initial = project.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                onRenameProject(project.id, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectSummary,
    isOpen: Boolean,
    edited: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val thumb = rememberThumbnail(project.thumbnailPath, maxEdgePx = 480)

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.panel)
                .border(
                    width = if (isOpen) 1.5.dp else 0.5.dp,
                    color = if (isOpen) palette.accent else palette.line,
                    shape = RoundedCornerShape(12.dp)
                )
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuAt = Offset(24f, 24f) }
                )
                // A right-click opens the same menu as a long-press, where the pointer is.
                .onSecondaryClick(project.id) { menuAt = it }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF1B2A44), Color(0xFF0E1320)))
                    )
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = project.name.trim().take(1).uppercase().ifEmpty { "·" },
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = formatDurationShort(project.durationSec),
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (isOpen) {
                    Text(
                        text = "OPEN",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(palette.accent, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 4.dp)
            ) {
                Text(
                    text = project.name,
                    color = palette.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                PanelToggleButton(glyph = "⋯", description = "Project menu for ${project.name}", onClick = { menuAt = Offset(24f, 24f) })
            }
            Text(
                text = "${plural(project.clipCount, "clip")} · ${project.assetCount} media · ${project.width}×${project.height} · ${project.fps} fps",
                color = palette.muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            Text(
                text = "Edited $edited · ${formatFileSize(project.sizeBytes)}",
                color = palette.muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            )
        }

        EditorContextMenu(position = menuAt, onDismiss = { menuAt = null }) {
            EditorMenuItem("Open") { menuAt = null; onOpen() }
            EditorMenuItem("Rename…") { menuAt = null; onRename() }
            EditorMenuItem("Duplicate") { menuAt = null; onDuplicate() }
            EditorMenuDivider()
            EditorMenuItem("Delete…", destructive = true) { menuAt = null; onDelete() }
        }
    }
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** Asks for a name and a canvas (shape, size, frame rate) before creating a project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectDialog(
    onCreate: (String, ProjectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var name by remember { mutableStateOf("Untitled Project") }
    var aspect by remember { mutableStateOf("landscape") }
    var resolution by remember { mutableStateOf("1280x720") }
    var fps by remember { mutableStateOf(30) }
    val (width, height) = ExportSettings(aspect = aspect, resolution = resolution, androidLowRamGuard = false)
        .resolvedDimensions()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .heightIn(max = rememberDialogMaxHeight())
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("New project", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.controlBg)
                            .border(0.5.dp, palette.line, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp)
                    ) {
                        if (name.isEmpty()) Text("Project name", color = palette.muted, fontSize = 14.sp)
                        inner()
                    }
                }
            )

            SectionHeader("SHAPE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "landscape" to "16:9 Wide",
                    "portrait" to "9:16 Vertical",
                    "square" to "1:1 Square",
                    "classic" to "4:3 Classic"
                ).forEach { (id, label) ->
                    InspectorChip(text = label, selected = aspect == id) { aspect = id }
                }
            }
            SectionHeader("SIZE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("854x480" to "480p", "1280x720" to "720p HD", "1920x1080" to "1080p Full HD").forEach { (id, label) ->
                    InspectorChip(text = label, selected = resolution == id) { resolution = id }
                }
            }
            SectionHeader("FRAME RATE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(24, 25, 30, 60).forEach { rate ->
                    InspectorChip(text = "$rate fps", selected = fps == rate) { fps = rate }
                }
            }
            Text(
                "Canvas: $width × $height at $fps fps. This can be changed later in the export settings.",
                color = palette.muted,
                fontSize = 11.5.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                InspectorActionButton("Create project", isPrimary = true, modifier = Modifier.weight(1f)) {
                    onCreate(name, ProjectSettings(width = width, height = height, fps = fps))
                }
            }
        }
    }
}
