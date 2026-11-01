package com.ultikits.ulticloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Loopback HTTP stub that replays the copied contract fixtures. Every request is
 * recorded; a request with no queued response is answered 599 and counted as
 * unexpected, so a test can assert that nothing else was called.
 */
public final class StubServer implements AutoCloseable {

    /** One recorded request. */
    public static final class Recorded {
        public final String method;
        public final String path;
        public final String query;
        public final Map<String, String> headers;
        public final byte[] body;

        Recorded(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class Reply {
        final int status;
        final String body;
        final Map<String, String> headers;

        Reply(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    private final HttpServer server;
    private final Map<String, LinkedList<Reply>> replies = new HashMap<String, LinkedList<Reply>>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<Recorded>());
    private final List<String> unexpected = Collections.synchronizedList(new ArrayList<String>());

    private final boolean tls;

    public StubServer() throws IOException {
        this(false);
    }

    /**
     * @param tls serve HTTPS with the self-signed test certificate in {@code tls/stub-test-only.p12}
     */
    public StubServer(boolean tls) throws IOException {
        this.tls = tls;
        if (tls) {
            com.sun.net.httpserver.HttpsServer https =
                    com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(testTlsContext()));
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                StubServer.this.handle(ex);
            }
        });
        server.start();
    }

    /** @return the stub origin, for example {@code http://127.0.0.1:41234} */
    public String baseUrl() {
        return (tls ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues a reply for {@code METHOD /path} (query ignored). */
    public StubServer on(String method, String path, int status, String body) {
        return on(method, path, status, body, Collections.<String, String>emptyMap());
    }

    public synchronized StubServer on(String method, String path, int status, String body, Map<String, String> headers) {
        String key = method + " " + path;
        LinkedList<Reply> queue = replies.get(key);
        if (queue == null) {
            queue = new LinkedList<Reply>();
            replies.put(key, queue);
        }
        queue.add(new Reply(status, body, headers));
        return this;
    }

    public List<Recorded> requests() {
        return new ArrayList<Recorded>(requests);
    }

    public List<String> unexpected() {
        return new ArrayList<String>(unexpected);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        Map<String, String> headers = new HashMap<String, String>();
        for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
            headers.put(h.getKey().toLowerCase(), h.getValue().get(0));
        }
        byte[] body = readAll(ex.getRequestBody());
        requests.add(new Recorded(method, path, ex.getRequestURI().getRawQuery(), headers, body));
        Reply reply;
        synchronized (this) {
            LinkedList<Reply> queue = replies.get(method + " " + path);
            reply = queue == null || queue.isEmpty() ? null : queue.removeFirst();
        }
        if (reply == null) {
            unexpected.add(method + " " + path);
            reply = new Reply(599, "{}", Collections.<String, String>emptyMap());
        }
        byte[] out = reply.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        for (Map.Entry<String, String> h : reply.headers.entrySet()) {
            ex.getResponseHeaders().set(h.getKey(), h.getValue());
        }
        ex.sendResponseHeaders(reply.status, out.length == 0 ? -1 : out.length);
        OutputStream os = ex.getResponseBody();
        os.write(out);
        os.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------ fixtures

    /**
     * TLS context holding the self-signed test certificate, used both as the server key
     * and as the client's only trust anchor. Test-only material; never used outside tests.
     */
    public static javax.net.ssl.SSLContext testTlsContext() {
        try {
            char[] pass = "changeit".toCharArray();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            InputStream in = StubServer.class.getResourceAsStream("/tls/stub-test-only.p12");
            try {
                ks.load(in, pass);
            } finally {
                in.close();
            }
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Root of the copied contract export in the test resources. */
    public static File contractRoot() {
        try {
            return new File(StubServer.class.getResource("/ulticloud/developer-client/1.0.0/MANIFEST.json").toURI())
                    .getParentFile();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads {@code fixtures/<relative>} from the copied export. */
    public static String fixture(String relative) throws IOException {
        return new String(Files.readAllBytes(new File(contractRoot(), "fixtures/" + relative).toPath()),
                StandardCharsets.UTF_8);
    }

    /** Reads a fixture and points its {@code https://api.ultikits.com} URLs at this stub. */
    public String fixtureHere(String relative) throws IOException {
        return fixture(relative).replace("https://api.ultikits.com", baseUrl());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
