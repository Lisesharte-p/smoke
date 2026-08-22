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
import java.util.Map;

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

            /* ---------- 设备 ---------- */
            if ("GET".equals(method) && path.equals("/api/devices")) {
                ok(ex, devicesJson());
                return true;
            }
            if ("POST".equals(method) && path.equals("/api/devices")) {
                ok(ex, addDeviceJson(ex));
                return true;
            }
            if ("DELETE".equals(method) && path.matches("/api/devices/[^/]+")) {
                ok(ex, deleteDeviceJson(path.substring("/api/devices/".length())));
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
       设备
       ================================================================== */

    /**
     * GET /api/devices —— 设备列表。
     * type 按前端契约映射（数据库是 '土壤湿度传感器/温度传感器'，前端徽章只认 '土壤湿度/温度'）；
     * plotName 由 join plot 得出；controllable 由 type=='灌溉设备' 推出。
     */
    private static String devicesJson() throws SQLException {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running" +
                " FROM device d LEFT JOIN plot p ON p.id = d.plot_id ORDER BY d.id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                String type = rs.getString("type");
                sb.append('{')
                  .append("\"id\":").append(Json.str(rs.getString("id"))).append(',')
                  .append("\"name\":").append(Json.str(rs.getString("name"))).append(',')
                  .append("\"type\":").append(Json.str(typeMap(type))).append(',')
                  .append("\"plotId\":").append(Json.str(rs.getString("plot_id"))).append(',')
                  .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                  .append("\"online\":").append(rs.getInt("online") == 1).append(',')
                  .append("\"controllable\":").append("灌溉设备".equals(type)).append(',')
                  .append("\"running\":").append(rs.getInt("running") == 1)
                  .append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * POST /api/devices —— 新增设备。
     * 请求体：{name, type, plotId}；编号自动生成（D + 最大编号数字 + 1）。
     */
    private static String addDeviceJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String name = body.get("name");
        String type = body.get("type");
        String plotId = body.get("plotId");
        if (name == null || name.isEmpty() || type == null || plotId == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：name/type/plotId 必填") + "}";
        }
        String id = nextDeviceId();
        String plotName = plotName(plotId);
        String sql = "INSERT INTO device(id, plot_id, name, type) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, plotId);
            ps.setString(3, name);
            ps.setString(4, type);
            ps.executeUpdate();
        }
        return "{\"code\":0,\"data\":" + deviceJson(id, name, type, plotId, plotName, true) + "}";
    }

    /** DELETE /api/devices/{id} —— 解绑设备 */
    private static String deleteDeviceJson(String id) throws SQLException {
        String sql = "DELETE FROM device WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        return "{\"code\":0}";
    }

    /** 生成新的设备编号：D + (当前最大编号数字 + 1)，如 D006 */
    private static String nextDeviceId() throws SQLException {
        String sql = "SELECT MAX(CAST(SUBSTRING(id, 2) AS UNSIGNED)) FROM device";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int max = 0;
            if (rs.next()) max = rs.getInt(1);
            return String.format("D%03d", max + 1);
        }
    }

    /** 按设备编号查地块名（没有则返回 '-'） */
    private static String plotName(String plotId) throws SQLException {
        String sql = "SELECT name FROM plot WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "-";
            }
        }
    }

    /** 组一个设备对象的 JSON（新增设备响应用） */
    private static String deviceJson(String id, String name, String type, String plotId,
                                     String plotName, boolean online) {
        return "{\"id\":" + Json.str(id)
                + ",\"name\":" + Json.str(name)
                + ",\"type\":" + Json.str(typeMap(type))
                + ",\"plotId\":" + Json.str(plotId)
                + ",\"plotName\":" + Json.str(plotName)
                + ",\"online\":" + online
                + ",\"controllable\":" + "灌溉设备".equals(type)
                + ",\"running\":false}";
    }

    /** 设备类型映射：数据库类型 → 前端契约类型 */
    private static String typeMap(String type) {
        if ("灌溉设备".equals(type)) return "灌溉设备";
        if ("温度传感器".equals(type)) return "温度";
        return "土壤湿度"; // 土壤湿度传感器等统一归为土壤湿度
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
