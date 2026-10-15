package dev.g992.rcviewer;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Passive reader for dji_link's DUML telemetry service on TCP 40007.
 * Mirrors rc_read.main(): connect, send the version-query keepalive every 100 ms,
 * read with a 200 ms timeout, unwrap frames, decode 0x06/0xAE.
 * The keepalive is the ONLY thing ever written to the socket.
 */
final class RcReader extends Thread {
    private static final String TAG = "RcReader";
    /** Tried in order. dji_link binds 0.0.0.0:40007, so loopback works on-device. */
    static final String[] HOSTS = {"127.0.0.1", "192.168.3.180"};
    private static final long KEEPALIVE_MS = 100;   // rc_read: "if now - last_ka > 0.1"
    private static final int READ_TIMEOUT_MS = 200;  // rc_read: s.settimeout(0.2)

    interface Listener {
        void onStatus(String status);
        void onState(Duml.RcState state, long packetCount, double rateHz);
        /** Every CRC-valid packet the RC pushes (passive inspection only). */
        void onPacket(Duml.Packet p);
        /** Established session was reset/closed by the peer (another client took over). */
        void onTakenOver(String host, String reason);
    }

    private final Listener listener;
    private volatile boolean running = true;
    private volatile Socket socket;

    RcReader(Listener l) {
        super("RcReader");
        listener = l;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        Socket s = socket;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
        interrupt();
    }

    @Override
    public void run() {
        long backoff = 1000;
        while (running) {
            for (String host : HOSTS) {
                if (!running) return;
                boolean connected = false;
                try {
                    connected = session(host);
                } catch (Exception e) {
                    if (!running) return;
                    Log.w(TAG, "session " + host + " ended: " + e);
                    if (sessionConnected) {
                        // dji_link's 40007 service is effectively single-client: a new
                        // connection resets the previous one. If we were connected and got
                        // reset/closed, another client (normally DJI Fly) took over. Do NOT
                        // fight it with a reconnect loop - stop and let the user decide.
                        listener.onTakenOver(host, e.getMessage());
                        return;
                    }
                    listener.onStatus("connect " + host + " failed: " + e.getMessage());
                }
                if (connected) return;
            }
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(backoff * 2, 10000);
        }
    }

    private volatile boolean sessionConnected;

    /** @return true if ended because shutdown() was called. */
    private boolean session(String host) throws Exception {
        sessionConnected = false;
        listener.onStatus("connecting " + host + ":" + Duml.PORT + " ...");
        Socket s = new Socket();
        socket = s;
        try {
            s.connect(new InetSocketAddress(host, Duml.PORT), 5000);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            sessionConnected = true;
            listener.onStatus("connected " + host + ":" + Duml.PORT);
            Log.i(TAG, "connected " + host + ":" + Duml.PORT);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            byte[] buf = new byte[64 * 1024];
            int len = 0;
            byte[] rd = new byte[4096];
            List<byte[]> bodies = new ArrayList<>();
            int seq = 1;
            long lastKa = 0;
            long count = 0;
            long rateT0 = System.nanoTime();
            long rateN = 0;
            double rate = 0;
            long lastData = System.currentTimeMillis();

            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastKa > KEEPALIVE_MS) {
                    out.write(Duml.keepaliveFrame(seq));
                    out.flush();
                    seq = (seq + 1) & 0xFFFF;
                    lastKa = now;
                }
                int n;
                try {
                    n = in.read(rd);
                } catch (SocketTimeoutException te) {
                    if (now - lastData > 5000) throw new Exception("no data for 5 s");
                    continue;
                }
                if (n < 0) throw new Exception("EOF");
                lastData = now;
                if (len + n > buf.length) len = 0; // runaway garbage: drop buffer
                System.arraycopy(rd, 0, buf, len, n);
                len += n;

                bodies.clear();
                int consumed = Duml.frames(buf, len, bodies);
                if (consumed > 0) {
                    System.arraycopy(buf, consumed, buf, 0, len - consumed);
                    len -= consumed;
                }
                Duml.RcState latest = null;
                for (byte[] body : bodies) {
                    for (Duml.Packet p : Duml.parse(body)) {
                        listener.onPacket(p);
                        if (p.set == 0x06 && p.id == 0xAE) {
                            Duml.RcState st = Duml.decode(p.payload);
                            if (st != null) { latest = st; count++; rateN++; }
                        }
                    }
                }
                long t = System.nanoTime();
                if (t - rateT0 >= 1_000_000_000L) {
                    rate = rateN * 1e9 / (t - rateT0);
                    rateN = 0;
                    rateT0 = t;
                }
                if (latest != null) listener.onState(latest, count, rate);
            }
            return true;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
            socket = null;
        }
    }
}
