package com.example.screencap

import android.content.Context
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem

object ExoPlayerManager {
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        if (player == null) {
            player = ExoPlayer.Builder(context).build()
        }
        val item = MediaItem.fromUri(url)
        player?.setMediaItem(item)
        player?.prepare()
        player?.playWhenReady = true
    }

    fun release(context: Context) {
        player?.release()
        player = null
    }
}
