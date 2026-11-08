package com.rinigan.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/**
 * Runtime transformation core.
 *
 * Converts a source frame (bitmap or raw NV21 buffer) into the output shape required
 * by a specific application, driven by a {@link TransformProfile}:
 *
 *   rotate (CW 0/90/180/270) -> flip (H/V) -> scale / crop / letterbox -> output buffer
 *
 * Two independent paths are provided:
 *  - Canvas/Bitmap path : used for still images (takePicture) and for computing the
 *                         reference matrix handed to the OpenGL relay.
 *  - NV21 buffer path   : zero-dependency pure-Java transform operating directly on
 *                         Camera1 onPreviewFrame byte buffers (Y plane + interleaved VU),
 *                         including letterbox padding (Y=16, U/V=128) for FIT_CENTER.
 *
 * The OpenGL path uses {@link #buildGlVertexMatrix} (column-major 4x4, clip space).
 */
public final class FrameTransformer {

    private FrameTransformer() {
    }

    // ======================== Geometry helpers ========================

    /** Dimensions of the frame after the profile rotation is applied. */
    public static int[] rotatedSize(int w, int h, int rotation) {
        return (rotation == 90 || rotation == 270) ? new int[]{h, w} : new int[]{w, h};
    }

    /**
     * Final output size for a profile: explicit targetResolution wins, otherwise the
     * app-requested buffer size (which is itself the natural choice for FIT/CROP math).
     */
    public static int[] resolveOutputSize(TransformProfile p, int appW, int appH) {
        if (p != null && p.targetWidth > 0 && p.targetHeight > 0) {
            return new int[]{p.targetWidth, p.targetHeight};
        }
        return new int[]{appW > 0 ? appW : 1080, appH > 0 ? appH : 1920};
    }

    /**
     * Effective rotation, honoring automatic portrait normalization:
     * a landscape source consumed by a portrait buffer gets an extra +90 (CW)
     * when the profile allows it and no explicit rotation is configured.
     */
    public static int effectiveRotation(TransformProfile p, int srcW, int srcH, int outW, int outH) {
        int rot = (p != null) ? p.rotation : 0;
        boolean normalize = (p != null) && p.normalizePortrait;
        if (normalize && rot == 0 && srcW > 0 && srcH > 0 && outW > 0 && outH > 0) {
            boolean srcLandscape = srcW > srcH;
            boolean outPortrait = outH > outW;
            if (srcLandscape && outPortrait) {
                rot = 90;
            }
        }
        return rot;
    }

    // ======================== Bitmap / Canvas path ========================

    /**
     * Builds the android.graphics.Matrix equivalent of the transform pipeline
     * (rotation + flips + fit/crop/stretch into outW x outH).
     */
    public static Matrix buildBitmapMatrix(int srcW, int srcH, int outW, int outH,
                                           TransformProfile profile) {
        int rot = effectiveRotation(profile, srcW, srcH, outW, outH);
        int[] rotated = rotatedSize(srcW, srcH, rot);
        int rw = rotated[0], rh = rotated[1];

        Matrix m = new Matrix();
        // 1. flips around the frame center (source space)
        float fx = (profile != null && profile.flipHorizontal) ? -1f : 1f;
        float fy = (profile != null && profile.flipVertical) ? -1f : 1f;
        if (fx < 0) m.postScale(-1f, 1f, srcW / 2f, srcH / 2f);
        if (fy < 0) m.postScale(1f, -1f, srcW / 2f, srcH / 2f);
        // 2. clockwise rotation about center
        if (rot != 0) {
            m.postRotate(rot, srcW / 2f, srcH / 2f);
        }
        // 3. scale rotated content into the output (same math as the GL path)
        float f;
        if (profile != null && TransformProfile.SCALE_CENTER_CROP.equals(profile.scaleMode)) {
            f = Math.max(outW / (float) rw, outH / (float) rh);
        } else if (profile != null && TransformProfile.SCALE_STRETCH.equals(profile.scaleMode)) {
            f = Math.max(outW / (float) rw, outH / (float) rh); // exact stretch below
        } else {
            f = Math.min(outW / (float) rw, outH / (float) rh);
        }
        float sx, sy;
        if (profile != null && TransformProfile.SCALE_STRETCH.equals(profile.scaleMode)) {
            sx = outW / (float) rw;
            sy = outH / (float) rh;
        } else {
            sx = f;
            sy = f;
        }
        m.postScale(sx, sy);
        // 4. center in the output canvas
        float drawnW = rw * sx, drawnH = rh * sy;
        m.postTranslate((outW - drawnW) / 2f, (outH - drawnH) / 2f);
        return m;
    }

