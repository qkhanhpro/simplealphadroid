package com.simplealphadroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/**
 * Shrinks live-view JPEGs before they go out over Wi-Fi. The A6400 sends ~115 KB 1024x680 frames
 * (~23 Mbit/s at 25 fps), which a weak link can't carry. Each quality level caches its last result,
 * so any number of viewers costs one re-encode per frame.
 */
final class Transcode {
    enum Q {
        FULL(1, 0), MED(1, 50), SMALL(2, 55);
        final int sample, jpegQ;
        Q(int sample, int jpegQ) { this.sample = sample; this.jpegQ = jpegQ; }
        static Q parse(String s) {
            if (s == null) return MED;
            switch (s.toLowerCase(java.util.Locale.US)) {
                case "full": return FULL;
                case "small": return SMALL;
                default: return MED;
            }
        }
    }

    private static final class Slot {
        long frameNo = -1;
        byte[] out;
        Bitmap reuse;
        final ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
    }

    private static final Slot[] SLOTS = { new Slot(), new Slot(), new Slot() };

    /** Returns the frame at quality q; falls back to the original JPEG on any decode/encode failure. */
    static byte[] get(byte[] jpeg, long frameNo, Q q) {
        if (q == Q.FULL || jpeg == null) return jpeg;
        Slot s = SLOTS[q.ordinal()];
        synchronized (s) {
            if (s.frameNo == frameNo && s.out != null) return s.out;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = q.sample;
                o.inMutable = true;
                if (s.reuse != null && !s.reuse.isRecycled()) o.inBitmap = s.reuse;
                Bitmap b;
                try {
                    b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                } catch (IllegalArgumentException sizeChanged) {
                    o.inBitmap = null;
                    b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                }
                if (b == null) return jpeg;
                s.reuse = b;
                s.bo.reset();
                b.compress(Bitmap.CompressFormat.JPEG, q.jpegQ, s.bo);
                s.out = s.bo.toByteArray();
                s.frameNo = frameNo;
                return s.out;
            } catch (Throwable t) {
                L.e("transcode " + q, t);
                return jpeg;
            }
        }
    }

    private Transcode() {}
}
