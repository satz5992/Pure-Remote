package com.satz.tvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLSocket;

/** Pairing (port 6467): the TV shows a 6-character code which the user types on the phone. */
public final class PairingClient implements Closeable {
    public static final int PORT = 6467;
    private static final int HEX = 3, ROLE_INPUT = 1;

    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;

    /** Connects and asks the TV to display a code. Blocking - call off the UI thread. */
    public void start(String host, String clientName) throws IOException { start(host, PORT, clientName); }

    public void start(String host, int port, String clientName) throws IOException {
        sock = Tls.connect(host, port, 8000);
        sock.setSoTimeout(20000);
        in = sock.getInputStream();
        out = sock.getOutputStream();
        send(10, new Proto.W().s(1, "atvremote").s(2, clientName));
        expect();
        Proto.W enc = new Proto.W().v(1, HEX).v(2, 6);
        send(20, new Proto.W().m(1, enc).v(3, ROLE_INPUT));
        expect();
        send(30, new Proto.W().m(1, new Proto.W().v(1, HEX).v(2, 6)).v(2, ROLE_INPUT));
        expect();
        sock.setSoTimeout(0);
    }

    /** Sends the code typed by the user. Blocking. */
    public void finish(String code) throws Exception {
        X509Certificate server = (X509Certificate) sock.getSession().getPeerCertificates()[0];
        byte[] secret = Tls.pairingSecret(Tls.clientCert(), server, code.trim());
        sock.setSoTimeout(15000);
        send(40, new Proto.W().b(1, secret));
        expect();
    }

    private void send(int field, Proto.W payload) throws IOException {
        Proto.writeFrame(out, new Proto.W().v(1, 2).v(2, 200).m(field, payload).bytes());
    }

    private Proto.M expect() throws IOException {
        Proto.M m = Proto.M.parse(Proto.readFrame(in));
        long status = m.i(2, 0);
        if (status != 200) {
            if (status == 402) throw new IOException("Wrong code - try again");
            throw new IOException("TV refused pairing (status " + status + ")");
        }
        return m;
    }

    @Override public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
    }
}
