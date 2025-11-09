package com.ajmat.screencap

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

object ExoPlayerManager {
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(context).build()
                player?.setVolume(1.0f)  // Ensure full volume
            }

            val item = MediaItem.Builder()
                .setUri(url)
                .build()

            player?.setMediaItem(item)
            player?.prepare()
            player?.playWhenReady = true
            Log.i("ExoPlayer", "Playing: $url")
        } catch (e: Throwable) {
            Log.e("ExoPlayer", "Play failed", e)
            release()  // Reset on error
        }
    }

    fun release() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Throwable) {
            Log.e("ExoPlayer", "Release failed", e)
        } finally {
            player = null
        }
    }
}
