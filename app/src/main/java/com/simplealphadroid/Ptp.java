package com.simplealphadroid;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PTP over USB bulk transport (PIMA 15740 USB still image class).
 * Container: u32 length | u16 type (1 cmd, 2 data, 3 resp, 4 event) | u16 code | u32 txid | payload/params. LE.
 * All transactions are serialized through one fair lock.
 */
public final class Ptp {
    /** USB transaction failures since app start (diagnostics). */
    public static volatile int failures;
    public static final int OK = 0x2001;

    public static final class Result {
        public int op, code, tid;
        public long[] params = new long[0];
        public byte[] data;          // data-in payload (null if no data phase)
        public boolean ok() { return code == OK; }
        public String codeName() { return respName(code); }
    }

    public static final class Container {
        public int type, code, tid;
        public byte[] payload;
    }

    private final UsbDeviceConnection conn;
    private final UsbEndpoint in, out;
    public final UsbEndpoint intr;
    private final int mpsIn, mpsOut;
    public final ReentrantLock lock = new ReentrantLock(true);
    private int nextTid = 0;
    private final byte[] buf = new byte[512 * 1024];
    private byte[] pending;          // bytes over-read past the end of a container
    public int timeoutMs = 5000;
    private static final int MAX_READ = 16 * 1024;   // multiple of 512 (bulk mps)
    private int maxRead = MAX_READ;                   // shrinks 16K -> 4K -> 1K -> 512 if reads fail instantly

    public Ptp(UsbDeviceConnection conn, UsbEndpoint in, UsbEndpoint out, UsbEndpoint intr) {
        this.conn = conn; this.in = in; this.out = out; this.intr = intr;
        this.mpsIn = Math.max(64, in.getMaxPacketSize());
        this.mpsOut = Math.max(64, out.getMaxPacketSize());
        L.i("PTP transport: bulkIn=" + L.h16(in.getAddress()) + " mps=" + in.getMaxPacketSize()
                + " bulkOut=" + L.h16(out.getAddress()) + " mps=" + out.getMaxPacketSize()
                + " intr=" + (intr == null ? "none" : L.h16(intr.getAddress()) + " mps=" + intr.getMaxPacketSize()));
    }

    /** Transport failures in a row (reset by any completed transaction); SonyCam recovers the link when it climbs. */
    public volatile int consecutiveFailures;
    /** Called (outside the lock) after a transport failure, so the owner can start recovery. */
    public volatile Runnable onFailure;

