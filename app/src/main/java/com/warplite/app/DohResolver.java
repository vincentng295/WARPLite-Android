package com.warplite.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * DNS over HTTPS (RFC 8484, POST of the wire format) to Cloudflare. Tries the endpoints in order and remembers the
 * one that worked, so a blocked host name falls back to the IP form without waiting every time.
 * No Android dependency. This process is outside the VPN, so these requests use the real network.
 */
final class DohResolver implements Socks5Server.DnsResolver {

    private final String[] urls;
    private volatile int preferred;

    DohResolver(String... urls) {
        if (urls.length == 0) throw new IllegalArgumentException("no endpoint");
        this.urls = urls;
    }

    @Override
    public byte[] resolve(byte[] query) throws IOException {
        IOException last = null;
        for (int k = 0; k < urls.length; k++) {
            int i = (preferred + k) % urls.length;
            try {
                byte[] r = post(urls[i], query);
                preferred = i;
                return r;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static byte[] post(String url, byte[] query) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            try (OutputStream os = c.getOutputStream()) {
                os.write(query);
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("DoH HTTP " + code);
            byte[] r = read(c.getInputStream());
            if (r.length < 12) throw new IOException("DoH: short answer");
            r[0] = query[0];                                   // the answer carries the id of the query we got
            r[1] = query[1];
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0 && out.size() < 65535) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
