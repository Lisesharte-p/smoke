package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 智慧农业平台 — 静态 Web 服务器
 *
 * 作用：把 frontend/ 目录下的前端页面发布到 HTTP，启动后用浏览器直接访问。
 * 与 EchoServer（纯回显测试）不同，这里是真正的页面服务器。
 * 页面路由逐个注册在 PAGES 表里，新增页面只需加一行。
 *
 * 零依赖：只用 JDK 自带类（JDK 8+ 即可），不需要 Maven / Gradle。
 *
 * 运行（在项目根目录 D:\smoke 下）：
 *   编译  javac -encoding UTF-8 -d out/production/smoke server/WebServer.java server/Api.java server/Json.java
 *   运行  java -cp "out/production/smoke;lib/mysql-connector-j-8.0.33.jar" server.WebServer          # 默认 8080 端口
 *         java -cp "out/production/smoke;lib/mysql-connector-j-8.0.33.jar" server.WebServer 9090     # 或指定端口
 *   访问  http://localhost:8080/                                 # 数据总览页（/api/* 需要 mysql 驱动在 classpath）
 */
public class WebServer {

    private static final int DEFAULT_PORT = 8080;

    /** 前端页面所在目录（相对项目根目录） */
    private static final Path FRONTEND = Paths.get("frontend").toAbsolutePath().normalize();

    /** 页面路由：URL 路径 -> 页面文件名（页面逐个注册，新增页面加一行即可） */
    private static final Map<String, String> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("/", "index.html");              // 数据总览（默认首页）
        PAGES.put("/index.html", "index.html");
        PAGES.put("/login.html", "login.html");    // 登录
        PAGES.put("/monitoring.html", "monitoring.html"); // 数据监测
        PAGES.put("/history.html", "history.html"); // 历史趋势
        PAGES.put("/map.html", "map.html"); // 重庆巡田地图
        PAGES.put("/camera.html", "camera.html"); // 视频监控
        PAGES.put("/control.html", "control.html"); // 设备控制
        PAGES.put("/alarm.html", "alarm.html");     // 告警管理
        PAGES.put("/assistant.html", "assistant.html"); // 智能问答
        PAGES.put("/devices.html", "devices.html"); // 设备管理
        PAGES.put("/review.html", "review.html");   // 注册审核
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WebServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        // 确保对话历史表、知识库表、阈值字段存在（幂等，多个环境都能跑）
        DBUtil.ensureChatTables();
        DBUtil.ensureKnowledgeTables();
        DBUtil.ensureThresholdColumns();
        DBUtil.ensureDeviceCameraColumns();

        // 加载智能问答知识库（RAG 检索用，启动时预载入内存）
        Rag.init();

        // 启动板子数据采集器（后台守护线程，周期读板子数据写入 sensor_data）
        BoardCollector.start();

        System.out.println("=============================================");
        System.out.println("  WebServer 已启动");
        System.out.println("  监听地址: http://localhost:" + port);
        for (Map.Entry<String, String> e : PAGES.entrySet()) {
            String url = "/".equals(e.getKey()) ? "" : e.getKey().substring(1);
            System.out.println("  页面:     http://localhost:" + port + "/" + url);
        }
        System.out.println("  停止:     按 Ctrl+C");
        System.out.println("=============================================");
    }

    /** 处理所有请求：业务 API 优先，再匹配页面路由 / 静态资源，否则 404 */
    private static void handle(HttpExchange exchange) throws IOException {
        // 0. 业务 API（/api/*），返回 JSON
        if (Api.handle(exchange)) return;

        if (!"GET".equals(exchange.getRequestMethod())) {
            // 静态页面只支持 GET，其它方法一律 405
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        // 1. 页面路由（/ 或 /xxx.html）
        String page = PAGES.get(path);
        if (page != null) {
            sendFile(exchange, page, "text/html; charset=utf-8");
            return;
        }

        // 2. 静态资源（assets 下的 css / js 等）
        if (path.startsWith("/assets/")) {
            sendFile(exchange, path.substring(1), mimeType(path));
            return;
        }

        // 3. 其它路径：404
        sendText(exchange, 404, "404 Not Found: " + path);
    }

    /** 读取文件返回给浏览器；文件不存在或越出前端目录时返回 404 */
    private static void sendFile(HttpExchange exchange, String relative, String contentType) throws IOException {
        Path file = FRONTEND.resolve(relative).normalize();
        // 防路径穿越：解析后必须仍在前端目录内
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "404 Not Found: " + relative);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // 开发环境：禁止浏览器缓存页面/脚本，改完刷新即可生效（避免一直用旧 api.js）
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 返回纯文本响应（错误提示等） */
    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 根据文件后缀返回 Content-Type */
    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
}
