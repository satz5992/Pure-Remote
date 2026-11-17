package com.satz.tvremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Holds this phone's self-signed client certificate (created once, kept in app storage)
 * and opens TLS connections to the TV. No third-party libraries.
 */
public final class Tls {
    private Tls() {}

    private static X509Certificate cert;
    private static PrivateKey key;
    private static SSLContext ctx;

    public static synchronized void init(File dir) throws Exception {
        if (ctx != null) return;
        File kf = new File(dir, "client.key");
        File cf = new File(dir, "client.crt");
        if (kf.exists() && cf.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(kf.toPath())));
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(cf.toPath())));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            byte[] der = selfSignedCert(kp, "atvremote");
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            key = kp.getPrivate();
            try (FileOutputStream o = new FileOutputStream(kf)) { o.write(key.getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(cf)) { o.write(der); }
        }
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[]{new KM()}, new TrustManager[]{new TrustAll()}, new SecureRandom());
        ctx = c;
    }

    public static X509Certificate clientCert() { return cert; }

    public static SSLSocket connect(String host, int port, int timeoutMs) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        plain.setTcpNoDelay(true);
        plain.setSoTimeout(timeoutMs);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
        s.setUseClientMode(true);
        s.startHandshake();
        return s;
    }

    /** Pairing secret = SHA256(client modulus, client exponent, server modulus, server exponent, code[2:]). */
    public static byte[] pairingSecret(X509Certificate client, X509Certificate server, String code) throws Exception {
        if (code == null || code.length() != 6) throw new IllegalArgumentException("Code must be 6 characters");
        code = code.toUpperCase();
        RSAPublicKey c = (RSAPublicKey) client.getPublicKey();
        RSAPublicKey s = (RSAPublicKey) server.getPublicKey();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(unsigned(c.getModulus()));
        md.update(unsigned(c.getPublicExponent()));
        md.update(unsigned(s.getModulus()));
        md.update(unsigned(s.getPublicExponent()));
        md.update(hex(code.substring(2)));
        byte[] h = md.digest();
        if ((h[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new IllegalArgumentException("Wrong code - check the code on your TV");
        }
        return h;
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        byte[] r = new byte[b.length - i];
        System.arraycopy(b, i, r, 0, r.length);
        return r;
    }

    static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    // ---- minimal DER encoder for a self-signed X.509 v3 certificate ----

    private static final byte[] OID_SHA256_RSA = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] NULL = {0x05, 0x00};

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (n < 0x80) o.write(n);
        else if (n < 0x100) { o.write(0x81); o.write(n); }
        else if (n < 0x10000) { o.write(0x82); o.write(n >> 8); o.write(n & 0xFF); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xFF); o.write(n & 0xFF); }
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    static byte[] selfSignedCert(KeyPair kp, String cn) throws Exception {
        byte[] algId = tlv(0x30, OID_SHA256_RSA, NULL);
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, OID_CN, tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = tlv(0x30,
                tlv(0x17, "240101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{0x02}));
        byte[] serial = tlv(0x02, new BigInteger(62, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = tlv(0x30, version, serial, algId, name, validity, name, kp.getPublic().getEncoded());
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sv = sig.sign();
        byte[] bits = new byte[sv.length + 1];
        System.arraycopy(sv, 0, bits, 1, sv.length);
        return tlv(0x30, tbs, algId, tlv(0x03, bits));
    }

    private static final class KM extends X509ExtendedKeyManager {
        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"c"}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "c"; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "c"; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    /** TVs use self-signed certificates; trust is established by the on-screen pairing code. */
    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
