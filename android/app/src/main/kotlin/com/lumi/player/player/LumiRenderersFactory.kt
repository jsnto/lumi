package com.lumi.player.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * The playback [DefaultRenderersFactory] with the audio level's
 * [LoudnessProcessor] installed in the sink's audio processor chain (the
 * Media3 hook: [DefaultAudioSink.Builder.setAudioProcessors]). Every
 * decoded audio buffer passes the processor before it reaches the
 * AudioTrack, so the per-track gain (and the boost's soft-clip limiter)
 * apply to what the user hears.
 */
class LumiRenderersFactory(
    context: Context,
    private val audioProcessors: Array<out AudioProcessor>,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(audioProcessors)
            .build()
    }
}
