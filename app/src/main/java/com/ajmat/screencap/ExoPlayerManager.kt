package com.ajmat.screencap

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MediaItemBuilder

object ExoPlayerManager {
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(context).build()
            }
            val item = MediaItem.fromUri(url)
            player?.setMediaItem(item)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Throwable) {
            // prevent crash if media libs missing or bad url
            e.printStackTrace()
        }
    }

    fun release(context: Context) {
        try {
            player?.release()
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            player = null
        }
    }
}
