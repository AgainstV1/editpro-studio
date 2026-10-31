# Edit Pro Studio

[![Android CI](https://github.com/AgainstV1/editpro-studio/actions/workflows/android-build.yml/badge.svg?branch=main)](https://github.com/AgainstV1/editpro-studio/actions/workflows/android-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-informational.svg)](LICENSE)

A multi-track video editor for Android tablets, written in Kotlin with Jetpack Compose. Everything
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
| **Normal** | Assembly — trim, split, ripple delete, text and titles |
| **Colour** | Three-way grading with live RGB parade, waveform and histogram scopes |
| **Motion** | Keyframes with cubic-bezier easing, and 3D camera rigs (tilt, swivel, anamorphic) |
| **Music** | Five-band EQ, mastering presets, volume automation and beat detection |

Around that sit a six-track timeline (two video, one overlay, text, two audio), a media library
that imports through the Storage Access Framework, and an export dialog with resolution, aspect,
frame rate and quality controls.

## Features

**Timeline**
Six tracks, magnetic snapping, split at playhead, ripple delete, multi-select, per-track
lock / hide / mute / solo, markers, and a render queue.

**Colour**
58 look presets, three-way lift / gamma / gain wheels, and live scopes (parade, waveform,
histogram) with a split-screen before-and-after wipe.

**Motion**
55+ motion presets, editable cubic-bezier easing, keyframes, and camera-rig presets modelled on
real bodies and glass (ARRI, RED, Panavision, 16 mm, Super 8).

**Audio**
Volume automation, fade in / out, five-band EQ and mastering-style presets. Volume, fades and
automation are applied to both preview and export; the EQ and mastering controls are currently
UI-only — see [docs/MAINTENANCE.md](docs/MAINTENANCE.md).

**Export**
Native H.264 video with a stereo AAC audio track, via `MediaCodec` + `MediaMuxer`. Aspect
(16:9, 9:16, 1:1, 4:3), resolution up to 1080p, 24–60 fps, CRF quality, range in / out, and
cancellable renders. Finished files are published to `Movies/EditPro` so they appear in Photos.

**Projects**
Portable `.filmcraft` JSON, saved through the system file picker, with autosave and recovery, a
recent-project list, and confirmation before anything would discard current work.

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
├── MainActivity.kt     Compose host, workspace layout, keyboard shortcuts, file intents
├── viewmodel/          Single source of truth (EditorUiState) and every editing operation
├── model/              Data types, timeline engine, preset catalogue, project serialisation
├── media/              Media import, preview audio, MediaCodec export pipelines
└── ui/                 Compose panels: preview stage, timeline, inspector, dialogs, home screen
```

State lives in one `StateFlow<EditorUiState>`. Editing operations are pure functions over that
state with snapshot-based undo and redo, which keeps the timeline engine testable without an
Android runtime — that is what the JVM test suite covers.

## Status

Actively used, but early. Worth knowing before relying on it:

- **Verification is compile-time.** CI runs the JVM unit tests (timeline engine, serialisation,
  export dimension maths) and builds the APKs. There are **no instrumentation tests**, so UI,
  media and file-system behaviour is verified by hand against a written checklist.
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
