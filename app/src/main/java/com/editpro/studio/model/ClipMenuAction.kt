package com.editpro.studio.model

/**
 * One entry in the clip context menu.
 *
 * The menu is user-configurable: the app stores the ordered list of enabled ids (see
 * `EditorUiState.clipMenuOrder`) and builds the menu from it, so nothing is hardcoded in the UI.
 * [group] is only used to draw separator lines between related blocks of actions.
 */
enum class ClipMenuAction(
    val id: String,
    val label: String,
    /** The keyboard action that does the same thing, so the menu shows the key currently bound. */
    val shortcutAction: EditorShortcut? = null,
    val destructive: Boolean = false,
    val group: Int = 0
) {
    CUT("cut", "Cut", EditorShortcut.CUT, group = 0),
    COPY("copy", "Copy", EditorShortcut.COPY, group = 0),
    PASTE("paste", "Paste at playhead", EditorShortcut.PASTE, group = 0),
    DUPLICATE("duplicate", "Duplicate", EditorShortcut.DUPLICATE, group = 0),

    SPLIT("split", "Split at playhead", EditorShortcut.SPLIT, group = 1),
    TRIM_START("trim_start", "Trim start to playhead", EditorShortcut.TRIM_START, group = 1),
    TRIM_END("trim_end", "Trim end to playhead", EditorShortcut.TRIM_END, group = 1),
    DETACH_AUDIO("detach_audio", "Detach audio", EditorShortcut.DETACH_AUDIO, group = 1),
    REPLACE_MEDIA("replace_media", "Replace with selected library media", null, group = 1),

    KEYFRAME("keyframe", "Add keyframe at playhead", EditorShortcut.ADD_KEYFRAME, group = 2),
    COPY_PROPS("copy_props", "Copy clip properties", EditorShortcut.COPY_ATTRS, group = 2),
    PASTE_PROPS("paste_props", "Paste clip properties", EditorShortcut.PASTE_ATTRS, group = 2),
    RESET_PROPS("reset_props", "Reset effects & transform", null, group = 2),

    RIPPLE_DELETE("ripple_delete", "Ripple delete", EditorShortcut.RIPPLE_DELETE, destructive = true, group = 3),
    DELETE("delete", "Delete", EditorShortcut.DELETE, destructive = true, group = 3),

    DETECT_BEATS("detect_beats", "Detect audio beats to markers", null, group = 4),
    PRESET_HUB("preset_hub", "Preset & equipment hub", EditorShortcut.PRESETS, group = 4),
    GRADE_SPLIT("grade_split", "Toggle grade split wipe", EditorShortcut.GRADE_SPLIT, group = 4),

    MODE_EDIT("mode_edit", "Switch to Normal mode", EditorShortcut.MODE_EDIT, group = 5),
    MODE_COLOR("mode_color", "Switch to Colour mode", EditorShortcut.MODE_COLOR, group = 5),
    MODE_MOTION("mode_motion", "Switch to Motion mode", EditorShortcut.MODE_MOTION, group = 5),
    MODE_MUSIC("mode_music", "Switch to Music mode", EditorShortcut.MODE_MUSIC, group = 5),

    RIG_ARRI("rig_arri", "Apply ARRI Alexa + Cooke S4 rig", null, group = 6),
    RIG_RED("rig_red", "Apply RED V-Raptor anamorphic rig", null, group = 6),
    AUDIO_SSL("audio_ssl", "Apply SSL master bus audio glue", null, group = 6);

    companion object {
        fun fromId(id: String): ClipMenuAction? = entries.find { it.id == id }

        /**
         * Clip-editing actions are on by default. Mode switches and camera/audio rigs are off,
         * because they are what made the default menu feel like a wall of options.
         */
        val defaultOrder: List<String> = listOf(
            CUT.id,
            COPY.id,
            PASTE.id,
            DUPLICATE.id,
            SPLIT.id,
            TRIM_START.id,
            TRIM_END.id,
            DETACH_AUDIO.id,
            KEYFRAME.id,
            COPY_PROPS.id,
            PASTE_PROPS.id,
            RESET_PROPS.id,
            RIPPLE_DELETE.id,
            DELETE.id
        )

        /** Entries added in 3.0, offered once to people who already have a saved menu. */
        val addedInV3: List<String> = listOf(CUT.id, COPY.id, PASTE.id, TRIM_START.id, TRIM_END.id, DETACH_AUDIO.id)

        /**
         * A menu saved by an older version, with the editing actions introduced since then put at
         * the top. Entries the user removed on purpose are left alone after this one-off upgrade.
         */
        fun upgradeSavedOrder(saved: List<String>): List<String> {
            val known = saved.filter { fromId(it) != null }
            return addedInV3.filter { it !in known } + known
        }
    }
}
