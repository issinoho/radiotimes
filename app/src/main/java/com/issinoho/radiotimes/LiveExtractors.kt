package com.issinoho.radiotimes

import android.net.Uri
import android.util.Log
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * DefaultExtractorsFactory, except that MPEG-TS streams drop audio
 * description tracks.
 *
 * Why: UK DVB channels from an HDHomeRun (e.g. BBC TWO SD) carry an audio
 * description track that sends no frames while AD isn't being broadcast.
 * ExoPlayer's progressive source won't finish preparing until every track
 * has produced a format, so it buffered until its memory target, stopped
 * loading, and failed after ~45 s with "Playback stuck buffering and not
 * loading" -- the "Connecting…" loop seen on the Stick. ffprobe of that
 * stream shows the track as `mp3, 0 channels (visual impaired)
 * (descriptions) (dependent)`.
 *
 * The TS extractor is built exactly as DefaultExtractorsFactory 1.5.1 builds
 * it (mode, flags, subtitle handling, timestamp search), with only the
 * payload reader factory wrapped.
 */
@OptIn(UnstableApi::class)
class LiveExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    private var textTrackTranscoding = true
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()

    @Deprecated("Mirrors the ExtractorsFactory API")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        textTrackTranscoding = enabled
        @Suppress("DEPRECATION")
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        subtitleParserFactory = factory
        delegate.setSubtitleParserFactory(factory)
        return this
    }

    override fun createExtractors(): Array<Extractor> = swapTs(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swapTs(delegate.createExtractors(uri, responseHeaders))

    private fun swapTs(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i -> if (extractors[i] is TsExtractor) liveTsExtractor() else extractors[i] }

    private fun liveTsExtractor(): TsExtractor = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        if (textTrackTranscoding) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        subtitleParserFactory,
        TimestampAdjuster(0),
        SkipAudioDescription(DefaultTsPayloadReaderFactory()),
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
    )

    private class SkipAudioDescription(private val inner: TsPayloadReader.Factory) : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = inner.createInitialPayloadReaders()

        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
            if (esInfo.audioType == TsPayloadReader.EsInfo.AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY) {
                Log.i(TAG, "play | skipping audio description track (stream type $streamType, ${esInfo.language})")
                return null
            }
            return inner.createPayloadReader(streamType, esInfo)
        }
    }
}
