package com.satz.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny protobuf encoder/decoder - just enough for the Android TV remote protocol. */
public final class Proto {
    private Proto() {}

    /** Message writer. */
    public static final class W {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
        }

        private void tag(int field, int wire) { raw(((long) field << 3) | wire); }

        public W v(int field, long value) { tag(field, 0); raw(value); return this; }

        public W b(int field, byte[] data) {
            tag(field, 2);
            raw(data.length);
            o.write(data, 0, data.length);
            return this;
        }

        public W s(int field, String s) { return b(field, s.getBytes(StandardCharsets.UTF_8)); }

        public W m(int field, W msg) { return b(field, msg.bytes()); }

        public byte[] bytes() { return o.toByteArray(); }
    }

    /** Parsed message: field number -> values (Long for varints, byte[] for length-delimited). */
    public static final class M {
        private final Map<Integer, List<Object>> f = new HashMap<>();

        public static M parse(byte[] d) throws IOException {
            M m = new M();
            int[] p = {0};
            while (p[0] < d.length) {
                long key = readVarint(d, p);
                int field = (int) (key >>> 3);
                int wire = (int) (key & 7);
                Object val;
                switch (wire) {
                    case 0: val = readVarint(d, p); break;
                    case 1: p[0] += 8; continue;
                    case 2: {
                        int len = (int) readVarint(d, p);
                        if (len < 0 || p[0] + len > d.length) throw new IOException("bad length");
                        byte[] b = new byte[len];
                        System.arraycopy(d, p[0], b, 0, len);
                        p[0] += len;
                        val = b;
                        break;
                    }
                    case 5: p[0] += 4; continue;
                    default: throw new IOException("bad wire type " + wire);
                }
                List<Object> l = m.f.get(field);
                if (l == null) { l = new ArrayList<>(); m.f.put(field, l); }
                l.add(val);
            }
            return m;
        }

        public boolean has(int field) { return f.containsKey(field); }

        public long i(int field, long def) {
            List<Object> l = f.get(field);
            if (l == null || !(l.get(0) instanceof Long)) return def;
            return (Long) l.get(0);
        }

        public byte[] b(int field) {
            List<Object> l = f.get(field);
            if (l == null || !(l.get(0) instanceof byte[])) return null;
            return (byte[]) l.get(0);
        }

        public String s(int field) {
            byte[] b = b(field);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        public M m(int field) {
            byte[] b = b(field);
            if (b == null) return null;
            try { return parse(b); } catch (IOException e) { return null; }
        }
    }

    private static long readVarint(byte[] d, int[] p) throws IOException {
        long r = 0;
        int shift = 0;
        while (true) {
            if (p[0] >= d.length) throw new IOException("truncated varint");
            int b = d[p[0]++] & 0xFF;
            r |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return r;
            shift += 7;
            if (shift > 63) throw new IOException("varint too long");
        }
    }

    /** Writes a varint length-prefixed frame. */
    public static void writeFrame(OutputStream out, byte[] msg) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        long v = msg.length;
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
        o.write(msg, 0, msg.length);
        out.write(o.toByteArray());
        out.flush();
    }

    /** Reads one varint length-prefixed frame. */
    public static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("frame too long");
        }
        if (len > 1 << 20) throw new IOException("frame too large");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, (int) len - off);
            if (n < 0) throw new EOFException("connection closed");
            off += n;
        }
        return buf;
    }
}