    /** Run one transaction; DeviceBusy (0x2019: nothing was executed) is retried with backoff 25/50/100/200 ms
     *  (as darkgrade does). outData != null means a data-out phase. Data-in is detected automatically. */
    public Result transact(int op, long[] params, byte[] outData, boolean log) throws IOException {
        Result r = null;
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                L.w("  " + L.h16(op) + " DeviceBusy, retry " + i);
                try { Thread.sleep(25L << (i - 1)); } catch (InterruptedException ignored) {}
            }
            try {
                r = transactOnce(op, params, outData, log);
            } catch (IOException e) {
                consecutiveFailures++;
                Runnable f = onFailure;
                if (f != null) f.run();
                throw e;
            }
            consecutiveFailures = 0;
            if (r.code != 0x2019) break;
        }
        return r;
    }

    private Result transactOnce(int op, long[] params, byte[] outData, boolean log) throws IOException {
        if (params == null) params = new long[0];
        lock.lock();
        try {
            int tid;
            if (op == 0x1002) { tid = 0; nextTid = 1; } else { tid = nextTid++; }
            if (log) {
                StringBuilder sb = new StringBuilder("TX ").append(L.h16(op)).append(' ').append(opName(op))
                        .append(" tid=").append(tid).append(" p=[");
                for (int i = 0; i < params.length; i++) { if (i > 0) sb.append(','); sb.append(L.h32(params[i])); }
                sb.append(']');
                if (outData != null) sb.append(" out(").append(outData.length).append(")=").append(L.hex(outData, 0, outData.length, 64));
                L.i(sb.toString());
            }
            long t0 = System.currentTimeMillis();
            try {
                int n = Math.min(params.length, 5);
                byte[] cmd = new byte[12 + 4 * n];
                put32(cmd, 0, cmd.length); put16(cmd, 4, 1); put16(cmd, 6, op); put32(cmd, 8, tid);
                for (int i = 0; i < n; i++) put32(cmd, 12 + 4 * i, params[i]);
                write(cmd);
                if (outData != null) {
                    byte[] d = new byte[12 + outData.length];
                    put32(d, 0, d.length); put16(d, 4, 2); put16(d, 6, op); put32(d, 8, tid);
                    System.arraycopy(outData, 0, d, 12, outData.length);
                    write(d);
                    if (d.length % mpsOut == 0) write(new byte[0]); // ZLP terminates a full-packet transfer
                }
                Result r = new Result();
                r.op = op;
                boolean gotResp = false;
                for (int guard = 0; guard < 4 && !gotResp; guard++) {
                    Container c = readContainer(timeoutMs);
                    if (c.type == 2) {
                        r.data = c.payload;
                        if (c.tid != tid) L.w("  data container tid " + c.tid + " != " + tid);
                    } else if (c.type == 3) {
                        r.code = c.code; r.tid = c.tid;
                        int np = c.payload.length / 4;
                        r.params = new long[np];
                        for (int i = 0; i < np; i++) r.params[i] = get32(c.payload, 4 * i);
                        if (c.tid != tid) L.w("  response tid " + c.tid + " != " + tid);
                        gotResp = true;
                    } else {
                        L.w("  unexpected container type=" + c.type + " code=" + L.h16(c.code) + " len=" + c.payload.length
                                + " " + L.hex(c.payload, 0, c.payload.length, 32));
                    }
                }
                if (!gotResp) throw new IOException("no response container");
                if (log || (!r.ok() && !(op == 0x1009 && r.code == 0x200F))) {
                    StringBuilder sb = new StringBuilder("RX ").append(L.h16(op)).append(" -> ").append(L.h16(r.code))
                            .append(' ').append(respName(r.code)).append(" tid=").append(r.tid)
                            .append(' ').append(System.currentTimeMillis() - t0).append("ms");
                    if (r.params.length > 0) {
                        sb.append(" rp=[");
                        for (int i = 0; i < r.params.length; i++) { if (i > 0) sb.append(','); sb.append(L.h32(r.params[i])); }
                        sb.append(']');
                    }
                    if (r.data != null) sb.append(" data(").append(r.data.length).append(")=").append(L.hex(r.data, 0, r.data.length, 64));
                    L.i(sb.toString());
                }
                return r;
            } catch (IOException e) {
                failures++;
                L.e("PTP " + L.h16(op) + " " + opName(op) + " tid=" + tid + " failed after "
                        + (System.currentTimeMillis() - t0) + "ms", e);
                drain();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private void write(byte[] b) throws IOException {
        int r = conn.bulkTransfer(out, b, 0, b.length, timeoutMs);
        if (r != b.length) throw new IOException("bulk OUT returned " + r + " (wanted " + b.length + ")");
    }

    private int readSome(byte[] dst, int off, int max, int timeout) {
        if (pending != null) {
            int n = Math.min(max, pending.length);
            System.arraycopy(pending, 0, dst, off, n);
            if (n == pending.length) pending = null;
            else { byte[] rest = new byte[pending.length - n]; System.arraycopy(pending, n, rest, 0, rest.length); pending = rest; }
            return n;
        }
        return conn.bulkTransfer(in, dst, off, max, timeout);
    }

    /** Read one full container from bulk IN; may span many bulk reads. Skips zero-length packets. */
    private Container readContainer(int timeout) throws IOException {
        byte[] acc = buf;
        int got = 0, zlp = 0;
        long len = -1;
        while (len < 0 || got < len) {
            int want;
            if (len < 0) want = acc.length - got;
            else {
                long rem = len - got;
                long r = ((rem + mpsIn - 1) / mpsIn) * mpsIn;
                want = (int) Math.min(acc.length - got, r);
            }
            if (want <= 0) throw new IOException("buffer full got=" + got + " len=" + len);
            // usbfs kmallocs the whole request; large reads fail instantly (-1) on fragmented memory (MediaTek phone)
            if (want > maxRead) want = maxRead;
            long tr = System.currentTimeMillis();
            int n = readSome(acc, got, want, timeout);
            if (n < 0 && maxRead > 512 && System.currentTimeMillis() - tr < 50) {
                // instant failure (not a timeout): the request size is the problem; shrink it for the rest of the session
                maxRead = Math.max(512, maxRead / 4);
                L.w("  bulk IN of " + want + " B failed instantly; read size now " + maxRead + " B");
                continue;
            }
            if (n < 0) throw new IOException("bulk IN returned " + n + " (timeout/error) got=" + got + " len=" + len);
            if (n == 0) { if (++zlp > 3) throw new IOException("too many ZLPs"); continue; }
            got += n;
            if (len < 0 && got >= 4) {
                len = get32(acc, 0);
                if (len < 12 || len > 256L * 1024 * 1024) {
                    throw new IOException("bad container length " + len + " head=" + L.hex(acc, 0, got, 32));
                }
                if (len + mpsIn > acc.length) {
                    byte[] big = new byte[(int) (((len + mpsIn - 1) / mpsIn) * mpsIn + mpsIn)];
                    System.arraycopy(acc, 0, big, 0, got);
                    acc = big;
                }
            }
        }
        if (got > len) {
            int extra = (int) (got - len);
            byte[] p = new byte[extra];
            System.arraycopy(acc, (int) len, p, 0, extra);
            pending = p;
            L.w("  over-read " + extra + " bytes past container (kept for next read): " + L.hex(p, 0, extra, 24));
        }
        Container c = new Container();
        c.type = get16(acc, 4); c.code = get16(acc, 6); c.tid = (int) get32(acc, 8);
        c.payload = new byte[(int) len - 12];
        System.arraycopy(acc, 12, c.payload, 0, c.payload.length);
        return c;
    }

    /** Read one event container from the interrupt endpoint (no lock). null on timeout. */
    public Container readEvent(byte[] ebuf, int timeout) {
        if (intr == null) return null;
        int n = conn.bulkTransfer(intr, ebuf, 0, ebuf.length, timeout);
        if (n < 12) {
            if (n > 0) L.w("EVT short read " + n + ": " + L.hex(ebuf, 0, n, 32));
            return null;
        }
        Container c = new Container();
        int len = (int) Math.min(get32(ebuf, 0), n);
        c.type = get16(ebuf, 4); c.code = get16(ebuf, 6); c.tid = (int) get32(ebuf, 8);
        c.payload = new byte[Math.max(0, len - 12)];
        System.arraycopy(ebuf, 12, c.payload, 0, c.payload.length);
        return c;
    }

    /** After an error: throw away whatever the camera still sends. Caller holds the lock. */
    private void drain() {
        pending = null;
        int total = 0;
        for (int i = 0; i < 50; i++) {
            int n = conn.bulkTransfer(in, buf, 0, maxRead, 100);
            if (n < 0) break;
            total += n;
        }
        if (total > 0) L.w("drained " + total + " bytes from bulk IN");
    }

    /** PTP class request: Device Reset (0x66). */
    public int classReset(int ifaceId) {
        lock.lock();
        try {
            int r = conn.controlTransfer(0x21, 0x66, 0, ifaceId, null, 0, 2000);
            L.i("class request DeviceReset(0x66) -> " + r);
            pending = null;
            return r;
        } finally { lock.unlock(); }
    }

    /** Clear a stall on bulk IN/OUT and the interrupt endpoint (standard CLEAR_FEATURE ENDPOINT_HALT), as alphawire
     *  and tethr do after an error. Caller should not be mid-transaction. */
    public String clearHalts() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder("clear halt:");
            for (UsbEndpoint ep : new UsbEndpoint[]{in, out, intr}) {
                if (ep == null) continue;
                int r = conn.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 1000);
                sb.append(' ').append(L.h16(ep.getAddress())).append('=').append(r);
            }
            pending = null;
            L.i(sb.toString());
            return sb.toString();
        } finally { lock.unlock(); }
    }

    /** PTP class request: Get Device Status (0x67). */
    public String classStatus(int ifaceId) {
        byte[] b = new byte[64];
        int r = conn.controlTransfer(0xA1, 0x67, 0, ifaceId, b, b.length, 2000);
        String s = "GetDeviceStatus(0x67) -> " + r + (r > 0 ? " " + L.hex(b, 0, r, 64) : "");
        L.i(s);
        return s;
    }

    // ---- LE helpers ----
    public static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    public static void put32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    public static int get16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    public static long get32(byte[] b, int o) { return (get16(b, o) | ((long) get16(b, o + 2) << 16)) & 0xFFFFFFFFL; }

    public static byte[] le(long v, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    public static String opName(int op) {
        switch (op) {
            case 0x1001: return "GetDeviceInfo";
            case 0x1002: return "OpenSession";
            case 0x1003: return "CloseSession";
            case 0x1004: return "GetStorageIDs";
            case 0x1008: return "GetObjectInfo";
            case 0x1009: return "GetObject";
            case 0x101B: return "GetPartialObject";
            case 0x9201: return "SDIO_Connect";
            case 0x9202: return "SDIO_GetExtDeviceInfo";
            case 0x9205: return "SDIO_SetExtDevicePropValue";
            case 0x9207: return "SDIO_ControlDevice";
            case 0x9209: return "SDIO_GetAllExtDevicePropInfo";
            case 0x920A: return "SDIO_GetCautionInfo";
            case 0x920B: return "SDIO_RequestInhibitReason";
            case 0x920C: return "SDIO_FreeObjectHandle";
            case 0x920D: return "SDIO_GetFocalMarkerInfo";
            case 0x9210: return "SDIO_OpenSession";
            case 0x9215: return "SDIO_GetDisplayStringList";
            default: return "op";
        }
    }

    public static String respName(int c) {
        switch (c) {
            case 0x2001: return "OK";
            case 0x2002: return "GeneralError";
            case 0x2003: return "SessionNotOpen";
            case 0x2004: return "InvalidTransactionID";
            case 0x2005: return "OperationNotSupported";
            case 0x2006: return "ParameterNotSupported";
            case 0x2007: return "IncompleteTransfer";
            case 0x2009: return "InvalidObjectHandle";
            case 0x200A: return "DevicePropNotSupported";
            case 0x200F: return "AccessDenied";
            case 0x2019: return "DeviceBusy";
            case 0x201B: return "InvalidDevicePropFormat";
            case 0x201C: return "InvalidDevicePropValue";
            case 0x201D: return "InvalidParameter";
            case 0x201E: return "SessionAlreadyOpen";
            case 0x201F: return "TransactionCancelled";
            case 0: return "none";
            default: return (c & 0xF000) == 0xA000 ? "Vendor" : "?";
        }
    }
}
