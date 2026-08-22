package server;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 业务 API：/api/* 路由 + 数据库交互。
 * 前端统一走 window.API（frontend/assets/js/api.js），接口路径和数据形状已定死，
 * 这里按契约实现。所有响应都是 {"code":0,"data":...} 或 {"code":1,"msg":"..."}。
 *
 * 每个接口独立一个方法，路由在 handle() 里分发；以后新增接口加一行即可。
 */
public class Api {

    /** 处理 /api/* 请求，返回 true 表示已处理（false 交给 WebServer 走静态页面） */
    public static boolean handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith("/api/")) return false;
        String method = ex.getRequestMethod();

        try {
            /* ---------- 地块 ---------- */
            if ("GET".equals(method) && path.equals("/api/plots")) {
                ok(ex, plotsJson());
                return true;
            }

            /* ---------- 未匹配：404 ---------- */
            send(ex, 404, "{\"code\":1,\"msg\":" + Json.str("接口不存在: " + method + " " + path) + "}");
        } catch (SQLException e) {
            send(ex, 500, "{\"code\":1,\"msg\":" + Json.str("数据库错误: " + e.getMessage()) + "}");
        } catch (Exception e) {
            // 兜底：任何意外异常都给浏览器一个明确的 JSON 错误，而不是让连接挂起
            send(ex, 500, "{\"code\":1,\"msg\":" + Json.str("服务器内部错误: " + e.getMessage()) + "}");
        }
        return true;
    }

    /* ==================================================================
       地块
       ================================================================== */

    /**
     * GET /api/plots —— 地块列表。
     * 返回每个地块：id,name,crop,area(带亩),temp,humidity,deviceCount,onlineCount。
     * temp/humidity 取该地块最新一条传感器读数；deviceCount/onlineCount 由 device 聚合。
     */
    private static String plotsJson() throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":0,\"data\":[");
        String sql =
                "SELECT p.id, p.name, p.crop, p.area," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id) AS deviceCount," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id AND d.online=1) AS onlineCount," +
                "  (SELECT s.value FROM sensor_data s JOIN device d ON d.id=s.device_id" +
                "    WHERE d.plot_id=p.id AND s.metric='temp' ORDER BY s.collected_at DESC LIMIT 1) AS temp," +
                "  (SELECT s.value FROM sensor_data s JOIN device d ON d.id=s.device_id" +
                "    WHERE d.plot_id=p.id AND s.metric='humidity' ORDER BY s.collected_at DESC LIMIT 1) AS humidity" +
                " FROM plot p ORDER BY p.id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('{')
                  .append("\"id\":").append(Json.str(rs.getString("id"))).append(',')
                  .append("\"name\":").append(Json.str(rs.getString("name"))).append(',')
                  .append("\"crop\":").append(Json.str(rs.getString("crop"))).append(',')
                  .append("\"area\":").append(Json.str(areaStr(rs.getBigDecimal("area")))).append(',')
                  .append("\"temp\":").append(Json.num(rs.getBigDecimal("temp"))).append(',')
                  .append("\"humidity\":").append(Json.num(rs.getBigDecimal("humidity"))).append(',')
                  .append("\"deviceCount\":").append(rs.getInt("deviceCount")).append(',')
                  .append("\"onlineCount\":").append(rs.getInt("onlineCount"))
                  .append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 面积显示格式：数字 + 亩（mock 里就是 '2.5亩' 这种字符串） */
    private static String areaStr(BigDecimal area) {
        if (area == null) return "";
        return area.stripTrailingZeros().toPlainString() + "亩";
    }

    /* ==================================================================
       通用响应
       ================================================================== */

    private static void ok(HttpExchange ex, String json) throws IOException {
        send(ex, 200, json);
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 读取请求体（GET 时为空字符串） */
    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
