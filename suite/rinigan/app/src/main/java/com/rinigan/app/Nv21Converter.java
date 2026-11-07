package com.rinigan.app;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * v6.2 — CPU pixel conversion helpers for the frame bridges.
 *
 * The peer-side paths (Camera2 ImageReader surfaces, Camera1 preview callbacks)
 * consume YUV (NV21) or JPEG data. Web content and RGBA taps arrive as ARGB
 * bitmaps / RGBA buffers, so conversions are needed:
 *
 *   Bitmap(ARGB)  -> NV21            (WebViewStreamer software frames)
 *   Image(RGBA)   -> NV21            (FrameTapBridge MediaPlayer tap)
 *   NV21          -> scaled NV21     (tap size -> reader size)
 *   NV21          -> Image planes    (ImageWriter YUV_420_888 queueing)
 *   NV21          -> JPEG bytes      (ImageWriter JPEG queueing)
 *
 * All methods are pure-JVM and allocation-conscious: hot paths reuse scratch
 * buffers where the caller allows it, and every method is static + stateless.
 */
public final class Nv21Converter {

    private Nv21Converter() {}

    // ======================== Bitmap -> NV21 ========================

    /** ARGB_8888 bitmap → NV21 byte array (Bi-planar Y + interleaved VU). */
    public static byte[] bitmapToNv21(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        return argbToNv21(pixels, w, h);
    }

