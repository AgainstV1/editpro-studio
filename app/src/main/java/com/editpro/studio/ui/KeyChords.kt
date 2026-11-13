package com.editpro.studio.ui

import android.view.KeyEvent

/**
 * The chord key name ("S", "Left", "Comma", "F11") for an Android key code, or null for keys that
 * cannot be part of a shortcut on their own (Ctrl, Shift, Alt, media keys…).
 *
 * The names are the ones `ShortcutChords` uses, so a pressed key can be looked up in the key map
 * and shown in the shortcuts sheet without any further translation.
 */
fun chordKeyName(keyCode: Int): String? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)).toString()
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Escape"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    KeyEvent.KEYCODE_DPAD_UP -> "Up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_COMMA -> "Comma"
    KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> "Period"
    KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "Slash"
    KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
    KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
    KeyEvent.KEYCODE_APOSTROPHE -> "Apostrophe"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "LeftBracket"
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "RightBracket"
    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "Minus"
    KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "Equals"
    KeyEvent.KEYCODE_GRAVE -> "Grave"
    else -> null
}
