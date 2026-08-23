package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

/**
 * 小型 Echo 服务器
 *
 * 用途：验证 HTTP 服务能启动、8080 端口能监听、请求能正常收发。
 * 正式的业务接口（浓度查询、告警等）后面再在这个基础上扩展，或整体迁移到 Spring Boot。
 *
 * 零依赖：只用 JDK 自带类（JDK 8+ 即可），不需要 Maven / Gradle。
 *
 * 运行（在项目根目录 D:\smoke 下）：
 *   编译  javac -encoding UTF-8 server/EchoServer.java
 *   运行  java server.EchoServer                # 默认 8080 端口
 *         java server.EchoServer 9090           # 或指定端口
 *   测试  curl "http://localhost:8080/echo?msg=hello"
 */
public class EchoServer {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // 注册根路径处理器：所有请求都走 handleRequest
        server.createContext("/", EchoServer::handleRequest);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("=============================================");
        System.out.println("  EchoServer 已启动");
        System.out.println("  监听地址: http://localhost:" + port);
        System.out.println("  测试 GET:  curl \"http://localhost:" + port + "/echo?msg=hello\"");
        System.out.println("  测试 POST: curl -X POST -d \"hello body\" http://localhost:" + port + "/echo");
        System.out.println("  停止:      按 Ctrl+C");
        System.out.println("=============================================");
    }

    /**
     * 处理所有 HTTP 请求，把请求信息原样回显成一个 JSON。
     */
    private static void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body = readBody(exchange);

        // 手动拼 JSON（暂不引入 JSON 库）
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"method\":\"").append(escape(method)).append("\",");
        json.append("\"path\":\"").append(escape(path)).append("\",");
        json.append("\"query\":\"").append(escape(query == null ? "" : query)).append("\",");
        json.append("\"body\":\"").append(escape(body)).append("\",");
        json.append("\"time\":\"").append(LocalDateTime.now()).append('"');
        json.append('}');

        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 读取请求体（兼容 JDK 8，InputStream.readAllBytes 是 9+ 才有） */
    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /** 转义特殊字符，避免破坏 JSON 结构 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