    /**
     * Canvas transform: draws the source bitmap through the pipeline onto an
     * outW x outH ARGB bitmap (letterbox areas become opaque black for FIT_CENTER).
     */
    public static Bitmap transformBitmap(Bitmap src, TransformProfile profile, int outW, int outH) {
        if (src == null || src.isRecycled()) return src;
        try {
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(0xFF000000);
            Matrix m = buildBitmapMatrix(src.getWidth(), src.getHeight(), outW, outH, profile);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(src, m, paint);
            return out;
        } catch (Throwable t) {
            LogUtil.log("[Transform]bitmap transform failed: " + t);
            return src;
        }
    }

    // ======================== OpenGL path ========================

    /**
     * Builds the vertex transform matrix (column-major float[16]) applied by the GL relay
     * shader: gl_Position = uRotMatrix * aPosition, with aPosition spanning [-1, 1].
     *
     * Composition: Scale(clipX, clipY) * RotZ(rotation, clockwise) * Flip.
     * Rotation is CLOCKWISE in visual terms; FIT letterboxes, CROP overflows, STRETCH fills.
     */
    public static float[] buildGlVertexMatrix(int srcW, int srcH, int outW, int outH,
                                              TransformProfile profile) {
        int rot = effectiveRotation(profile, srcW, srcH, outW, outH);
        int[] rotated = rotatedSize(srcW, srcH, rot);
        int rw = Math.max(1, rotated[0]);
        int rh = Math.max(1, rotated[1]);

        float fx = (profile != null && profile.flipHorizontal) ? -1f : 1f;
        float fy = (profile != null && profile.flipVertical) ? -1f : 1f;

        float sx, sy;
        if (profile != null && TransformProfile.SCALE_STRETCH.equals(profile.scaleMode)) {
            sx = 1f;
            sy = 1f;
        } else {
            float f;
            if (TransformProfile.SCALE_CENTER_CROP.equals(profile.scaleMode)) {
                f = Math.max(outW / (float) rw, outH / (float) rh);
            } else {
                f = Math.min(outW / (float) rw, outH / (float) rh);
            }
            sx = (rw * f) / outW;
            sy = (rh * f) / outH;
        }

        // clockwise rotation matrices in clip space (+Y up)
        // R90 = [[0,1],[-1,0]]  R180 = [[-1,0],[0,-1]]  R270 = [[0,-1],[1,0]]
        float r00, r01, r10, r11;
        switch (rot) {
            case 90:
                r00 = 0f;
                r01 = 1f;
                r10 = -1f;
                r11 = 0f;
                break;
            case 180:
                r00 = -1f;
                r01 = 0f;
                r10 = 0f;
                r11 = -1f;
                break;
            case 270:
                r00 = 0f;
                r01 = -1f;
                r10 = 1f;
                r11 = 0f;
                break;
            default:
                r00 = 1f;
                r01 = 0f;
                r10 = 0f;
                r11 = 1f;
        }

        // M = S * R * F (2D affine part)
        float m00 = sx * r00 * fx;
        float m01 = sx * r01 * fy;
        float m10 = sy * r10 * fx;
        float m11 = sy * r11 * fy;

        // column-major 4x4
        return new float[]{
                m00, m10, 0f, 0f,
                m01, m11, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    // ======================== NV21 buffer path ========================

    private static final byte PAD_Y = 16;      // limited-range black
    private static final byte PAD_UV = (byte) 128;

    /**
     * Full NV21 pipeline: rotate (CW) -> flip -> scale/crop/letterbox into dstOut.
     * dstOut must be exactly dstW * dstH * 3 / 2 bytes.
     *
     * @return true when the transform was applied, false when the input was unusable
     *         (caller should fall back to a plain copy).
     */
    public static boolean transformNv21(byte[] src, int srcW, int srcH,
                                        byte[] dstOut, int dstW, int dstH,
                                        TransformProfile profile) {
        try {
            if (src == null || dstOut == null || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
                return false;
            }
            int expectedSrc = srcW * srcH * 3 / 2;
            int expectedDst = dstW * dstH * 3 / 2;
            if (src.length < expectedSrc || dstOut.length < expectedDst) return false;

            int rot = effectiveRotation(profile, srcW, srcH, dstW, dstH);
            boolean flipH = profile != null && profile.flipHorizontal;
            boolean flipV = profile != null && profile.flipVertical;
            String mode = profile != null ? profile.scaleMode : TransformProfile.SCALE_FIT_CENTER;

            byte[] mid = (rot != 0) ? rotateNv21(src, srcW, srcH, rot) : src;
            int[] midDims = rotatedSize(srcW, srcH, rot);
            int mw = midDims[0], mh = midDims[1];

            if (flipH || flipV) {
                mid = flipNv21(mid, mw, mh, flipH, flipV);
            }

            switch (mode) {
                case TransformProfile.SCALE_CENTER_CROP:
                    scaleCropInto(mid, mw, mh, dstOut, dstW, dstH);
                    break;
                case TransformProfile.SCALE_STRETCH:
                    scaleInto(mid, mw, mh, dstOut, dstW, dstH, 0, 0, mw, mh);
                    break;
                default: // FIT_CENTER
                    scaleFitInto(mid, mw, mh, dstOut, dstW, dstH);
                    break;
            }
            return true;
        } catch (Throwable t) {
            LogUtil.log("[Transform]nv21 transform failed: " + t);
            return false;
        }
    }

    /** Clockwise NV21 rotation by 0/90/180/270 degrees. */
    public static byte[] rotateNv21(byte[] in, int w, int h, int deg) {
        int rot = TransformProfile.normalizeRotation(deg);
        if (rot == 0) return in;
        byte[] out;
        if (rot == 180) {
            out = new byte[in.length];
            // Y plane
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[(h - 1 - y) * w + (w - 1 - x)] = in[y * w + x];
                }
            }
            // chroma units (V,U)
            int cw = w / 2, ch = h / 2;
            int cOff = w * h;
            for (int cy = 0; cy < ch; cy++) {
                for (int cx = 0; cx < cw; cx++) {
                    int si = cOff + (cy * cw + cx) * 2;
                    int di = cOff + ((ch - 1 - cy) * cw + (cw - 1 - cx)) * 2;
                    out[di] = in[si];
                    out[di + 1] = in[si + 1];
                }
            }
            return out;
        }

        // 90 / 270: output dims swapped
        int nw = h, nh = w;
        out = new byte[in.length];
        if (rot == 90) {
            // (x, y) -> (x' = h-1-y, y' = x)
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[x * h + (h - 1 - y)] = in[y * w + x];
                }
            }
        } else { // 270
            // (x, y) -> (x' = y, y' = w-1-x)
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[(w - 1 - x) * h + y] = in[y * w + x];
                }
            }
        }
        // chroma units with the same mapping
        int cw = w / 2, ch = h / 2;
        int cOff = w * h;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int si = cOff + (cy * cw + cx) * 2;
                int dx, dy;
                if (rot == 90) {
                    dx = ch - 1 - cy;
                    dy = cx;
                } else {
                    dx = cy;
                    dy = cw - 1 - cx;
                }
                int di = cOff + (dy * ch + dx) * 2;
                out[di] = in[si];
                out[di + 1] = in[si + 1];
            }
        }
        return out;
    }

    /** Horizontal / vertical NV21 mirror. */
    public static byte[] flipNv21(byte[] in, int w, int h, boolean flipH, boolean flipV) {
        if (!flipH && !flipV) return in;
        byte[] out = new byte[in.length];
        // Y plane
        for (int y = 0; y < h; y++) {
            int sy = flipV ? (h - 1 - y) : y;
            for (int x = 0; x < w; x++) {
                int sx = flipH ? (w - 1 - x) : x;
                out[y * w + x] = in[sy * w + sx];
            }
        }
        // chroma units
        int cw = w / 2, ch = h / 2;
        int cOff = w * h;
        for (int cy = 0; cy < ch; cy++) {
            int scy = flipV ? (ch - 1 - cy) : cy;
            for (int cx = 0; cx < cw; cx++) {
                int scx = flipH ? (cw - 1 - cx) : cx;
                int si = cOff + (scy * cw + scx) * 2;
                int di = cOff + (cy * cw + cx) * 2;
                out[di] = in[si];
                out[di + 1] = in[si + 1];
            }
        }
        return out;
    }

    /** FIT_CENTER: scale preserving aspect, center, letterbox with Y16/UV128. */
    private static void scaleFitInto(byte[] src, int sw, int sh,
                                     byte[] dst, int dw, int dh) {
        float f = Math.min(dw / (float) sw, dh / (float) sh);
        int useW = Math.max(2, ((int) (sw * f)) & ~1);
        int useH = Math.max(2, ((int) (sh * f)) & ~1);
        int offX = ((dw - useW) / 2) & ~1;
        int offY = ((dh - useH) / 2) & ~1;
        fillLetterbox(dst, dw, dh);
        scaleInto(src, sw, sh, dst, dw, dh, offX, offY, useW, useH);
    }

    /** CENTER_CROP: center-crop the source to the destination aspect, then scale to fill. */
    private static void scaleCropInto(byte[] src, int sw, int sh,
                                      byte[] dst, int dw, int dh) {
        float dstAspect = dw / (float) dh;
        int cropW = sw, cropH = sh;
        if (sw / (float) sh > dstAspect) {
            cropW = Math.max(2, ((int) (sh * dstAspect)) & ~1);
        } else {
            cropH = Math.max(2, ((int) (sw / dstAspect)) & ~1);
        }
        int cropX = ((sw - cropW) / 2) & ~1;
        int cropY = ((sh - cropH) / 2) & ~1;
        scaleInto(src, sw, sh, dst, dw, dh, -cropX, -cropY, cropW, cropH);
    }

    /**
     * Nearest-neighbor NV21 scaler.
     * (cropX, cropY) are the source-pixel coordinates that land on destination (0,0);
     * useW/useH define the source region mapped onto the full destination.
     */
    private static void scaleInto(byte[] src, int sw, int sh,
                                  byte[] dst, int dw, int dh,
                                  int cropX, int cropY, int useW, int useH) {
        // Y plane
        for (int dy = 0; dy < dh; dy++) {
            int sy = cropY + (dy * useH) / dh;
            if (sy < 0) sy = 0;
            if (sy >= sh) sy = sh - 1;
            int dstRow = dy * dw;
            int srcRow = sy * sw;
            for (int dx = 0; dx < dw; dx++) {
                int sx = cropX + (dx * useW) / dw;
                if (sx < 0) sx = 0;
                if (sx >= sw) sx = sw - 1;
                dst[dstRow + dx] = src[srcRow + sx];
            }
        }
        // chroma (V,U interleaved)
        int cw = sw / 2, chh = sh / 2;
        int dcw = dw / 2, dch = dh / 2;
        int cOff = sw * sh;
        int dCOff = dw * dh;
        for (int dy = 0; dy < dch; dy++) {
            int scy = (cropY + (dy * useH) / dh) / 2;
            if (scy < 0) scy = 0;
            if (scy >= chh) scy = chh - 1;
            int dstRow = dCOff + dy * dcw * 2;
            int srcRow = cOff + scy * cw * 2;
            for (int dx = 0; dx < dcw; dx++) {
                int scx = (cropX + (dx * useW) / dw) / 2;
                if (scx < 0) scx = 0;
                if (scx >= cw) scx = cw - 1;
                int si = srcRow + scx * 2;
                int di = dstRow + dx * 2;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
            }
        }
    }

    /** Fills the whole destination with letterbox color (Y=16, U/V=128). */
    private static void fillLetterbox(byte[] dst, int w, int h) {
        int yLen = w * h;
        java.util.Arrays.fill(dst, 0, yLen, PAD_Y);
        int cLen = yLen / 2;
        if (cLen > 0) {
            java.util.Arrays.fill(dst, yLen, yLen + cLen, PAD_UV);
        }
    }
}
