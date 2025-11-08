package com.ajmat.screencap

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

object ExoPlayerManager {
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(context).build()
            }

            val item = MediaItem.Builder()
                .setUri(url)
                .build()

            player?.setMediaItem(item)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            player?.release()
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            player = null
        }
    }
}
