package com.rinigan.app;

import android.view.Surface;

/** ExoPlayer backend stub. A future version should add the Media3 ExoPlayer dependency for RTSP playback. */
public class ExoPlayerBackend implements SurfacePlayerBackend {
    private Listener listener;
    private Surface outputSurface;

    @Override public void setOutputSurface(Surface surface) { this.outputSurface = surface; }
    @Override public void open(MediaSourceDescriptor source) {
        LogUtil.log("[CS]ExoPlayerBackend: stub mode, stream playback not implemented");
        if (listener != null) listener.onError("ExoPlayer not available", null);
    }
    @Override public void restart() {}
    @Override public void stop() {}
    @Override public void release() {}
    @Override public boolean isPlaying() { return false; }
    @Override public long getCurrentPositionMs() { return 0; }
    @Override public long getDurationMs() { return -1; }
    @Override public void setLooping(boolean looping) {}
    @Override public void setVolume(float volume) {}
    @Override public void setListener(Listener listener) { this.listener = listener; }
}