    /** ARGB int[] (from Bitmap.getPixels) → NV21. */
    public static byte[] argbToNv21(int[] argb, int w, int h) {
        int frameSize = w * h;
        byte[] nv21 = new byte[frameSize + (frameSize >> 1)];
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int rgb = argb[j * w + i];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv21[yIndex++] = (byte) (y < 16 ? 16 : Math.min(y, 255));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    nv21[uvIndex++] = (byte) (v < 0 ? 0 : Math.min(v, 255));
                    nv21[uvIndex++] = (byte) (u < 0 ? 0 : Math.min(u, 255));
                }
            }
        }
        return nv21;
    }

    // ======================== RGBA Image -> NV21 ========================

    /** ImageReader RGBA_8888 image → NV21. The image is NOT closed by this method. */
    public static byte[] rgbaImageToNv21(Image image) {
        if (image == null) return null;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int w = image.getWidth();
        int h = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] argb = new int[w * h];
        int idx = 0;
        for (int row = 0; row < h; row++) {
            int rowStart = row * rowStride;
            for (int col = 0; col < w; col++) {
                int off = rowStart + col * pixelStride;
                int r = buf.get(off) & 0xFF;
                int g = buf.get(off + 1) & 0xFF;
                int b = buf.get(off + 2) & 0xFF;
                argb[idx++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return argbToNv21(argb, w, h);
    }

    // ======================== NV21 -> Bitmap (v6.3 relay) ========================

    /** NV21 → ARGB_8888 Bitmap (canvas posting of relayed engine frames). */
    public static Bitmap nv21ToBitmap(byte[] nv21, int w, int h) {
        if (nv21 == null || w <= 0 || h <= 0 || nv21.length < w * h * 3 / 2) return null;
        int frameSize = w * h;
        int[] argb = new int[frameSize];
        for (int j = 0; j < h; j++) {
            int yRow = j * w;
            int uvRow = frameSize + (j >> 1) * w;
            for (int i = 0; i < w; i++) {
                int y = nv21[yRow + i] & 0xFF;
                int uvIndex = uvRow + (i & ~1);
                int v = (nv21[uvIndex] & 0xFF) - 128;
                int u = (nv21[uvIndex + 1] & 0xFF) - 128;
                int r = y + ((91881 * v) >> 16);
                int g = y - ((22554 * u + 46802 * v) >> 16);
                int b = y + ((116130 * u) >> 16);
                argb[yRow + i] = 0xFF000000
                        | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ======================== RGBA byte buffer -> NV21 (v6.3 GL readback) ========================

    /**
     * Raw RGBA8888 byte buffer (glReadPixels order: R,G,B,A per pixel) → NV21.
     * Used by the app-side LinkRenderer after reading the decoded video frame.
     */
    public static byte[] rgbaBufferToNv21(java.nio.ByteBuffer rgba, int w, int h) {
        if (rgba == null || w <= 0 || h <= 0) return null;
        int frameSize = w * h;
        byte[] nv21 = new byte[frameSize + (frameSize >> 1)];
        int yIndex = 0;
        int uvIndex = frameSize;
        rgba.position(0);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = (j * w + i) * 4;
                int r = rgba.get(p) & 0xFF;
                int g = rgba.get(p + 1) & 0xFF;
                int b = rgba.get(p + 2) & 0xFF;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv21[yIndex++] = (byte) (y < 16 ? 16 : Math.min(y, 255));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    nv21[uvIndex++] = (byte) (v < 0 ? 0 : Math.min(v, 255));
                    nv21[uvIndex++] = (byte) (u < 0 ? 0 : Math.min(u, 255));
                }
            }
        }
        return nv21;
    }

    // ======================== NV21 scaling (nearest neighbor) ========================

    /** Scales an NV21 frame to a new size (nearest neighbor — fast, good enough at 10-15 fps). */
    public static byte[] nv21Scale(byte[] src, int sw, int sh, int dw, int dh) {
        if (src == null || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return null;
        if (sw == dw && sh == dh) return src;
        byte[] dst = new byte[dw * dh * 3 / 2];
        // Y plane
        for (int dy = 0; dy < dh; dy++) {
            int sy = (dy * sh) / dh;
            int srcRow = sy * sw;
            int dstRow = dy * dw;
            for (int dx = 0; dx < dw; dx++) {
                int sx = (dx * sw) / dw;
                dst[dstRow + dx] = src[srcRow + sx];
            }
        }
        // UV plane (interleaved VU, quarter resolution)
        int srcUvW = sw >> 1;
        int dstUvW = dw >> 1;
        int srcUvH = sh >> 1;
        int dstUvH = dh >> 1;
        int srcUvOff = sw * sh;
        int dstUvOff = dw * dh;
        for (int dy = 0; dy < dstUvH; dy++) {
            int sy = (dy * srcUvH) / dstUvH;
            int srcRow = srcUvOff + sy * srcUvW * 2;
            int dstRow = dstUvOff + dy * dstUvW * 2;
            for (int dx = 0; dx < dstUvW; dx++) {
                int sx = (dx * srcUvW) / dstUvW;
                dst[dstRow + dx * 2] = src[srcRow + sx * 2];
                dst[dstRow + dx * 2 + 1] = src[srcRow + sx * 2 + 1];
            }
        }
        return dst;
    }

    // ======================== NV21 -> Image planes (ImageWriter) ========================

    /**
     * Fills a {@link YUV_420_888} Image acquired from an ImageWriter with NV21 data.
     * Handles both planar (pixelStride 1) and semi-planar (pixelStride 2) consumers.
     */
    public static void fillYuvImage(Image image, byte[] nv21) {
        int w = image.getWidth();
        int h = image.getHeight();
        int frameSize = w * h;

        Image.Plane[] planes = image.getPlanes();
        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        // ---- Y ----
        ByteBuffer yBuf = yPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixStride = yPlane.getPixelStride();
        for (int row = 0; row < h; row++) {
            int imgRow = row * yRowStride;
            int srcRow = row * w;
            if (yPixStride == 1) {
                for (int col = 0; col < w; col++) {
                    yBuf.put(imgRow + col, nv21[srcRow + col]);
                }
            } else {
                for (int col = 0; col < w; col++) {
                    yBuf.put(imgRow + col * yPixStride, nv21[srcRow + col]);
                }
            }
        }

        // ---- U / V from NV21 interleaved (V first) ----
        int cw = w >> 1;
        int ch = h >> 1;
        int uvPixStrideU = uPlane.getPixelStride();
        int uvPixStrideV = vPlane.getPixelStride();
        int uvRowStrideU = uPlane.getRowStride();
        int uvRowStrideV = vPlane.getRowStride();
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();

        boolean semiPlanar = (uvPixStrideU == 2); // NV12-style interleaved output
        for (int row = 0; row < ch; row++) {
            int srcRow = frameSize + row * cw * 2;
            int imgRowU = row * uvRowStrideU;
            int imgRowV = row * uvRowStrideV;
            for (int col = 0; col < cw; col++) {
                byte vVal = nv21[srcRow + col * 2];
                byte uVal = nv21[srcRow + col * 2 + 1];
                if (semiPlanar) {
                    uBuf.put(imgRowU + col * uvPixStrideU, uVal);
                    vBuf.put(imgRowV + col * uvPixStrideV, vVal);
                } else {
                    uBuf.put(imgRowU + col * uvPixStrideU, uVal);
                    vBuf.put(imgRowV + col * uvPixStrideV, vVal);
                }
            }
        }
    }

    // ======================== NV21 -> JPEG ========================

    /** Encodes NV21 to JPEG bytes (for JPEG ImageWriter / takePicture fallbacks). */
    public static byte[] nv21ToJpeg(byte[] nv21, int w, int h, int quality) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            Rect rect = new Rect(0, 0, w, h);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(rect, quality, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
