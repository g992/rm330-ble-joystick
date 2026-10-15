package dev.g992.rcviewer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Faithful Java port of ~/rm330/duml.py (build/parse/CRCs) and the TCP framing +
 * 0x06/0xAE decoder from ~/rm330/rc_read.py.
 *
 * SAFETY: the only packet this class can build is the General Version Query
 * keepalive (cmd_set 0x00, cmd_id 0x01) exactly as rc_read.py sends it. There is
 * intentionally no generic "build any command" entry point.
 */
public final class Duml {
    private Duml() {}

    /** TCP transport magic used by dji_link on port 40007. */
    static final byte[] TCP_MAGIC = {0x55, (byte) 0xCC, 0x30, 0x75};
    public static final int PORT = 40007;
    static final int WRAP_CENTER = 4;

    // ---------------------------------------------------------------- CRCs (duml.py)

    static int crc8(byte[] d, int off, int len) {
        int crc = 0x77;
        for (int k = 0; k < len; k++) {
            crc ^= d[off + k] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8C : crc >>> 1;
            }
        }
        return crc & 0xFF;
    }

    static int crc16(byte[] d, int off, int len) {
        int crc = 0x3692;
        for (int k = 0; k < len; k++) {
            crc ^= d[off + k] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8408 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    // ---------------------------------------------------------------- keepalive

    /**
     * rc_read.keepalive(seq): duml.build(receiver=6, cmd_set=0, cmd_id=1, seq=seq,
     * sender=2, cmd_type=0), wrapped as MAGIC + LE32(len) + pkt.
     */
    public static byte[] keepaliveFrame(int seq) {
        final int sender = 2, receiver = 6, cmdType = 0, cmdSet = 0x00, cmdId = 0x01;
        final int length = 13; // no payload
        byte[] pkt = new byte[length];
        pkt[0] = 0x55;
        pkt[1] = (byte) (length & 0xFF);
        pkt[2] = (byte) (((length >> 8) & 0x03) | (1 << 2));
        pkt[3] = (byte) crc8(pkt, 0, 3);
        pkt[4] = (byte) sender;
        pkt[5] = (byte) receiver;
        pkt[6] = (byte) (seq & 0xFF);
        pkt[7] = (byte) ((seq >> 8) & 0xFF);
        pkt[8] = (byte) cmdType;
        pkt[9] = (byte) cmdSet;
        pkt[10] = (byte) cmdId;
        int c = crc16(pkt, 0, length - 2);
        pkt[11] = (byte) (c & 0xFF);
        pkt[12] = (byte) ((c >> 8) & 0xFF);

        byte[] out = new byte[8 + length];
        System.arraycopy(TCP_MAGIC, 0, out, 0, 4);
        out[4] = (byte) (length & 0xFF);
        out[5] = (byte) ((length >> 8) & 0xFF);
        out[6] = (byte) ((length >> 16) & 0xFF);
        out[7] = (byte) ((length >>> 24) & 0xFF);
        System.arraycopy(pkt, 0, out, 8, length);
        return out;
    }

    // ---------------------------------------------------------------- TCP framing

    /**
     * Port of rc_read.frames(): extracts complete MAGIC+LE32 frames from the start of
     * {@code buf} (bytes [0, len)), appends their DUML bodies to {@code out}, and
     * returns the index of the first unconsumed byte (start of a partial frame, or
     * len if none). Unlike the Python generator (whose "return buf[i:]" remainder is
     * swallowed by StopIteration), the caller here really keeps the remainder.
     */
    static int frames(byte[] buf, int len, List<byte[]> out) {
        int i = 0;
        while (true) {
            int j = indexOf(buf, len, TCP_MAGIC, i);
            if (j < 0) {
                // Keep up to 3 trailing bytes in case the magic is split across reads.
                return Math.max(i, len - 3);
            }
            if (j + 8 > len) return j;
            long ln = (buf[j + 4] & 0xFFL) | ((buf[j + 5] & 0xFFL) << 8)
                    | ((buf[j + 6] & 0xFFL) << 16) | ((buf[j + 7] & 0xFFL) << 24);
            if (ln > 1 << 20) { // garbage length; resync past this magic
                i = j + 1;
                continue;
            }
            if (j + 8 + ln > len) return j;
            byte[] body = new byte[(int) ln];
            System.arraycopy(buf, j + 8, body, 0, (int) ln);
            out.add(body);
            i = j + 8 + (int) ln;
        }
    }

    private static int indexOf(byte[] buf, int len, byte[] pat, int from) {
        outer:
        for (int i = Math.max(0, from); i + pat.length <= len; i++) {
            for (int k = 0; k < pat.length; k++) {
                if (buf[i + k] != pat[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- DUML parse

    public static final class Packet {
        public final int sender, receiver, seq, type, set, id;
        public final byte[] payload;

        Packet(int sender, int receiver, int seq, int type, int set, int id, byte[] payload) {
            this.sender = sender; this.receiver = receiver; this.seq = seq;
            this.type = type; this.set = set; this.id = id; this.payload = payload;
        }
    }

    /** Port of duml.parse(): yields CRC-valid DUML v1 packets, skipping garbage. */
    static List<Packet> parse(byte[] buf) {
        List<Packet> res = new ArrayList<>();
        int i = 0;
        int n = buf.length;
        while (i + 13 <= n) {
            if ((buf[i] & 0xFF) != 0x55) { i++; continue; }
            int length = (buf[i + 1] & 0xFF) | ((buf[i + 2] & 0x03) << 8);
            if (crc8(buf, i, 3) != (buf[i + 3] & 0xFF) || length < 13 || i + length > n) {
                i++;
                continue;
            }
            int got = (buf[i + length - 2] & 0xFF) | ((buf[i + length - 1] & 0xFF) << 8);
            if (crc16(buf, i, length - 2) != got) { i++; continue; }
            byte[] pl = new byte[length - 13];
            System.arraycopy(buf, i + 11, pl, 0, pl.length);
            res.add(new Packet(buf[i + 4] & 0xFF, buf[i + 5] & 0xFF,
                    (buf[i + 6] & 0xFF) | ((buf[i + 7] & 0xFF) << 8),
                    buf[i + 8] & 0xFF, buf[i + 9] & 0xFF, buf[i + 10] & 0xFF, pl));
            i += length;
        }
        return res;
    }

    // ---------------------------------------------------------------- 0x06/0xAE decode

    /** Axis table from rc_read.AXES: name, position-byte, wrap-byte. */
    public static final String[] AXIS_NAMES = {
            "L-horiz", "L-vert", "R-horiz", "R-vert", "ch6", "ch7"};
    // ch6/ch7 (bytes 13/14, 15/16) are labelled "back wheels" by rc_read.py / stiad, but on
    // this RM330 they do NOT track the physical dials: live tests showed slow one-signed ramps
    // (~-44 ceiling) regardless of dial direction, drift at rest, and coupling to stick motion.
    static final int[][] AXIS_BYTES = {
            {11, 12}, {9, 10}, {5, 6}, {7, 8}, {13, 14}, {15, 16}};
    public static final int AX_LH = 0, AX_LV = 1, AX_RH = 2, AX_RV = 3, AX_LW = 4 /* ch6 */, AX_RW = 5 /* ch7 */;

    // rc_read.BTN0 / MODE. C1/C2 (ch1 bits 0x04/0x08) never appear on 40007 and were dropped.
    static final int[] BTN0_MASK = {0x20, 0x80, 0x40, 0x100};
    static final String[] BTN0_NAME = {"RTH", "CamHalf", "CamFull", "Rec"};
    public static final String[] ALL_BUTTONS = {"RTH", "CamHalf", "CamFull", "Rec"};

    public static final class RcState {
        public final int ch0, ch1;
        public final int[] axes = new int[6];
        public final boolean[] buttons = new boolean[4]; // order = ALL_BUTTONS
        public final String mode;
        public final byte[] raw;

        RcState(byte[] pl) {
            raw = pl;
            ch0 = (pl[0] & 0xFF) | ((pl[1] & 0xFF) << 8);
            ch1 = (pl[2] & 0xFF) | ((pl[3] & 0xFF) << 8);
            for (int k = 0; k < 4; k++) buttons[k] = (ch0 & BTN0_MASK[k]) != 0;
            switch (ch1 & 0x03) {
                case 0: mode = "Sport"; break;
                case 1: mode = "Normal"; break;
                case 2: mode = "Cinema"; break;
                default: mode = "?";
            }
            for (int a = 0; a < 6; a++) axes[a] = axis(pl, AXIS_BYTES[a][0], AXIS_BYTES[a][1]);
        }
    }

    /** rc_read.axis(): (pl[wrap] - 4) * 256 + pl[pos], both bytes unsigned. */
    static int axis(byte[] pl, int pos, int wrap) {
        return ((pl[wrap] & 0xFF) - WRAP_CENTER) * 256 + (pl[pos] & 0xFF);
    }

    /** rc_read.decode(): null if payload shorter than 17 bytes. */
    public static RcState decode(byte[] pl) {
        if (pl == null || pl.length < 17) return null;
        return new RcState(pl);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02x ", x & 0xFF));
        return sb.toString().trim();
    }

    @SuppressWarnings("unused")
    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
