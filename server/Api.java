package server;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
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

            /* ---------- 实时 / 历史数据 ---------- */
            if ("GET".equals(method) && path.matches("/api/plots/[^/]+/realtime")) {
                ok(ex, realtimeJson(path.substring("/api/plots/".length(),
                        path.length() - "/realtime".length())));
                return true;
            }
            if ("GET".equals(method) && path.matches("/api/plots/[^/]+/history")) {
                ok(ex, historyJson(path.substring("/api/plots/".length(),
                        path.length() - "/history".length()), ex.getRequestURI().getQuery()));
                return true;
            }

            /* ---------- 灌溉控制 ---------- */
            if ("POST".equals(method) && path.matches("/api/devices/[^/]+/control")) {
                ok(ex, controlJson(path.substring("/api/devices/".length(),
                        path.length() - "/control".length()), ex));
                return true;
            }

            /* ---------- 阈值 / 告警 ---------- */
            if ("GET".equals(method) && path.equals("/api/thresholds")) {
                ok(ex, thresholdsJson());
                return true;
            }
            if ("PUT".equals(method) && path.equals("/api/thresholds")) {
                ok(ex, saveThresholdsJson(ex));
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/alarms")) {
                ok(ex, alarmsJson());
                return true;
            }
            if ("PUT".equals(method) && path.matches("/api/alarms/[^/]+")) {
                ok(ex, updateAlarmJson(path.substring("/api/alarms/".length()), ex));
                return true;
            }

            /* ---------- 登录 ---------- */
            if ("POST".equals(method) && path.equals("/api/auth/login")) {
                ok(ex, loginJson(ex));
                return true;
            }

            /* ---------- 注册 ---------- */
            if ("POST".equals(method) && path.equals("/api/auth/register")) {
                ok(ex, registerJson(ex));
                return true;
            }

            /* ---------- 控制日志 ---------- */
            if ("GET".equals(method) && path.equals("/api/control-logs")) {
                ok(ex, controlLogsJson());
                return true;
            }

            /* ---------- 传感器数据上报（模拟硬件 / MQTT 接收端） ---------- */
            if ("POST".equals(method) && path.equals("/api/sensor-data")) {
                ok(ex, sensorDataJson(ex));
                return true;
            }

            /* ---------- 智能问答 ---------- */
            if ("POST".equals(method) && path.equals("/api/assistant/chat")) {
                ok(ex, chatJson(ex));
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

    /** 按设备编号查单个设备 JSON（含 plotName / controllable / running）；不存在返回 null */
    private static String deviceJson(String id) throws SQLException {
        String sql =
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running" +
                " FROM device d LEFT JOIN plot p ON p.id = d.plot_id WHERE d.id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";
                return "{\"id\":" + Json.str(rs.getString("id"))
                        + ",\"name\":" + Json.str(rs.getString("name"))
                        + ",\"type\":" + Json.str(typeMap(rs.getString("type")))
                        + ",\"plotId\":" + Json.str(rs.getString("plot_id"))
                        + ",\"plotName\":" + Json.str(rs.getString("plotName"))
                        + ",\"online\":" + (rs.getInt("online") == 1)
                        + ",\"controllable\":" + "灌溉设备".equals(rs.getString("type"))
                        + ",\"running\":" + (rs.getInt("running") == 1)
                        + "}";
            }
        }
    }

    /** 设备类型映射：数据库类型 → 前端契约类型 */
    private static String typeMap(String type) {
        if ("灌溉设备".equals(type)) return "灌溉设备";
        if ("温度传感器".equals(type)) return "温度";
        return "土壤湿度"; // 土壤湿度传感器等统一归为土壤湿度
    }

    /* ==================================================================
       实时 / 历史数据
       ================================================================== */

    /** GET /api/plots/{plotId}/realtime —— 某地块最新温湿度 */
    private static String realtimeJson(String plotId) throws SQLException {
        BigDecimal temp = latestValue(plotId, "temp");
        BigDecimal humidity = latestValue(plotId, "humidity");
        String updatedAt = latestTime(plotId);
        return "{\"code\":0,\"data\":{"
                + "\"plotId\":" + Json.str(plotId)
                + ",\"plotName\":" + Json.str(plotName(plotId))
                + ",\"temp\":" + Json.num(temp)
                + ",\"humidity\":" + Json.num(humidity)
                + ",\"updatedAt\":" + Json.str(updatedAt)
                + "}}";
    }

    /** GET /api/plots/{plotId}/history?days=N —— 近 N 天按日聚合的温湿度趋势 */
    private static String historyJson(String plotId, String query) throws SQLException {
        int days = 7;
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=");
                if (p.length == 2 && "days".equals(p[0])) {
                    try { days = Integer.parseInt(p[1]); } catch (NumberFormatException ignore) { }
                }
            }
        }
        String sql =
                "SELECT DATE_FORMAT(s.collected_at, '%Y-%m-%d') AS day, s.metric, AVG(s.value) AS avgv" +
                " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ? AND s.collected_at >= DATE_SUB(NOW(), INTERVAL ? DAY)" +
                " GROUP BY day, s.metric ORDER BY day";
        StringBuilder dates = new StringBuilder();
        StringBuilder temp = new StringBuilder();
        StringBuilder hum = new StringBuilder();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            ps.setInt(2, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = rs.getString("day");
                    double v = rs.getDouble("avgv");
                    if (dates.length() > 0) dates.append(',');
                    dates.append(Json.str(day));
                    if ("temp".equals(rs.getString("metric"))) {
                        if (temp.length() > 0) temp.append(',');
                        temp.append(v);
                    } else {
                        if (hum.length() > 0) hum.append(',');
                        hum.append(v);
                    }
                }
            }
        }
        return "{\"code\":0,\"data\":{"
                + "\"dates\":[" + dates + "]"
                + ",\"temp\":[" + temp + "]"
                + ",\"humidity\":[" + hum + "]}"
                + "}";
    }

    /** 某地块某指标的最新一条读数；没有数据返回 null */
    private static BigDecimal latestValue(String plotId, String metric) throws SQLException {
        String sql =
                "SELECT s.value FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ? AND s.metric = ? ORDER BY s.collected_at DESC LIMIT 1";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            ps.setString(2, metric);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    /** 某地块最新采集时间；没有数据返回空串 */
    private static String latestTime(String plotId) throws SQLException {
        String sql =
                "SELECT MAX(s.collected_at) FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts);
                }
                return "";
            }
        }
    }

    /* ==================================================================
       灌溉控制
       ================================================================== */

    /**
     * POST /api/devices/{deviceId}/control —— 灌溉开关。
     * 请求体：{action:'on'|'off'}；更新 device.running，并写一条 control_log 留痕。
     */
    private static String controlJson(String deviceId, HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String action = body.get("action");
        if (!"on".equals(action) && !"off".equals(action)) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：action 需为 on 或 off") + "}";
        }
        int running = "on".equals(action) ? 1 : 0;
        String actionText = running == 1 ? "开启" : "关闭";
        String operator = body.containsKey("operator") && body.get("operator") != null
                ? body.get("operator") : "演示用户";

        try (Connection conn = DBUtil.getConnection()) {
            // 1. 更新设备运行状态
            try (PreparedStatement ps = conn.prepareStatement("UPDATE device SET running = ? WHERE id = ?")) {
                ps.setInt(1, running);
                ps.setString(2, deviceId);
                if (ps.executeUpdate() == 0) {
                    return "{\"code\":1,\"msg\":" + Json.str("设备不存在: " + deviceId) + "}";
                }
            }
            // 2. 写入控制日志（留痕）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO control_log(device_id, action, result, operator) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, deviceId);
                ps.setString(2, actionText);
                ps.setString(3, "成功");
                ps.setString(4, operator);
                ps.executeUpdate();
            }
        }
        return "{\"code\":0,\"data\":" + deviceJson(deviceId) + "}";
    }

    /* ==================================================================
       阈值 / 告警
       ================================================================== */

    /** 当前阈值：取第一个地块的配置（每地块一行），全部没配置时用默认 40 / 35 */
    private static BigDecimal[] currentThresholds() throws SQLException {
        BigDecimal humidityMin = new BigDecimal(40);
        BigDecimal tempMax = new BigDecimal(35);
        String sql = "SELECT humidity_min, temp_max FROM plot_threshold ORDER BY plot_id LIMIT 1";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                humidityMin = rs.getBigDecimal("humidity_min");
                tempMax = rs.getBigDecimal("temp_max");
            }
        }
        return new BigDecimal[]{humidityMin, tempMax};
    }

    /**
     * GET /api/thresholds —— 阈值配置。
     * 前端只有一个全局编辑器，DB 是每地块一行（plot_threshold）；
     * 这里返回当前阈值（首个地块），未配置时用默认值 40 / 35。
     */
    private static String thresholdsJson() throws SQLException {
        BigDecimal[] t = currentThresholds();
        return "{\"code\":0,\"data\":{"
                + "\"humidityMin\":" + Json.num(t[0].stripTrailingZeros().toPlainString())
                + ",\"tempMax\":" + Json.num(t[1].stripTrailingZeros().toPlainString())
                + "}}";
    }

    /**
     * PUT /api/thresholds —— 保存阈值。
     * body: {humidityMin, tempMax}；对每个地块 upsert 一份，保证前端全局编辑后各地块一致。
     */
    private static String saveThresholdsJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String h = body.get("humidityMin");
        String t = body.get("tempMax");
        if (h == null || t == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：humidityMin/tempMax 必填") + "}";
        }
        BigDecimal humidityMin;
        BigDecimal tempMax;
        try {
            humidityMin = new BigDecimal(h);
            tempMax = new BigDecimal(t);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：阈值必须是数字") + "}";
        }
        String sql =
                "INSERT INTO plot_threshold(plot_id, humidity_min, temp_max) VALUES (?, ?, ?)" +
                " ON DUPLICATE KEY UPDATE humidity_min = VALUES(humidity_min), temp_max = VALUES(temp_max)";
        try (Connection conn = DBUtil.getConnection()) {
            // 1. 收集全部地块编号
            List<String> plotIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM plot");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) plotIds.add(rs.getString(1));
            }
            // 2. 每个地块 upsert 一份同样的阈值
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String plotId : plotIds) {
                    ps.setString(1, plotId);
                    ps.setBigDecimal(2, humidityMin);
                    ps.setBigDecimal(3, tempMax);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return "{\"code\":0}";
    }

    /**
     * GET /api/alarms —— 告警列表。
     * 返回：id,time,plotId,plotName,type,value,level,status。
     * time 格式与 mock 一致（yyyy-MM-dd HH:mm）；type 直接用库里的中文 alarm_type。
     */
    private static String alarmsJson() throws SQLException {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT a.id, a.plot_id, a.alarm_type, a.value, a.level, a.status, a.created_at," +
                " p.name AS plotName FROM alarm a LEFT JOIN plot p ON p.id = a.plot_id" +
                " ORDER BY a.created_at DESC, a.id DESC";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                Timestamp ts = rs.getTimestamp("created_at");
                String time = ts == null ? "" : fmt.format(ts);
                sb.append('{')
                  .append("\"id\":").append(rs.getLong("id")).append(',')
                  .append("\"time\":").append(Json.str(time)).append(',')
                  .append("\"plotId\":").append(Json.str(rs.getString("plot_id"))).append(',')
                  .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                  .append("\"type\":").append(Json.str(rs.getString("alarm_type"))).append(',')
                  .append("\"value\":").append(Json.str(rs.getString("value"))).append(',')
                  .append("\"level\":").append(Json.str(rs.getString("level"))).append(',')
                  .append("\"status\":").append(Json.str(rs.getString("status")))
                  .append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * PUT /api/alarms/{id} —— 标记处理。
     * body: {status:'已处理'}；顺手记录处理人和处理时间。
     */
    private static String updateAlarmJson(String id, HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String status = body.get("status");
        if (status == null || status.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：status 必填") + "}";
        }
        String handler = body.containsKey("handler") && body.get("handler") != null
                ? body.get("handler") : "演示用户";
        String sql = "UPDATE alarm SET status = ?, handled_at = NOW(), handler = ? WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, handler);
            ps.setLong(3, Long.parseLong(id));
            if (ps.executeUpdate() == 0) {
                return "{\"code\":1,\"msg\":" + Json.str("告警不存在: " + id) + "}";
            }
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
        }
        return "{\"code\":0}";
    }

    /* ==================================================================
       登录
       ================================================================== */

    /**
     * POST /api/auth/login —— 登录。
     * body: {username, password, role}。
     * 演示环境：user 表密码是占位哈希（$2a$10$placeholder），不校验密码，任意账号密码可登录。
     * 用户优先按 username 查，查不到再按 role 兜底；返回 token/name/roleName/role。
     */
    private static String loginJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("请输入账号和密码") + "}";
        }

        String foundName = null;
        String foundRole = null;
        String foundHash = null;
        String sql = "SELECT name, role, password FROM user WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    foundName = rs.getString("name");
                    foundRole = rs.getString("role");
                    foundHash = rs.getString("password");
                }
            }
        }
        if (foundRole == null || foundHash == null) {
            return "{\"code\":1,\"msg\":" + Json.str("账号或密码错误") + "}";
        }
        // 密码校验：SHA-256（种子账号密码均为 123456）
        if (!sha256(password).equals(foundHash)) {
            return "{\"code\":1,\"msg\":" + Json.str("账号或密码错误") + "}";
        }

        String token = "token-" + foundRole + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return "{\"code\":0,\"msg\":\"ok\",\"data\":{"
                + "\"token\":" + Json.str(token) + ","
                + "\"name\":" + Json.str(foundName) + ","
                + "\"roleName\":" + Json.str(roleName(foundRole)) + ","
                + "\"role\":" + Json.str(foundRole)
                + "}}";
    }

    /** role 枚举 → 前端展示的中文角色名 */
    private static String roleName(String role) {
        if ("farmer".equals(role)) return "农户";
        if ("admin".equals(role)) return "农场管理员";
        if ("sysadmin".equals(role)) return "系统管理员";
        return role;
    }

    /* ==================================================================
       智能问答
       ================================================================== */

    /**
     * POST /api/assistant/chat —— 智能问答。
     * body: {question}。关键词规则匹配（和前端 mock 同一套问答逻辑），
     * 文案里的阈值实时读库，让回答跟着当前配置走。
     * 返回：{answer, action:{text,href}|null}。
     */
    private static String chatJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String question = body.get("question");
        if (question == null) question = "";
        String q = question.replaceAll("[？?。.，,、\\s]", ""); // 去标点空格便于关键词命中

        BigDecimal[] t = currentThresholds();
        String h = t[0].stripTrailingZeros().toPlainString();
        String tm = t[1].stripTrailingZeros().toPlainString();

        // 规则表：关键词 → 回答文案 / 推荐操作
        String[][] rules = {
                {"浇水,灌溉,什么时候浇,该不该浇",
                 "根据当前土壤湿度数据，建议在清晨 6:00–8:00 或傍晚 18:00–20:00 灌溉，此时蒸发量小、水分利用率高。若土壤湿度低于 " + h + "%（当前阈值），请及时补水。",
                 "去控制灌溉", "control.html"},
                {"太干,干旱,缺水,湿度低",
                 "当前部分地块土壤湿度偏低，存在缺水风险。建议开启灌溉设备补水 20–30 分钟，并关注告警记录，避免作物因缺水萎蔫。",
                 "查看告警", "alarm.html"},
                {"阈值,告警条件,设置",
                 "您可以在「告警管理」页设置土壤湿度下限和温度上限。当实测值越过阈值时，系统会自动触发告警并通知您。",
                 "去设置阈值", "alarm.html"},
                {"温度,太热,高温",
                 "若大棚温度超过 " + tm + "℃（当前阈值），建议及时通风或开启遮阳。温度过高会影响作物生长，请留意实时温度曲线。",
                 "查看实时数据", "monitoring.html"}
        };

        String answer = "我是智慧农业助手，可以为您提供灌溉建议和农事指导。您可以试试问我：「现在该浇水吗？」「土壤太干怎么办？」「如何设置告警阈值？」";
        String actionText = null;
        String actionHref = null;
        outer:
        for (String[] rule : rules) {
            for (String kw : rule[0].split(",")) {
                if (q.indexOf(kw) != -1) {
                    answer = rule[1];
                    actionText = rule[2];
                    actionHref = rule[3];
                    break outer;
                }
            }
        }

        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":{\"answer\":")
                .append(Json.str(answer));
        if (actionText != null) {
            sb.append(",\"action\":{\"text\":").append(Json.str(actionText))
              .append(",\"href\":").append(Json.str(actionHref)).append('}');
        } else {
            sb.append(",\"action\":null");
        }
        sb.append("}}");
        return sb.toString();
    }

    /* ==================================================================
       注册
       ================================================================== */

    /**
     * POST /api/auth/register —— 注册申请。
     * 写入 register_request（待审核），管理员审核通过后才写入 user 表。
     * role 默认 farmer（前端注册表单暂不选角色）。
     */
    private static String registerJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String username = body.get("username");
        String password = body.get("password");
        String role = body.get("role");
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("请填写账号和密码") + "}";
        }
        if (role == null || role.isEmpty()) role = "farmer";
        if (!"farmer".equals(role) && !"admin".equals(role) && !"sysadmin".equals(role)) role = "farmer";

        // 用户名已存在（user 或待审核的 register_request）则拒绝
        if (exists("SELECT 1 FROM user WHERE username = ?", username)
                || exists("SELECT 1 FROM register_request WHERE username = ? AND status = '待审核'", username)) {
            return "{\"code\":1,\"msg\":" + Json.str("账号已存在或已在审核中") + "}";
        }

        String hash = sha256(password);
        String sql = "INSERT INTO register_request(username, password, role, status) VALUES (?, ?, ?, '待审核')";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, role);
            ps.executeUpdate();
        }
        return "{\"code\":0,\"msg\":" + Json.str("注册申请已提交，请等待管理员审核") + "}";
    }

    /** 是否存在某条记录（注册去重用） */
    private static boolean exists(String sql, String param) throws SQLException {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /* ==================================================================
       控制日志
       ================================================================== */

    /**
     * GET /api/control-logs —— 灌溉控制日志列表。
     * deviceName / plotName 由 join 得出，operator 存库。
     */
    private static String controlLogsJson() throws SQLException {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT c.id, c.action, c.result, c.operator, c.created_at," +
                " d.name AS deviceName, p.name AS plotName" +
                " FROM control_log c" +
                " LEFT JOIN device d ON d.id = c.device_id" +
                " LEFT JOIN plot p ON p.id = d.plot_id" +
                " ORDER BY c.created_at DESC, c.id DESC";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                Timestamp ts = rs.getTimestamp("created_at");
                String time = ts == null ? "" : fmt.format(ts);
                sb.append('{')
                  .append("\"id\":").append(rs.getLong("id")).append(',')
                  .append("\"time\":").append(Json.str(time)).append(',')
                  .append("\"deviceName\":").append(Json.str(rs.getString("deviceName"))).append(',')
                  .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                  .append("\"action\":").append(Json.str(rs.getString("action"))).append(',')
                  .append("\"result\":").append(Json.str(rs.getString("result"))).append(',')
                  .append("\"operator\":").append(Json.str(rs.getString("operator")))
                  .append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /* ==================================================================
       传感器数据上报（模拟硬件 / MQTT 接收端）
       ================================================================== */

    /**
     * POST /api/sensor-data —— 接收一条传感器读数。
     * body: {deviceId, metric:'temp'|'humidity', value}。
     * 写入 sensor_data，并检查是否越过阈值触发告警。
     */
    private static String sensorDataJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String deviceId = body.get("deviceId");
        String metric = body.get("metric");
        String value = body.get("value");
        if (deviceId == null || metric == null || value == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：deviceId/metric/value 必填") + "}";
        }
        if (!"temp".equals(metric) && !"humidity".equals(metric)) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：metric 需为 temp 或 humidity") + "}";
        }
        BigDecimal v;
        try { v = new BigDecimal(value); } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：value 必须是数字") + "}";
        }

        // 1. 写入读数
        String sql = "INSERT INTO sensor_data(device_id, metric, value, collected_at) VALUES (?, ?, ?, NOW(3))";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, metric);
            ps.setBigDecimal(3, v);
            ps.executeUpdate();
        }

        // 2. 阈值告警检查
        String plotId = plotOfDevice(deviceId);
        if (plotId != null) {
            checkThresholdAlarm(plotId, metric, v);
        }
        return "{\"code\":0}";
    }

    /** 设备所属地块；不存在返回 null */
    private static String plotOfDevice(String deviceId) throws SQLException {
        String sql = "SELECT plot_id FROM device WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 越过阈值时插入一条告警（同一地块同类型未处理告警不重复插） */
    private static void checkThresholdAlarm(String plotId, String metric, BigDecimal value) throws SQLException {
        BigDecimal humidityMin = new BigDecimal(40);
        BigDecimal tempMax = new BigDecimal(35);
        String sql = "SELECT humidity_min, temp_max FROM plot_threshold WHERE plot_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    humidityMin = rs.getBigDecimal("humidity_min");
                    tempMax = rs.getBigDecimal("temp_max");
                }
            }
        }

        String alarmType = null;
        String alarmValue = null;
        String level = null;
        if ("humidity".equals(metric) && value.compareTo(humidityMin) < 0) {
            alarmType = "土壤湿度过低";
            alarmValue = value.stripTrailingZeros().toPlainString() + "%";
            level = "警告";
        } else if ("temp".equals(metric) && value.compareTo(tempMax) > 0) {
            alarmType = "温度过高";
            alarmValue = value.stripTrailingZeros().toPlainString() + "℃";
            level = "严重";
        }
        if (alarmType == null) return;

        // 去重：同一地块同类型且未处理的告警不重复插入
        String dup = "SELECT 1 FROM alarm WHERE plot_id = ? AND alarm_type = ? AND status = '未处理'";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(dup)) {
            ps.setString(1, plotId);
            ps.setString(2, alarmType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        String ins = "INSERT INTO alarm(plot_id, alarm_type, value, level, status) VALUES (?, ?, ?, ?, '未处理')";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ins)) {
            ps.setString(1, plotId);
            ps.setString(2, alarmType);
            ps.setString(3, alarmValue);
            ps.setString(4, level);
            ps.executeUpdate();
        }
    }

    /* ==================================================================
       SHA-256 哈希（登录 / 注册密码）
       ================================================================== */

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
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
