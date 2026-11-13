package com.editpro.studio.media

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays the timeline's sound during preview, roughly in sync with the playhead.
 *
 * Preview only. Every clip that carries sound — clips on the audio tracks and the soundtrack of
 * video clips — gets its own `MediaPlayer`, started at the correct source offset (honouring trim,
 * speed and the current playhead), with volume, fades, volume automation and stereo balance
 * applied as playback advances. Mute/solo is resolved by the caller. EQ and compression are
 * applied on export only.
 *
 * Every operation is defensive: a codec that refuses a file, a missing media file or a player
 * that dies mid-clip degrades to silence rather than interrupting editing.
 *
 * The exported MP4's audio is a completely separate pipeline (see TimelineAudioEncoder).
 */
class PreviewAudioEngine {

    private val active = mutableListOf<Pair<MediaPlayer, TimelineClip>>()
    private var scheduler: Job? = null

    fun stop() {
        scheduler?.cancel()
        scheduler = null
        active.forEach { (p, _) -> release(p) }
        active.clear()
    }

    /**
     * Starts every audible clip that is still ahead of (or under) [fromSec].
     *
     * @param audibleTracks tracks that should be heard — mute/solo already resolved.
     */
    fun start(
        scope: CoroutineScope,
        clips: List<ResolvedClip>,
        assets: Map<String, MediaAsset>,
        audibleTracks: Set<TimelineTrack>,
        fromSec: Double
    ) {
        stop()
        // The moment the playhead was at [fromSec]. Every cue below is measured from it, so the
        // time spent opening a file never makes its clip late.
        val startedAt = SystemClock.elapsedRealtime()
        scheduler = scope.launch {
            for (rc in clips) {
                val clip = rc.clip
                if (clip.track !in audibleTracks) continue
                if (rc.end <= fromSec + 0.02) continue
                val asset = clip.assetId?.let { assets[it] } ?: continue
                if (!TimelineEngine.carriesSound(clip, asset)) continue
                if (clip.volume <= 0.0 && clip.volumeKeyframes.isEmpty()) continue
                val path = asset.localPath ?: continue
                if (!File(path).exists()) continue

                // Each clip waits for its own cue, so a late clip is not held up by earlier ones.
                launch {
                    // Milliseconds from the start of playback to the clip's first frame; negative
                    // when the clip is already under the playhead.
                    val cueMs = ((rc.start - fromSec) * 1000.0).toLong()
                    val openIn = cueMs - OPEN_AHEAD_MS - (SystemClock.elapsedRealtime() - startedAt)
                    if (openIn > 0L) delay(openIn)

                    val speed = clip.speed.coerceIn(0.25, 4.0)
                    val player = MediaPlayer()
                    var handedOver = false
                    try {
                        // Opening a file can take a few hundred milliseconds, more for video.
                        // It happens off the main thread so playback never freezes the editor,
                        // and a little before the cue so the clip can still start on time.
                        withContext(Dispatchers.IO) {
                            player.setDataSource(path)
                            player.prepare()
                        }
                        val early = cueMs - (SystemClock.elapsedRealtime() - startedAt)
                        if (early > 0L) {
                            // Ready ahead of time: line up on the clip's first frame and wait.
                            seek(player, clip.trimIn)
                            delay(early)
                        }
                        // Where the playhead is inside the clip by now.
                        val localSec = ((SystemClock.elapsedRealtime() - startedAt - cueMs) / 1000.0).coerceAtLeast(0.0)
                        if (localSec < rc.duration) {
                            if (early <= 0L) seek(player, clip.trimIn + localSec * speed)
                            applyVolume(player, clip, localSec, rc.duration)
                            if (speed != 1.0) {
                                // Setting playback params also starts the player on most devices.
                                runCatching { player.playbackParams = PlaybackParams().setSpeed(speed.toFloat()) }
                            }
                            if (!player.isPlaying) player.start()
                            active.add(player to clip)
                            handedOver = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A file the player refuses is simply not heard.
                    } finally {
                        // Stopped while it was still opening, or failed: nothing else owns it.
                        if (!handedOver) release(player)
                    }
                }
            }
        }
    }

    /**
     * Moves a player to an exact source time. The plain `seekTo` lands on the previous video key
     * frame, which for the sound of a video clip can be a second or more early; the picture is
     * frame-accurate, so the sound has to be as well.
     */
    private fun seek(player: MediaPlayer, sourceSec: Double) {
        player.seekTo((sourceSec * 1000.0).toLong().coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
    }

    /**
     * Called from the playback ticker: keeps fades/automation audible and reaps finished players.
     * Cheap enough to run a few times a second.
     */
    fun updateVolumes(clips: List<ResolvedClip>, playheadSec: Double) {
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (player, clip) = iterator.next()
            val rc = clips.firstOrNull { it.clip.id == clip.id }
            if (rc == null || playheadSec > rc.end) {
                release(player)
                iterator.remove()
                continue
            }
            if (playheadSec < rc.start) continue
            applyVolume(player, rc.clip, playheadSec - rc.start, rc.duration)
        }
    }

    private fun applyVolume(player: MediaPlayer, clip: TimelineClip, localSec: Double, durationSec: Double) {
        val base = (clip.volume / 100.0).coerceIn(0.0, 2.0)
        val envelope = TimelineEngine.audioEnvelope(clip, localSec, durationSec)
        val gain = (base * envelope).coerceIn(0.0, 1.0).toFloat()
        val (left, right) = AudioDsp.panGains(clip.audioPan)
        runCatching { player.setVolume(gain * left, gain * right) }
    }

    private fun release(player: MediaPlayer) {
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private companion object {
        /** How long before its cue a clip's file is opened. */
        const val OPEN_AHEAD_MS = 600L
    }
}
