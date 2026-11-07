package com.rinigan.app;

import android.media.MediaPlayer;

/**
 * Player manager stub. The current stage uses MediaPlayer + VideoToFrames directly.
 * Later stages can integrate GLVideoRenderer / SurfaceRelay extensions.
 */
public class MediaPlayerManager {
    public MediaPlayer mplayer1;
    public MediaPlayer mMediaPlayer;
    public MediaPlayer c2_player;
    public MediaPlayer c2_player_1;

    public void releaseCamera1Resources() {
        stopAndRelease(mplayer1); mplayer1 = null;
        stopAndRelease(mMediaPlayer); mMediaPlayer = null;
    }

    public void releaseCamera2Resources() {
        stopAndRelease(c2_player); c2_player = null;
        stopAndRelease(c2_player_1); c2_player_1 = null;
    }

    public void releaseAllRenderers() {}
    public void updateRotation(int degrees) {}
    public void restartAll() {}

    private void stopAndRelease(MediaPlayer p) {
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
            p.release();
        }
    }
}