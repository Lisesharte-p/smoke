package server;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
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
            if ("POST".equals(method) && path.equals("/api/plots")) {
                ok(ex, addPlotJson(ex));
                return true;
            }
            if ("DELETE".equals(method) && path.matches("/api/plots/[^/]+")) {
                ok(ex, deletePlotJson(path.substring("/api/plots/".length())));
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

            /* ---------- 注册申请审核（管理员） ---------- */
            if ("GET".equals(method) && path.equals("/api/register-requests")) {
                ok(ex, registerRequestsJson());
                return true;
            }
            if ("PUT".equals(method) && path.matches("/api/register-requests/[^/]+")) {
                ok(ex, reviewRegisterJson(path.substring("/api/register-requests/".length()), ex));
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

            /* ---------- 板子手动刷新（立即读一次板子并入库） ---------- */
            if ("POST".equals(method) && path.equals("/api/board/refresh")) {
                ok(ex, refreshBoardJson());
                return true;
            }

            /* ---------- 智能问答 ---------- */
            if ("POST".equals(method) && path.equals("/api/assistant/chat")) {
                ok(ex, chatJson(ex));
                return true;
            }

            /* ---------- 对话历史（按用户隔离） ---------- */
            if ("GET".equals(method) && path.equals("/api/conversations")) {
                ok(ex, conversationsJson(ex.getRequestURI().getQuery()));
                return true;
            }
            if ("GET".equals(method) && path.matches("/api/conversations/[^/]+")) {
                ok(ex, conversationMessagesJson(path.substring("/api/conversations/".length()),
                        ex.getRequestURI().getQuery()));
                return true;
            }
            if ("DELETE".equals(method) && path.matches("/api/conversations/[^/]+")) {
                ok(ex, deleteConversationJson(path.substring("/api/conversations/".length()),
                        ex.getRequestURI().getQuery()));
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

    /** 生成新的地块编号：P + (当前最大编号数字 + 1)，如 P005 */
    private static String nextPlotId() throws SQLException {
        String sql = "SELECT MAX(CAST(SUBSTRING(id, 2) AS UNSIGNED)) FROM plot";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int max = 0;
            if (rs.next()) max = rs.getInt(1);
            return String.format("P%03d", max + 1);
        }
    }

    /** 按地块编号查单个地块 JSON（与列表项形状一致）；不存在返回 null */
    private static String plotJson(String plotId) throws SQLException {
        String sql =
                "SELECT p.id, p.name, p.crop, p.area," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id) AS deviceCount," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id AND d.online=1) AS onlineCount" +
                " FROM plot p WHERE p.id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";
                return "{\"id\":" + Json.str(rs.getString("id"))
                        + ",\"name\":" + Json.str(rs.getString("name"))
                        + ",\"crop\":" + Json.str(rs.getString("crop"))
                        + ",\"area\":" + Json.str(areaStr(rs.getBigDecimal("area")))
                        + ",\"temp\":" + Json.num(latestValue(plotId, "temp"))
                        + ",\"humidity\":" + Json.num(latestValue(plotId, "humidity"))
                        + ",\"deviceCount\":" + rs.getInt("deviceCount")
                        + ",\"onlineCount\":" + rs.getInt("onlineCount")
                        + "}";
            }
        }
    }

    /**
     * POST /api/plots —— 新增地块。
     * 请求体：{name, crop, area, devices?:[{name,type,ip,port},...]}。
     * devices 为可选：添加地块时同步绑定新设备（每个设备 name/type/ip/port 必填），
     * 与地块同在一个事务里，任一设备校验不过则整单回滚。
     */
    private static String addPlotJson(HttpExchange ex) throws IOException, SQLException {
        String raw = readBody(ex);
        Map<String, String> body = Json.parseObject(raw);
        String name = body.get("name");
        String crop = body.get("crop");
        String area = body.get("area");
        if (name == null || name.trim().isEmpty()
                || crop == null || crop.trim().isEmpty()
                || area == null || area.trim().isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：name/crop/area 必填") + "}";
        }
        BigDecimal areaVal;
        try {
            areaVal = new BigDecimal(area.trim());
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：area 必须是数字") + "}";
        }
        if (areaVal.compareTo(BigDecimal.ZERO) <= 0) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：area 需大于 0") + "}";
        }

        String plotId = nextPlotId();
        List<Map<String, String>> devices = Json.parseObjectArray(Json.arrayText(raw, "devices"));

        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. 插入地块
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO plot(id, name, crop, area) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, plotId);
                    ps.setString(2, name.trim());
                    ps.setString(3, crop.trim());
                    ps.setBigDecimal(4, areaVal);
                    ps.executeUpdate();
                }
                // 2. 绑定设备（校验后逐个插入；编号在事务内递增，避免重复）
                for (Map<String, String> d : devices) {
                    String devName = d.get("name");
                    String type = d.get("type");
                    String ip = d.get("ip");
                    String portStr = d.get("port");
                    if (devName == null || devName.trim().isEmpty()) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备名称不能为空") + "}";
                    }
                    if (type == null || type.isEmpty()) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备「" + devName + "」需选择类型") + "}";
                    }
                    if (ip == null || ip.trim().isEmpty() || portStr == null || portStr.trim().isEmpty()) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备「" + devName + "」需填写 IP 和端口") + "}";
                    }
                    int port;
                    try {
                        port = Integer.parseInt(portStr.trim());
                    } catch (NumberFormatException e) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备「" + devName + "」端口必须是数字") + "}";
                    }
                    if (port < 1 || port > 65535) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备「" + devName + "」端口需在 1-65535 之间") + "}";
                    }
                    // 前端类型契约 → 入库类型（与 addDeviceJson 同一套映射）
                    String devType = type;
                    if ("土壤湿度".equals(type)) devType = "土壤湿度传感器";
                    else if ("温度".equals(type)) devType = "温度传感器";
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO device(id, plot_id, name, type, ip, port) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, nextDeviceId(conn));
                        ps.setString(2, plotId);
                        ps.setString(3, devName.trim());
                        ps.setString(4, devType);
                        ps.setString(5, ip.trim());
                        ps.setInt(6, port);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return "{\"code\":0,\"data\":" + plotJson(plotId) + "}";
    }

    /**
     * DELETE /api/plots/{plotId} —— 删除地块。
     * 级联删除该地块下的所有关联数据（同一事务，要么全删要么全不删）：
     *   device（及其 sensor_data / control_log）→ alarm → plot_threshold → plot
     */
    private static String deletePlotJson(String plotId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 0. 地块是否存在
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM plot WHERE id = ?")) {
                    ps.setString(1, plotId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return "{\"code\":1,\"msg\":" + Json.str("地块不存在: " + plotId) + "}";
                        }
                    }
                }
                // 1. 先删该地块下设备的传感器数据 / 控制日志，再删设备本身
                exec(conn, "DELETE FROM sensor_data WHERE device_id IN (SELECT id FROM device WHERE plot_id = ?)", plotId);
                exec(conn, "DELETE FROM control_log WHERE device_id IN (SELECT id FROM device WHERE plot_id = ?)", plotId);
                exec(conn, "DELETE FROM device WHERE plot_id = ?", plotId);
                // 2. 该地块的告警（含设备离线等按地块归类的告警）
                exec(conn, "DELETE FROM alarm WHERE plot_id = ?", plotId);
                // 3. 阈值配置
                exec(conn, "DELETE FROM plot_threshold WHERE plot_id = ?", plotId);
                // 4. 地块本身
                exec(conn, "DELETE FROM plot WHERE id = ?", plotId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return "{\"code\":0}";
    }

    /** 事务内执行一条带单参数的 DELETE/UPDATE，供级联删除复用 */
    private static void exec(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
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
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running, d.ip, d.port" +
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
                  .append("\"ip\":").append(Json.str(rs.getString("ip"))).append(',')
                  .append("\"port\":").append(Json.num(rs.getObject("port"))).append(',')
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
        String ip = body.get("ip");
        String portStr = body.get("port");
        if (name == null || name.isEmpty() || type == null || plotId == null
                || ip == null || ip.trim().isEmpty() || portStr == null || portStr.trim().isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：name/type/plotId/ip/port 必填") + "}";
        }
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：port 必须是数字") + "}";
        }
        if (port < 1 || port > 65535) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：port 需在 1-65535 之间") + "}";
        }
        // 前端类型契约 → 入库类型（typeMap 只认 '土壤湿度传感器'/'温度传感器'/'灌溉设备'）
        if ("土壤湿度".equals(type)) type = "土壤湿度传感器";
        else if ("温度".equals(type)) type = "温度传感器";
        String id = nextDeviceId();
        String sql = "INSERT INTO device(id, plot_id, name, type, ip, port) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, plotId);
            ps.setString(3, name);
            ps.setString(4, type);
            ps.setString(5, ip.trim());
            ps.setInt(6, port);
            ps.executeUpdate();
        }
        return "{\"code\":0,\"data\":" + deviceJson(id) + "}";
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
        try (Connection conn = DBUtil.getConnection()) {
            return nextDeviceId(conn);
        }
    }

    /** 在指定连接里生成设备编号：事务内连续新增设备时能看到自己的未提交插入，编号不会重复 */
    private static String nextDeviceId(Connection conn) throws SQLException {
        String sql = "SELECT MAX(CAST(SUBSTRING(id, 2) AS UNSIGNED)) FROM device";
        try (PreparedStatement ps = conn.prepareStatement(sql);
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

    /** 按设备编号查单个设备 JSON（含 plotName / controllable / running / ip / port）；不存在返回 null */
    private static String deviceJson(String id) throws SQLException {
        String sql =
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running, d.ip, d.port" +
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
                        + ",\"ip\":" + Json.str(rs.getString("ip"))
                        + ",\"port\":" + Json.num(rs.getObject("port"))
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

    /**
     * GET /api/plots/{plotId}/history —— 温湿度趋势。
     * 支持两种查询方式：
     *   ?days=N     近 N 天按日聚合（默认 7）
     *   ?window=1m|30m|24h   短时窗口实时趋势（1分钟按秒、30分钟按分钟、24小时按5分钟聚合）
     * 返回 {dates:[], temp:[], humidity:[]}，三个数组一一对齐。
     */
    private static String historyJson(String plotId, String query) throws SQLException {
        int days = 7;
        String window = null;
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=");
                if (p.length == 2) {
                    if ("days".equals(p[0])) {
                        try { days = Integer.parseInt(p[1]); } catch (NumberFormatException ignore) { }
                    } else if ("window".equals(p[0])) {
                        window = p[1];
                    }
                }
            }
        }

        // 短时窗口：按各自粒度聚合；1m/30m/24h 都 GROUP BY 时间标签，保证每桶 temp/humidity 齐全
        String intervalSql = null; // DATE_SUB(NOW(), INTERVAL xxx)
        String selectExpr  = null; // SELECT 的时间标签表达式 + 值表达式
        String groupSql    = null; // GROUP BY 的时间标签
        if ("1m".equals(window)) {
            intervalSql = "1 MINUTE";
            selectExpr  = "DATE_FORMAT(s.collected_at, '%H:%i:%s') AS x, s.metric, AVG(s.value) AS v";
            groupSql    = "x, s.metric";
        } else if ("30m".equals(window)) {
            intervalSql = "30 MINUTE";
            selectExpr  = "DATE_FORMAT(s.collected_at, '%H:%i') AS x, s.metric, AVG(s.value) AS v";
            groupSql    = "x, s.metric";
        } else if ("24h".equals(window)) {
            intervalSql = "24 HOUR";
            selectExpr  = "CONCAT(DATE_FORMAT(s.collected_at, '%Y-%m-%d %H:'), "
                        + "LPAD(FLOOR(MINUTE(s.collected_at)/5)*5, 2, '0')) AS x, s.metric, AVG(s.value) AS v";
            groupSql    = "x, s.metric";
        }

        String sql;
        if (window != null && intervalSql != null) {
            sql = "SELECT " + selectExpr +
                  " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                  " WHERE d.plot_id = ? AND s.collected_at >= DATE_SUB(NOW(), INTERVAL " + intervalSql + ")" +
                  " GROUP BY " + groupSql + " ORDER BY x";
        } else {
            sql = "SELECT DATE_FORMAT(s.collected_at, '%Y-%m-%d') AS x, s.metric, AVG(s.value) AS v" +
                  " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                  " WHERE d.plot_id = ? AND s.collected_at >= DATE_SUB(NOW(), INTERVAL ? DAY)" +
                  " GROUP BY x, s.metric ORDER BY x";
        }

        StringBuilder dates = new StringBuilder();
        StringBuilder temp = new StringBuilder();
        StringBuilder hum = new StringBuilder();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            if (window == null || intervalSql == null) {
                ps.setInt(2, days);
            }
            // 同一时间标签只记一次日期，保证 dates 与 temp/humidity 一一对齐
            String lastX = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String x = rs.getString("x");
                    double v = rs.getDouble("v");
                    String metric = rs.getString("metric");
                    // 只有 temp/humidity 进温湿度趋势；lux 等其它指标不进
                    if ("temp".equals(metric) || "humidity".equals(metric)) {
                        if (!x.equals(lastX)) {
                            if (dates.length() > 0) dates.append(',');
                            dates.append(Json.str(x));
                            lastX = x;
                        }
                        if ("temp".equals(metric)) {
                            if (temp.length() > 0) temp.append(',');
                            temp.append(v);
                        } else {
                            if (hum.length() > 0) hum.append(',');
                            hum.append(v);
                        }
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
     * 请求体：{action:'on'|'off'}。会真正向设备（板子）下发 on/off 指令：
     *   板子收到 on → 开启马达，收到 off → 关闭马达。
     * 只有指令下发成功才更新 device.running 并记「成功」日志；
     * 下发失败（设备未配置地址 / 连不上 / 无确认回包）记「失败」日志并返回错误。
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

        // 1. 查设备网络地址（只有配置了 ip/port 的设备才能向板子下发指令）
        String[] target = deviceTarget(deviceId); // {ip, port}
        if (target == null) {
            return "{\"code\":1,\"msg\":" + Json.str("设备不存在: " + deviceId) + "}";
        }
        if (target[0] == null || target[1] == null) {
            return "{\"code\":1,\"msg\":" + Json.str("设备「" + deviceId + "」未配置网络地址，无法下发指令") + "}";
        }

        // 2. 向板子下发 on/off，收到 motor 确认回包才算成功
        boolean ok;
        try {
            ok = BoardCollector.sendCommand(target[0], Integer.parseInt(target[1]), action);
        } catch (NumberFormatException e) {
            ok = false;
        }
        if (!ok) {
            writeControlLog(deviceId, actionText, "失败", operator);
            return "{\"code\":1,\"msg\":" + Json.str("指令下发失败：无法连接设备或设备无响应") + "}";
        }

        // 3. 指令成功：更新运行状态 + 写成功日志
        try (Connection conn = DBUtil.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE device SET running = ? WHERE id = ?")) {
                ps.setInt(1, running);
                ps.setString(2, deviceId);
                ps.executeUpdate();
            }
        }
        writeControlLog(deviceId, actionText, "成功", operator);
        return "{\"code\":0,\"data\":" + deviceJson(deviceId) + "}";
    }

    /** 查设备的网络地址 {ip, port}；设备不存在返回 null，地址未配置则 ip 为 null */
    private static String[] deviceTarget(String deviceId) throws SQLException {
        String sql = "SELECT ip, port FROM device WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String ip = rs.getString("ip");
                Object port = rs.getObject("port");
                return new String[]{ ip, port == null ? null : port.toString() };
            }
        }
    }

    /** 写一条控制日志（留痕） */
    private static void writeControlLog(String deviceId, String action, String result, String operator) throws SQLException {
        String sql = "INSERT INTO control_log(device_id, action, result, operator) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, action);
            ps.setString(3, result);
            ps.setString(4, operator);
            ps.executeUpdate();
        }
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
                + "\"username\":" + Json.str(username) + ","
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
       智能问答（DeepSeek 大模型）
       ================================================================== */

    /** DeepSeek API Key：必须从环境变量 DEEPSEEK_API_KEY 读取，勿硬编码（避免随代码提交泄露） */
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String DEEPSEEK_URL   = "https://api.deepseek.com/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    /** 多轮对话最多带上多少条历史（防止上下文过长、超 token 上限） */
    private static final int MAX_CHAT_HISTORY = 20;

    /**
     * POST /api/assistant/chat —— 智能问答（DeepSeek 大模型，支持多轮对话）。
     * 请求体：{messages: [{role:'user'|'assistant', content:'...'}, ...]}。
     * 前端维护对话历史，每次把整段历史（含新问题）传过来；后端加一条系统提示后转发给 DeepSeek。
     * 返回：{answer, action:null}。
     */
    private static String chatJson(HttpExchange ex) throws Exception {
        String raw = readBody(ex);
        Map<String, String> body = Json.parseObject(raw);
        List<Map<String, String>> msgs = Json.parseObjectArray(Json.arrayText(raw, "messages"));
        if (msgs.isEmpty()) {
            // 兼容旧前端：只传 {question} 没有 messages 时，当作单轮提问
            String question = body.get("question");
            if (question != null && !question.trim().isEmpty()) {
                Map<String, String> m = new HashMap<>();
                m.put("role", "user");
                m.put("content", question.trim());
                msgs.add(m);
            }
        }
        String user = body.get("user");
        String conversationIdStr = body.get("conversationId");

        // 只保留 user/assistant 且内容非空的消息，最多 MAX_CHAT_HISTORY 条（保留最近的）
        List<Map<String, String>> valid = new ArrayList<>();
        for (int i = Math.max(0, msgs.size() - MAX_CHAT_HISTORY); i < msgs.size(); i++) {
            Map<String, String> m = msgs.get(i);
            String role = m.get("role");
            String content = m.get("content");
            if (content == null || content.trim().isEmpty()) continue;
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            valid.add(m);
        }
        if (valid.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少对话内容：messages 需含 role/content 的消息") + "}";
        }

        String answer;
        try {
            answer = deepseekChat(valid);
        } catch (Exception e) {
            return "{\"code\":1,\"msg\":" + Json.str("大模型调用失败：" + e.getMessage()) + "}";
        }

        // 落库（按用户名隔离）；未传 user（未登录）则不保存，仅返回回答
        Long conversationId = null;
        if (user != null && !user.trim().isEmpty()) {
            try {
                conversationId = persistChat(user.trim(), conversationIdStr, valid, answer);
            } catch (SQLException e) {
                return "{\"code\":1,\"msg\":" + Json.str("对话记录保存失败：" + e.getMessage()) + "}";
            }
        }

        return "{\"code\":0,\"data\":{\"answer\":" + Json.str(answer)
                + ",\"action\":null,\"conversationId\":"
                + (conversationId == null ? "null" : conversationId) + "}}";
    }

    /**
     * 把本轮对话落库：无 conversationId 则新建会话（标题取首个用户问题），
     * 写入本轮最新的用户问题 + 助手回答，返回会话 id。
     * 会话不属于该用户 / 会话不存在时返回 null（本次不落库）。
     */
    private static Long persistChat(String user, String conversationIdStr,
                                    List<Map<String, String>> msgs, String answer) throws SQLException {
        Long conversationId = null;
        if (conversationIdStr != null && !conversationIdStr.isEmpty()) {
            try {
                conversationId = Long.parseLong(conversationIdStr);
            } catch (NumberFormatException e) {
                conversationId = null;
            }
        }
        // 本轮新问题 = 最后一条用户消息（前面的历史已入库）
        String question = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Map<String, String> m = msgs.get(i);
            if ("user".equals(m.get("role"))) { question = m.get("content"); break; }
        }
        if (question == null || question.trim().isEmpty()) return conversationId;

        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (conversationId == null) {
                    // 新建会话：标题取首个用户问题（截断到 100 字）
                    String title = question.trim();
                    if (title.length() > 100) title = title.substring(0, 100);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO conversation(username, title) VALUES (?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, user);
                        ps.setString(2, title);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) conversationId = keys.getLong(1);
                        }
                    }
                } else {
                    // 已有会话：校验归属
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT 1 FROM conversation WHERE id=? AND username=?")) {
                        ps.setLong(1, conversationId);
                        ps.setString(2, user);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                conn.rollback();
                                return null;
                            }
                        }
                    }
                }
                if (conversationId != null) {
                    insertMessage(conn, conversationId, "user", question.trim());
                    insertMessage(conn, conversationId, "assistant", answer);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return conversationId;
    }

    private static void insertMessage(Connection conn, long conversationId, String role, String content) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat_message(conversation_id, role, content) VALUES (?, ?, ?)")) {
            ps.setLong(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.executeUpdate();
        }
    }

    /** 调 DeepSeek 的 OpenAI 兼容接口：系统提示（含实时数据）+ 对话历史 → 返回模型回答 */
    private static String deepseekChat(List<Map<String, String>> msgs) throws Exception {
        // 未配置 key 时给出明确提示（启动前需 set DEEPSEEK_API_KEY=sk-xxx）
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isEmpty()) {
            throw new IOException("未配置环境变量 DEEPSEEK_API_KEY");
        }
        // 系统提示：基础角色 + 最新一次采集的温湿度/光照数据（让回答结合实时数据）
        String dataCtx = latestReadingsContext();
        String system = "你是「智慧农业平台」的智能助手，负责解答大棚种植、灌溉、温湿度监测、告警阈值、设备控制等农业问题。"
                      + "回答要简洁实用、直接给出建议；只回答与农业种植相关的问题，无关问题礼貌说明无法回答。";
        if (!dataCtx.isEmpty()) {
            system += " 当前系统实时采集的环境数据如下：" + dataCtx
                    + " 回答灌溉、通风等建议时可参考这些数据，但不要编造未提供的数据。";
        }

        StringBuilder req = new StringBuilder();
        req.append("{\"model\":\"").append(DEEPSEEK_MODEL)
           .append("\",\"temperature\":0.7,\"messages\":[");
        req.append("{\"role\":\"system\",\"content\":")
           .append(Json.str(system))
           .append('}');
        for (Map<String, String> m : msgs) {
            req.append(",{\"role\":").append(Json.str(m.get("role")))
               .append(",\"content\":").append(Json.str(m.get("content"))).append('}');
        }
        req.append("]}");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEEPSEEK_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(req.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + ": "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body));
        }
        String content = Json.strValue(body, "content");
        if (content == null) {
            throw new IOException("响应解析失败，未找到 content 字段");
        }
        return content;
    }

    /**
     * 汇总各指标最新一条传感器读数，拼成给大模型的实时环境数据上下文。
     * 返回形如「温度 30.75℃，湿度 54.41%，光照 390 lx（更新于 2026-08-23 11:50:30）」；
     * 完全没有数据时返回空串（系统提示就不加数据段）。
     */
    private static String latestReadingsContext() throws SQLException {
        String sql =
                "SELECT metric, value, DATE_FORMAT(collected_at, '%Y-%m-%d %H:%i:%s') AS t" +
                " FROM sensor_data WHERE id IN (" +
                "  SELECT MAX(id) FROM sensor_data WHERE metric IN ('temp','humidity','lux') GROUP BY metric)";
        String temp = null, humidity = null, lux = null, time = null;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String metric = rs.getString("metric");
                String v = rs.getBigDecimal("value").stripTrailingZeros().toPlainString();
                if (time == null) time = rs.getString("t");
                if ("temp".equals(metric)) temp = v;
                else if ("humidity".equals(metric)) humidity = v;
                else if ("lux".equals(metric)) lux = v;
            }
        }
        if (temp == null && humidity == null && lux == null) return "";

        StringBuilder sb = new StringBuilder("最近一次采集");
        if (time != null) sb.append("（").append(time).append('）');
        sb.append("：");
        if (temp != null) sb.append("温度 ").append(temp).append("℃，");
        if (humidity != null) sb.append("湿度 ").append(humidity).append("%，");
        if (lux != null) sb.append("光照 ").append(lux).append(" lx");
        return sb.toString();
    }

    /* ==================================================================
       对话历史（按用户隔离）
       ================================================================== */

    /** 从 query string 里取参数值；没有返回 null */
    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            String[] p = kv.split("=");
            if (p.length == 2 && key.equals(p[0])) return p[1];
        }
        return null;
    }

    /**
     * GET /api/conversations?user=xxx —— 当前用户的对话列表（按最近更新倒序）。
     * 返回 [{id,title,updatedAt,messageCount},...]。
     */
    private static String conversationsJson(String query) throws SQLException {
        String user = queryParam(query, "user");
        if (user == null || user.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少参数：user 必填") + "}";
        }
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT c.id, c.title, c.updated_at," +
                " (SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id = c.id) AS msgCount" +
                " FROM conversation c WHERE c.username = ? ORDER BY c.updated_at DESC, c.id DESC";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    Timestamp ts = rs.getTimestamp("updated_at");
                    sb.append('{')
                      .append("\"id\":").append(rs.getLong("id")).append(',')
                      .append("\"title\":").append(Json.str(rs.getString("title"))).append(',')
                      .append("\"updatedAt\":").append(Json.str(ts == null ? "" : fmt.format(ts))).append(',')
                      .append("\"messageCount\":").append(rs.getInt("msgCount"))
                      .append('}');
                }
            }
        }
        return sb.append("]}").toString();
    }

    /**
     * GET /api/conversations/{id}?user=xxx —— 加载某次对话的完整上下文（含全部消息）。
     * 校验会话归属该用户；不属于/不存在返回错误。
     */
    private static String conversationMessagesJson(String idStr, String query) throws SQLException {
        String user = queryParam(query, "user");
        if (user == null || user.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少参数：user 必填") + "}";
        }
        long convId;
        try {
            convId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
        }
        String title = null;
        String sqlC = "SELECT title FROM conversation WHERE id = ? AND username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlC)) {
            ps.setLong(1, convId);
            ps.setString(2, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"code\":1,\"msg\":" + Json.str("对话不存在") + "}";
                title = rs.getString("title");
            }
        }
        StringBuilder msgs = new StringBuilder();
        String sqlM = "SELECT role, content FROM chat_message WHERE conversation_id = ? ORDER BY id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlM)) {
            ps.setLong(1, convId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) msgs.append(',');
                    first = false;
                    msgs.append('{')
                        .append("\"role\":").append(Json.str(rs.getString("role")))
                        .append(",\"content\":").append(Json.str(rs.getString("content")))
                        .append('}');
                }
            }
        }
        return "{\"code\":0,\"data\":{\"id\":" + convId
                + ",\"title\":" + Json.str(title)
                + ",\"messages\":[" + msgs + "]}}";
    }

    /**
     * DELETE /api/conversations/{id}?user=xxx —— 删除某次对话（连同消息）。
     * 校验归属；不属于/不存在返回错误。
     */
    private static String deleteConversationJson(String idStr, String query) throws SQLException {
        String user = queryParam(query, "user");
        if (user == null || user.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少参数：user 必填") + "}";
        }
        long convId;
        try {
            convId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
        }
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM conversation WHERE id=? AND username=?")) {
                    ps.setLong(1, convId);
                    ps.setString(2, user);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return "{\"code\":1,\"msg\":" + Json.str("对话不存在") + "}";
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM chat_message WHERE conversation_id=?")) {
                    ps.setLong(1, convId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM conversation WHERE id=?")) {
                    ps.setLong(1, convId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return "{\"code\":0}";
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
       注册申请审核（管理员）
       ================================================================== */

    /** GET /api/register-requests —— 注册申请列表（待审核的排前面） */
    private static String registerRequestsJson() throws SQLException {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql = "SELECT id, username, role, status, reject_reason, created_at, reviewed_at, reviewer"
                + " FROM register_request ORDER BY (status = '待审核') DESC, created_at DESC";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                Timestamp ts = rs.getTimestamp("created_at");
                Timestamp rt = rs.getTimestamp("reviewed_at");
                sb.append('{')
                  .append("\"id\":").append(rs.getLong("id")).append(',')
                  .append("\"username\":").append(Json.str(rs.getString("username"))).append(',')
                  .append("\"role\":").append(Json.str(rs.getString("role"))).append(',')
                  .append("\"roleName\":").append(Json.str(roleName(rs.getString("role")))).append(',')
                  .append("\"status\":").append(Json.str(rs.getString("status"))).append(',')
                  .append("\"rejectReason\":").append(Json.str(rs.getString("reject_reason"))).append(',')
                  .append("\"createdAt\":").append(Json.str(ts == null ? "" : fmt.format(ts))).append(',')
                  .append("\"reviewedAt\":").append(Json.str(rt == null ? "" : fmt.format(rt))).append(',')
                  .append("\"reviewer\":").append(Json.str(rs.getString("reviewer")))
                  .append('}');
            }
        }
        return sb.append("]}").toString();
    }

    /**
     * PUT /api/register-requests/{id} —— 审核注册申请。
     * body: {status:'已通过'|'已拒绝', name?(通过时显示名,默认账号名), rejectReason?(拒绝原因)}。
     * 通过：写入 user 表（沿用申请时存的密码哈希，登录无需改密）。
     */
    private static String reviewRegisterJson(String id, HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String status = body.get("status");
        if (status == null || (!"已通过".equals(status) && !"已拒绝".equals(status))) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：status 需为 已通过 或 已拒绝") + "}";
        }
        long reqId;
        try { reqId = Long.parseLong(id); } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
        }
        String reviewer = body.get("reviewer");
        if (reviewer == null || reviewer.isEmpty()) reviewer = "管理员";

        // 取申请记录
        String username = null, password = null, role = null;
        String sel = "SELECT username, password, role FROM register_request WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sel)) {
            ps.setLong(1, reqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"code\":1,\"msg\":" + Json.str("申请不存在: " + id) + "}";
                username = rs.getString("username");
                password = rs.getString("password");
                role = rs.getString("role");
            }
        }
        if (username == null || password == null || role == null) {
            return "{\"code\":1,\"msg\":" + Json.str("申请数据不完整，无法审核") + "}";
        }

        if ("已通过".equals(status)) {
            if (exists("SELECT 1 FROM user WHERE username = ?", username)) {
                return "{\"code\":1,\"msg\":" + Json.str("账号已存在：" + username + "（可能已审核过）") + "}";
            }
            String name = body.get("name");
            if (name == null || name.isEmpty()) name = username;
            long userId = 0L;
            String insUser = "INSERT INTO user(username, password, name, role) VALUES (?, ?, ?, ?)";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(insUser, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, password);
                ps.setString(3, name);
                ps.setString(4, role);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) userId = keys.getLong(1);
                }
            }
            String upd = "UPDATE register_request SET status = '已通过', reviewed_at = NOW(), reviewer = ?, user_id = ? WHERE id = ?";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(upd)) {
                ps.setString(1, reviewer);
                ps.setLong(2, userId);
                ps.setLong(3, reqId);
                ps.executeUpdate();
            }
            return "{\"code\":0,\"msg\":" + Json.str("已通过，「" + username + "」可登录使用") + "}";
        }

        // 已拒绝
        String reason = body.get("rejectReason");
        String upd2 = "UPDATE register_request SET status = '已拒绝', reject_reason = ?, reviewed_at = NOW(), reviewer = ? WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(upd2)) {
            ps.setString(1, reason);
            ps.setString(2, reviewer);
            ps.setLong(3, reqId);
            ps.executeUpdate();
        }
        return "{\"code\":0,\"msg\":" + Json.str("已拒绝「" + username + "」的申请") + "}";
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

    /* ==================================================================
       板子手动刷新
       ================================================================== */

    /**
     * POST /api/board/refresh —— 手动刷新：立即读一次板子并写库，返回最新读数。
     * 前端刷新按钮调用，让板子日志能立刻看到一次请求（不用等采集器 30 秒周期）。
     */
    private static String refreshBoardJson() {
        Map<String, String> r = BoardCollector.refreshNow();
        if (r == null) {
            return "{\"code\":1,\"msg\":" + Json.str("板子读取失败：" + BoardCollector.getLastError()) + "}";
        }
        return "{\"code\":0,\"data\":{"
                + "\"temp\":" + Json.num(r.get("temp"))
                + ",\"humidity\":" + Json.num(r.get("humidity"))
                + ",\"lux\":" + Json.num(r.get("lux"))
                + ",\"updatedAt\":" + Json.str(java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                + "}}";
    }

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
    static String plotOfDevice(String deviceId) throws SQLException {
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
    static void checkThresholdAlarm(String plotId, String metric, BigDecimal value) throws SQLException {
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
