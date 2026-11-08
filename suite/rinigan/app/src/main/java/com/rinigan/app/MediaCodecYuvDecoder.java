package com.rinigan.app;

/** MediaCodec YUV decoder stub. A future version will extract YUV data from video frames. */
public class MediaCodecYuvDecoder {
    public MediaCodecYuvDecoder() {}

    public void setTargetSize(int width, int height) {}
    public void decode(String videoPath) {
        LogUtil.log("[CS]MediaCodecYuvDecoder stub");
    }
    public void stopDecode() {}
    public byte[] getLatestFrame() { return null; }
}