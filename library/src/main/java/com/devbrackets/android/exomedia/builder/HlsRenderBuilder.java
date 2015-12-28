/*
 * Copyright (C) 2015 Brian Wernick,
 * Copyright (C) 2015 Sébastiaan Versteeg,
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.devbrackets.android.exomedia.builder;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;

import com.devbrackets.android.exomedia.exoplayer.EMExoPlayer;
import com.devbrackets.android.exomedia.renderer.EMMediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.VideoFormatSelectorUtil;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.metadata.Id3Parser;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.ManifestFetcher.ManifestCallback;

import java.io.IOException;
import java.util.Map;

/**
 * A RenderBuilder for parsing and creating the renderers for
 * Http Live Streams (HLS).
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class HlsRenderBuilder extends RenderBuilder {

    private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private static final int BUFFER_SEGMENTS = 256;

    private final Context context;
    private final String userAgent;
    private final String url;

    private AsyncRendererBuilder currentAsyncBuilder;

    public HlsRenderBuilder(Context context, String userAgent, String url) {
        super(context, userAgent, url);

        this.context = context;
        this.userAgent = userAgent;
        this.url = url;
    }

    @Override
    public void buildRenderers(EMExoPlayer player) {
        currentAsyncBuilder = new AsyncRendererBuilder(context, userAgent, url, player);
        currentAsyncBuilder.init();
    }

    @Override
    public void cancel() {
        if (currentAsyncBuilder != null) {
            currentAsyncBuilder.cancel();
            currentAsyncBuilder = null;
        }
    }

    private static final class AsyncRendererBuilder implements ManifestCallback<HlsPlaylist> {

        private final Context context;
        private final String userAgent;
        private final String url;
        private final EMExoPlayer player;
        private final ManifestFetcher<HlsPlaylist> playlistFetcher;

        private boolean canceled;

        public AsyncRendererBuilder(Context context, String userAgent, String url, EMExoPlayer player) {
            this.context = context;
            this.userAgent = userAgent;
            this.url = url;
            this.player = player;
            HlsPlaylistParser parser = new HlsPlaylistParser();
            playlistFetcher = new ManifestFetcher<>(url, new DefaultUriDataSource(context, userAgent),
                    parser);
        }

        public void init() {
            playlistFetcher.singleLoad(player.getMainHandler().getLooper(), this);
        }

        public void cancel() {
            canceled = true;
        }

        @Override
        public void onSingleManifestError(IOException e) {
            if (canceled) {
                return;
            }

            player.onRenderersError(e);
        }

        @Override
        public void onSingleManifest(HlsPlaylist manifest) {
            if (canceled) {
                return;
            }

            Handler mainHandler = player.getMainHandler();
            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

            int[] variantIndices = null;
            if (manifest instanceof HlsMasterPlaylist) {
                HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) manifest;
                try {
                    variantIndices = VideoFormatSelectorUtil.selectVideoFormatsForDefaultDisplay(
                            context, masterPlaylist.variants, null, false);
                } catch (DecoderQueryException e) {
                    player.onRenderersError(e);
                    return;
                }
                if (variantIndices.length == 0) {
                    player.onRenderersError(new IllegalStateException("No variants selected."));
                    return;
                }
            }

            DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
            HlsChunkSource chunkSource = new HlsChunkSource(dataSource, url, manifest, bandwidthMeter,
                    variantIndices, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
                    BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, EMExoPlayer.RENDER_VIDEO_INDEX);
            MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context,
                    sampleSource, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 5000, mainHandler, player, 50);
            MediaCodecAudioTrackRenderer audioRenderer = new EMMediaCodecAudioTrackRenderer(sampleSource,
                    null, true, player.getMainHandler(), player, AudioCapabilities.getCapabilities(context));
            MetadataTrackRenderer<Map<String, Object>> id3Renderer = new MetadataTrackRenderer<>(
                    sampleSource, new Id3Parser(), player, mainHandler.getLooper());
            Eia608TrackRenderer closedCaptionRenderer = new Eia608TrackRenderer(sampleSource, player,
                    mainHandler.getLooper());

            TrackRenderer[] renderers = new TrackRenderer[EMExoPlayer.RENDER_COUNT];
            renderers[EMExoPlayer.RENDER_VIDEO_INDEX] = videoRenderer;
            renderers[EMExoPlayer.RENDER_AUDIO_INDEX] = audioRenderer;
            renderers[EMExoPlayer.RENDER_TIMED_METADATA_INDEX] = id3Renderer;
            renderers[EMExoPlayer.RENDER_CLOSED_CAPTION_INDEX] = closedCaptionRenderer;
            player.onRenderers(renderers, bandwidthMeter);
        }

    }

}