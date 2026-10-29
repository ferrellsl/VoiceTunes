package com.ferrellsl.voicetunes;

import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS for the away address when its certificate isn't from a public authority (a self-signed
 * one, or one from the reverse proxy's own authority, e.g. Caddy's "tls internal"). The user
 * picks the certificate file in the settings; it is trusted for the away address's host only,
 * on top of the phone's usual authorities. Every HTTPS connection in the app (the API, songs,
 * pictures) goes through HttpsURLConnection, so setting its default is enough.
 */
public final class Trust {

    private Trust() {}

    /** Reads a certificate file (PEM or DER); throws if it isn't one. */
    public static X509Certificate parse(byte[] file) throws CertificateException {
        String text = new String(file, StandardCharsets.US_ASCII);
        int b = text.indexOf("-----BEGIN CERTIFICATE-----");
        if (b >= 0) {
            int e = text.indexOf("-----END CERTIFICATE-----", b);
            if (e < 0) throw new CertificateException("the certificate is cut short");
            String body = text.substring(b + 27, e).replaceAll("\\s", "");
            file = Base64.decode(body, Base64.DEFAULT);
        }
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(file));
    }

    public static String encode(X509Certificate c) throws CertificateException {
        return Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP);
    }

    /** The certificate saved in the settings, or null. */
    public static X509Certificate saved(SharedPreferences prefs) {
        String s = prefs.getString("awayCert", "");
        if (s.isEmpty()) return null;
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.decode(s, Base64.DEFAULT)));
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 fingerprint, as the reverse proxy or openssl shows it (AB:CD:...). */
    public static String fingerprint(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(s.length() == 0 ? "" : ":").append(String.format("%02X", x));
            return s.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** Sets up the app's HTTPS from the settings (again after they change). */
    public static synchronized void install(SharedPreferences prefs) {
        try {
            X509TrustManager system = manager(null);
            X509Certificate cert = saved(prefs);
            String host = host(prefs.getString("away", ""));
            X509TrustManager tm = system;
            if (cert != null && host != null) {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("away", cert);
                tm = new AwayTrust(system, manager(ks), host);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { tm }, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        } catch (Exception e) {
            // (the phone's usual HTTPS, then)
        }
    }

    private static X509TrustManager manager(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers())
            if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    private static String host(String address) {
        String a = address.trim();
        if (a.isEmpty()) return null;
        if (!a.contains("://")) a = "https://" + a;
        try {
            String h = android.net.Uri.parse(a).getHost();
            return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** The phone's authorities for every host; the user's certificate as well for the away host. */
    private static final class AwayTrust extends X509ExtendedTrustManager {
        private final X509TrustManager system, user;
        private final String host;

        AwayTrust(X509TrustManager system, X509TrustManager user, String host) {
            this.system = system; this.user = user; this.host = host;
        }

        private void check(X509Certificate[] chain, String authType, String peer) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                if (peer == null || !host.equals(peer.toLowerCase(java.util.Locale.ROOT))) throw e;
                user.checkServerTrusted(chain, authType);
            }
        }

        private static String peer(SSLSession s) {
            return s == null ? null : s.getPeerHost();
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain, authType, socket instanceof SSLSocket ? peer(((SSLSocket) socket).getHandshakeSession()) : null);
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain, authType, engine == null ? null : peer(engine.getHandshakeSession()));
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            system.checkServerTrusted(chain, authType);
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("no client certificates");
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("no client certificates");
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("no client certificates");
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }
}
