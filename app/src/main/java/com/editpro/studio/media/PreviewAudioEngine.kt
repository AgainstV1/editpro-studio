package com.editpro.studio.media

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                    val delayMs = ((rc.start - fromSec) * 1000.0).toLong()
                    if (delayMs > 0L) delay(delayMs)

                    val speed = clip.speed.coerceIn(0.25, 4.0)
                    val localSec = (fromSec - rc.start).coerceAtLeast(0.0)
                    val offsetSec = clip.trimIn + localSec * speed
                    val player = MediaPlayer()
                    try {
                        player.setDataSource(path)
                        player.prepare()
                        player.seekTo((offsetSec * 1000.0).toLong().toInt().coerceAtLeast(0))
                        applyVolume(player, clip, localSec, rc.duration)
                        if (speed != 1.0) {
                            // Setting playback params also starts the player on most devices.
                            runCatching { player.playbackParams = PlaybackParams().setSpeed(speed.toFloat()) }
                        }
                        if (!player.isPlaying) player.start()
                        active.add(player to clip)
                    } catch (e: Exception) {
                        release(player)
                    }
                }
            }
        }
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
}
