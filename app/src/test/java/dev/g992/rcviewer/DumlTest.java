package dev.g992.rcviewer;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Golden vectors generated from ~/rm330/rc_read.py + duml.py. */
public class DumlTest {
    static byte[] h(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test public void keepaliveMatchesPython() {
        assertEquals("55 cc 30 75 0d 00 00 00 55 0d 04 33 02 06 01 00 00 00 01 6e f1",
                Duml.hex(Duml.keepaliveFrame(1)));
        assertArrayEquals(h("55cc30750d000000550d0433020634120000013cf9"), Duml.keepaliveFrame(0x1234));
    }

    @Test public void framedPacketDecodesLikePython() {
        byte[] pkt = h("551e048a060207000006ae200105000094066c010004ff03100502039037");
        byte[] hdr = {0x55, (byte) 0xCC, 0x30, 0x75, (byte) pkt.length, 0, 0, 0};
        byte[] all = Duml.concat(new byte[]{1, 2, 3}, hdr, pkt, hdr, pkt, new byte[]{0x55, (byte) 0xCC});
        // feed the stream split at every possible point and check reassembly
        for (int split = 0; split <= all.length; split++) {
            byte[] buf = new byte[256];
            int len = 0, got = 0;
            for (int part = 0; part < 2; part++) {
                int from = part == 0 ? 0 : split, to = part == 0 ? split : all.length;
                System.arraycopy(all, from, buf, len, to - from);
                len += to - from;
                List<byte[]> bodies = new ArrayList<>();
                int c = Duml.frames(buf, len, bodies);
                System.arraycopy(buf, c, buf, 0, len - c);
                len -= c;
                for (byte[] body : bodies) for (Duml.Packet p : Duml.parse(body)) {
                    assertEquals(0x06, p.set);
                    assertEquals(0xAE, p.id);
                    Duml.RcState st = Duml.decode(p.payload);
                    assertArrayEquals(new int[]{-1, 0, 660, -660, 272, -254}, st.axes);
                    assertEquals("Normal", st.mode);
                    // RTH, CamHalf, CamFull, Rec (C1/C2 dropped; they never appear on 40007)
                    assertArrayEquals(new boolean[]{true, false, false, true}, st.buttons);
                    got++;
                }
            }
            assertEquals("split " + split, 2, got);
        }
    }

    @Test public void badCrcRejected() {
        byte[] pkt = h("551e048a060207000006ae200105000094066c010004ff03100502039037");
        pkt[15] ^= 1;
        assertTrue(Duml.parse(pkt).isEmpty());
        assertNull(Duml.decode(new byte[16]));
    }
}
