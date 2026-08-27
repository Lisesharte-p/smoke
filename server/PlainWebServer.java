package server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blocking-socket fallback for environments where JDK HttpServer cannot create
 * its internal selector pipe. It keeps the same static routes and Api.handle().
 */
public class PlainWebServer {
    private static final int DEFAULT_PORT = 8080;
    private static final Path FRONTEND = Paths.get("frontend").toAbsolutePath().normalize();
    private static final Map<String, String> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("/", "index.html");
        PAGES.put("/index.html", "index.html");
        PAGES.put("/login.html", "login.html");
        PAGES.put("/monitoring.html", "monitoring.html");
        PAGES.put("/history.html", "history.html");
        PAGES.put("/map.html", "map.html");
        PAGES.put("/camera.html", "camera.html");
        PAGES.put("/control.html", "control.html");
        PAGES.put("/alarm.html", "alarm.html");
        PAGES.put("/assistant.html", "assistant.html");
        PAGES.put("/devices.html", "devices.html");
        PAGES.put("/review.html", "review.html");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(port));

        DBUtil.ensureChatTables();
        DBUtil.ensureKnowledgeTables();
        DBUtil.ensureThresholdColumns();
        DBUtil.ensureDeviceCameraColumns();
        DBUtil.ensureAlarmHandleColumns();
        DBUtil.ensureDetectionTables();
        Rag.init();
        BoardCollector.start();

        System.out.println("=============================================");
        System.out.println("  PlainWebServer 已启动");
        System.out.println("  监听地址: http://localhost:" + port);
        System.out.println("  停止:     结束 java 进程");
        System.out.println("=============================================");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        while (true) {
            Socket socket = server.accept();
            pool.submit(() -> handle(socket));
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Request req = readRequest(in);
            if (req == null) return;

            SimpleExchange ex = new SimpleExchange(req, out, s);
            if (Api.handle(ex)) {
                ex.close();
                return;
            }
            handleStatic(ex);
            ex.close();
        } catch (Exception e) {
            System.out.println("[PlainWebServer] 请求处理失败: " + e.getMessage());
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) return null;

        Headers headers = new Headers();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) headers.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }

        int len = 0;
        String contentLength = headers.getFirst("Content-Length");
        if (contentLength != null && !contentLength.isEmpty()) {
            try {
                len = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException ignored) {
                len = 0;
            }
        }
        byte[] body = readBytes(in, len);
        URI uri = URI.create(parts[1]);
        String protocol = parts.length >= 3 ? parts[2] : "HTTP/1.1";
        return new Request(parts[0], uri, protocol, headers, body);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static byte[] readBytes(InputStream in, int len) throws IOException {
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off == len ? body : java.util.Arrays.copyOf(body, off);
    }

    private static void handleStatic(SimpleExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String page = PAGES.get(path);
        if (page != null) {
            sendFile(ex, page, "text/html; charset=utf-8");
            return;
        }
        if (path.startsWith("/assets/")) {
            sendFile(ex, path.substring(1), mimeType(path));
            return;
        }
        sendText(ex, 404, "404 Not Found: " + path);
    }

    private static void sendFile(HttpExchange ex, String relative, String contentType) throws IOException {
        Path file = FRONTEND.resolve(relative).normalize();
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            sendText(ex, 404, "404 Not Found: " + relative);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static class Request {
        final String method;
        final URI uri;
        final String protocol;
        final Headers headers;
        final byte[] body;

        Request(String method, URI uri, String protocol, Headers headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
            this.headers = headers;
            this.body = body;
        }
    }

    private static class SimpleExchange extends HttpExchange {
        private final Request req;
        private final OutputStream rawOut;
        private final Socket socket;
        private final Headers responseHeaders = new Headers();
        private InputStream requestBody;
        private OutputStream responseBody;
        private int responseCode = -1;
        private boolean headersSent = false;

        SimpleExchange(Request req, OutputStream rawOut, Socket socket) {
            this.req = req;
            this.rawOut = rawOut;
            this.socket = socket;
            this.requestBody = new ByteArrayInputStream(req.body);
        }

        public Headers getRequestHeaders() { return req.headers; }
        public Headers getResponseHeaders() { return responseHeaders; }
        public URI getRequestURI() { return req.uri; }
        public String getRequestMethod() { return req.method; }
        public HttpContext getHttpContext() { return null; }
        public InputStream getRequestBody() { return requestBody; }
        public OutputStream getResponseBody() { return responseBody; }
        public InetSocketAddress getRemoteAddress() { return (InetSocketAddress) socket.getRemoteSocketAddress(); }
        public int getResponseCode() { return responseCode; }
        public InetSocketAddress getLocalAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        public String getProtocol() { return req.protocol; }
        public Object getAttribute(String name) { return null; }
        public void setAttribute(String name, Object value) {}
        public void setStreams(InputStream i, OutputStream o) {
            requestBody = i;
            responseBody = o;
        }
        public HttpPrincipal getPrincipal() { return null; }

        public void sendResponseHeaders(int status, long length) throws IOException {
            if (headersSent) return;
            responseCode = status;
            headersSent = true;
            responseHeaders.set("Connection", "close");
            if (length > 0) responseHeaders.set("Content-Length", String.valueOf(length));
            rawOut.write(("HTTP/1.1 " + status + " " + reason(status) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (Map.Entry<String, java.util.List<String>> e : responseHeaders.entrySet()) {
                for (String v : e.getValue()) {
                    rawOut.write((e.getKey() + ": " + v + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            rawOut.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            rawOut.flush();
            responseBody = new FilterOutputStream(rawOut) {
                public void close() throws IOException {
                    flush();
                }
            };
        }

        public void close() {
            try {
                if (!headersSent) sendResponseHeaders(204, -1);
                if (responseBody != null) responseBody.flush();
            } catch (IOException ignored) {
            }
        }

        private String reason(int status) {
            if (status == 200) return "OK";
            if (status == 204) return "No Content";
            if (status == 206) return "Partial Content";
            if (status == 400) return "Bad Request";
            if (status == 404) return "Not Found";
            if (status == 405) return "Method Not Allowed";
            if (status == 500) return "Internal Server Error";
            return "OK";
        }
    }
}
