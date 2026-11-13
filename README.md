# Edit Pro Studio

[![Android CI](https://github.com/AgainstV1/editpro-studio/actions/workflows/android-build.yml/badge.svg?branch=main)](https://github.com/AgainstV1/editpro-studio/actions/workflows/android-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-informational.svg)](LICENSE)

A multi-track video editor for Android tablets and phones, written in Kotlin with Jetpack Compose.
On a tablet the workspace is three panes side by side; on a phone they become tabs. Everything
happens on the device: media is decoded, composited and encoded through Android's own
`MediaCodec` / `MediaMuxer` pipeline — no web layer, no bundled FFmpeg, no third-party editing SDK.

This is an internal tool maintained for a small editing team rather than a store product. See
[Status](#status) for what that means in practice.

---

## Overview

The editor is built around four workspace modes. Switching mode changes the inspector and the
on-stage overlays rather than the whole layout, so the timeline stays where it is:

| Mode | Purpose |
| --- | --- |
| **Normal** | Assembly — trim, split, ripple edits, text, titles and motion graphics |
| **Colour** | Three-way grading with live RGB parade, waveform and histogram scopes |
| **Motion** | Keyframes with per-keyframe easing, and 3D camera rigs (tilt, swivel, anamorphic) |
| **Music** | Five-band EQ, mastering presets, volume automation and beat detection |

Around that sit a seven-track timeline (two video, one overlay, graphics, text, two audio), a media
library that imports through the Storage Access Framework, and an export dialog with resolution,
aspect, frame rate and quality controls. The app opens on a front page of projects; each project
keeps its edit and its media in a folder of its own.

## Features

**Timeline**
Seven tracks with magnetic snapping. Select several clips (Ctrl / Shift click, a marquee, or the
*Multi* toggle on touch) and move, cut, copy, paste, duplicate or ripple-delete them together.
Drag a clip between tracks, drag an edge to trim, split, ripple-trim to the playhead, close gaps,
detach a video's audio. Tracks lock, hide, mute, solo and collapse; panels collapse too. Right-click
or long-press a clip, a track header, a marker or empty space for its menu.

**Colour**
58 look presets, three-way lift / gamma / gain wheels, and live scopes (parade, waveform,
histogram) with a split-screen before-and-after wipe.

**Motion**
Keyframes for scale, position, rotation, opacity and grade, with an easing choice per keyframe, a
keyframe lane in the timeline and an editor in the inspector. Changing a value on an animated clip
keys it at the playhead. Plus 55+ motion presets and camera-rig presets modelled on real bodies and
glass (ARRI, RED, Panavision, 16 mm, Super 8).

**Motion graphics**
17 animated templates — lower third, title card, typewriter, quote, credits roll, callout,
countdown, number counter, progress bar, cinematic bars, shapes and more. Each is an ordinary clip
whose text and colours are edited in the inspector.

**Audio**
Video clips carry their own sound, which can be detached onto an audio track. Volume, fades and
volume automation apply to preview and export; the five-band EQ, compressor and pan are applied to
the export (the preview applies pan only). The export mixes mono, stereo and surround sources into
one stereo track, streaming them so a long edit keeps its sound to the end, and says so if a
clip's audio could not be read. De-essing and reverb are not processed yet — see
[docs/MAINTENANCE.md](docs/MAINTENANCE.md).

**Export**
Native H.264 video with a stereo AAC audio track, via `MediaCodec` + `MediaMuxer`. Aspect
(16:9, 9:16, 1:1, 4:3), resolution up to 1080p, 24–60 fps, CRF quality, range in / out, and
cancellable renders. Finished files are published to `Movies/EditPro` so they appear in Photos.

**Projects**
A front page of project cards: open, rename, duplicate, delete, or start a new one with a chosen
shape, size and frame rate. Projects autosave into their own folders together with their media.
A project can also be exported to, and imported from, a portable `.filmcraft` JSON file.

**Keyboard**
More than 80 actions, all rebindable from *Tools → Keyboard shortcuts*: J / K / L shuttle, frame
stepping, jump between cuts, Ctrl+C / X / V, ripple trims, keyframe navigation, panel toggles.

## Requirements

- **JDK 17** (Temurin recommended)
- **Android SDK** with platform 34 and build-tools 34
- Android **8.0 (API 26)** or newer on the device

## Getting started

```bash
git clone https://github.com/AgainstV1/editpro-studio.git
cd editpro-studio

# Point Gradle at your SDK (Android Studio writes this for you)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Alternatively, open the project
folder in Android Studio and press **Run**.

## Building without a local toolchain

Every push to `main` runs
[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml), which executes the
unit tests and builds both APKs. Download them from the **Actions** tab → the run → **Artifacts**.
Pushing a `v*` tag additionally attaches the APKs to a GitHub Release.

Release builds are signed only when a keystore is configured; without one they stay unsigned and
the build still succeeds. Add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` as repository secrets to enable signing. Every build must use the same keystore, or
an update will not install over a previously installed copy.

## Architecture

```
app/src/main/java/com/editpro/studio/
├── MainActivity.kt     Compose host, workspace layout, key handling, media library, file intents
├── viewmodel/          Single source of truth (EditorUiState) and every editing operation
├── model/              Data types and the pure logic: timeline engine and edits (TimelineOps),
│                       keyframes (KeyframeOps), key map (Shortcuts), motion-graphics timing,
│                       audio DSP and the clip mixer, project storage (ProjectStore) and
│                       serialisation
├── media/              Media import, the frame compositor shared by preview and export,
│                       the title / motion-graphics renderer, preview audio, MediaCodec export
├── agent/, mcp/        The built-in assistant and the MCP server, over one shared tool layer
└── ui/                 Compose panels: preview stage, timeline, inspector, dialogs, project list
```

State lives in one `StateFlow<EditorUiState>`. Editing operations are pure functions over that
state with snapshot-based undo and redo, which keeps the timeline engine testable without an
Android runtime — that is what the JVM test suite covers. The preview and the export draw each
frame with the same function (`NativeVideoExporter.renderCompositeFrame`), so they cannot drift
apart.

## Status

Actively used, but early. Worth knowing before relying on it:

- **Verification is compile-time.** CI runs the JVM unit tests (timeline engine and edits,
  keyframes, key map, project storage, serialisation, and the export's audio mix, which is also
  run against stand-in codecs) and builds the APKs. There are **no instrumentation tests**, so
  UI, media and file-system behaviour is verified by hand against a written checklist.
- **Video preview is frame-accurate, not real-time.** Frames come from Android's
  `MediaMetadataRetriever`, which is exact but slow, so video plays back in the preview at a
  reduced frame rate while the sound runs at full speed. The export is unaffected.
- **Some controls are not yet wired to the render output.** Rather than pretend, they are labelled
  "not rendered yet" in the inspector. The full list, and the manual test checklist, live in
  [docs/MAINTENANCE.md](docs/MAINTENANCE.md).
- **No store distribution.** Builds are distributed as APKs from CI rather than through a store.
  That is a choice, not a restriction — the project is MIT licensed.

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — what changed in each version
- [docs/ASSISTANT.md](docs/ASSISTANT.md) — the built-in editing assistant, with your own API key
- [docs/MCP.md](docs/MCP.md) — letting an AI agent read and edit the project over MCP
- [docs/TUNNEL.md](docs/TUNNEL.md) — reaching the MCP server from a cloud agent through a tunnel
- [docs/MAINTENANCE.md](docs/MAINTENANCE.md) — verification notes, manual test checklist,
  preview-versus-export behaviour, and known gaps

## Licence

MIT — see [LICENSE](LICENSE). You are free to use, modify and redistribute this software, including
commercially, provided the copyright notice and permission notice are retained.

## Built with

Kotlin 1.9.24 · Android Gradle Plugin 8.5.2 · Gradle 8.9 · Jetpack Compose (BOM 2024.06.00) ·
Material 3 · Coroutines 1.8.1 · kotlinx-serialization 1.6.3. Package `com.editpro.studio`,
minSdk 26, compile and target SDK 34.
