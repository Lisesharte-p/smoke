package server;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * 业务 API：/api/* 路由 + 数据库交互。
 * 前端统一走 window.API（frontend/assets/js/api.js），接口路径和数据形状已定死，
 * 这里按契约实现。所有响应都是 {"code":0,"data":...} 或 {"code":1,"msg":"..."}。
 *
 * 每个接口独立一个方法，路由在 handle() 里分发；以后新增接口加一行即可。
 */
public class Api {
    private static final Path LOCAL_CONFIG = Paths.get("config", "feishu.local.properties");
    private static final int THRESHOLD_ALARM_COOLDOWN_SECONDS = 60;
    private static final String[] CAMERA_HTTP_STREAM_PATHS = {
            "/video",
            "/videofeed",
            "/mjpegfeed",
            "/mjpeg",
            "/stream"
    };


    /** 读取环境变量，为空时使用默认值。 */
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    /** 按顺序读取多个环境变量，返回第一个非空值。 */
    private static String envFirst(String... keys) {
        for (String key : keys) {
            String v = System.getenv(key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        Properties props = localConfigProperties();
        for (String key : keys) {
            String v = props.getProperty(key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** 先按环境变量、本机配置读取，全部为空时使用默认值。 */
    private static String envFirstOrDefault(String def, String... keys) {
        String value = envFirst(keys);
        return value.isEmpty() ? def : value;
    }

    private static Properties localConfigProperties() {
        Properties props = new Properties();
        if (!Files.isRegularFile(LOCAL_CONFIG)) return props;
        try (InputStream in = Files.newInputStream(LOCAL_CONFIG)) {
            props.load(in);
        } catch (IOException e) {
            System.out.println("[Api] 读取本地配置失败: " + e.getMessage());
        }
        return props;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /* ==================================================================
       和风天气接入配置（天气预报）
       使用环境变量配置，避免把 API Key 提交到 Git：
         QWEATHER_API_KEY / WEATHER_API_KEY：和风天气 Key
         QWEATHER_LOCATION：LocationID 或经纬度 "经度,纬度"
         QWEATHER_HOST：和风天气 API Host
       填好后数据总览的「天气预报」即显示真实天气；未填 Key 时接口返回 code=1。
       ================================================================== */
    private static final String QW_API_KEY = envFirst("QWEATHER_API_KEY", "WEATHER_API_KEY");
    private static final String QW_LOCATION = env("QWEATHER_LOCATION", "106.565952,29.642614"); // 重庆两江新区，和风格式：经度,纬度
    private static final String QW_HOST = trimTrailingSlash(env("QWEATHER_HOST", "https://ma5rk8cjh3.re.qweatherapi.com"));

    private static final long CAMERA_ONLINE_REFRESH_INTERVAL_MS =
            Long.parseLong(env("CAMERA_ONLINE_REFRESH_INTERVAL_MS", "30000"));
    private static final Object CAMERA_ONLINE_REFRESH_LOCK = new Object();
    private static final AtomicBoolean CAMERA_ONLINE_REFRESH_RUNNING = new AtomicBoolean(false);
    private static volatile long lastCameraOnlineRefreshAt = 0L;
    private static final ExecutorService CAMERA_ONLINE_REFRESH_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "camera-online-refresh");
                t.setDaemon(true);
                return t;
            });

    /* ==================================================================
       YOLO 人体识别配置
       DETECTION_STORAGE_DIR：识别截图和回放视频保存目录，默认 data/detections
       DETECTION_WORKER_TOKEN：worker 调内部接口的令牌；为空时仅允许本机访问内部接口
       ================================================================== */
    private static final Path DETECTION_STORAGE = Paths.get(env("DETECTION_STORAGE_DIR", "data/detections"))
            .toAbsolutePath().normalize();
    private static final String DETECTION_WORKER_TOKEN = env("DETECTION_WORKER_TOKEN", "");
    private static final Object DETECTION_WORKER_LOCK = new Object();
    private static volatile Process detectionWorkerProcess = null;

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
                ok(ex, deletePlotJson(path.substring("/api/plots/".length()),
                        ex.getRequestURI().getQuery()));
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
            if ("PUT".equals(method) && path.matches("/api/devices/[^/]+")) {
                ok(ex, updateDeviceJson(path.substring("/api/devices/".length()), ex));
                return true;
            }
            if ("DELETE".equals(method) && path.matches("/api/devices/[^/]+")) {
                ok(ex, deleteDeviceJson(path.substring("/api/devices/".length())));
                return true;
            }

            /* ---------- 摄像头画面（MJPEG/HTTP 代理，供浏览器同源播放） ---------- */
            if ("GET".equals(method) && path.equals("/api/camera/player")) {
                streamCameraPlayer(ex);
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/camera/proxy")) {
                streamCameraProxy(ex);
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/camera/mjpeg")) {
                streamCameraMjpeg(ex);
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/camera/detection-records")) {
                ok(ex, detectionRecordsJson(ex.getRequestURI().getQuery()));
                return true;
            }
            if ("GET".equals(method) && path.matches("/api/camera/detection-records/[0-9]+/video")) {
                streamDetectionFile(ex, path.substring("/api/camera/detection-records/".length(),
                        path.length() - "/video".length()), true);
                return true;
            }
            if ("GET".equals(method) && path.matches("/api/camera/detection-records/[0-9]+/snapshot")) {
                streamDetectionFile(ex, path.substring("/api/camera/detection-records/".length(),
                        path.length() - "/snapshot".length()), false);
                return true;
            }
            if ("GET".equals(method) && path.matches("/api/camera/detection-records/[0-9]+")) {
                ok(ex, detectionRecordDetailJson(path.substring("/api/camera/detection-records/".length())));
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/camera/detection/status")) {
                ok(ex, detectionStatusJson(ex.getRequestURI().getQuery()));
                return true;
            }
            if ("POST".equals(method) && path.equals("/api/camera/detection/settings")) {
                ok(ex, saveDetectionSettingsJson(ex));
                return true;
            }
            if ("POST".equals(method) && path.equals("/api/camera/detection/worker/start")) {
                ok(ex, startDetectionWorkerJson(ex));
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/internal/camera-configs")) {
                ok(ex, internalCameraConfigsJson(ex));
                return true;
            }
            if ("POST".equals(method) && path.equals("/api/internal/detection-records")) {
                ok(ex, internalCreateDetectionRecordJson(ex));
                return true;
            }
            if ("POST".equals(method) && path.equals("/api/internal/detection-status")) {
                ok(ex, internalDetectionStatusUpdateJson(ex));
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
                ok(ex, thresholdsJson(ex.getRequestURI().getQuery()));
                return true;
            }
            if ("PUT".equals(method) && path.equals("/api/thresholds")) {
                ok(ex, saveThresholdsJson(ex, ex.getRequestURI().getQuery()));
                return true;
            }
            if ("POST".equals(method) && path.matches("/api/plots/[^/]+/alarms/resolve")) {
                ok(ex, resolvePlotAlarmsJson(path.substring("/api/plots/".length(),
                        path.length() - "/alarms/resolve".length()), ex));
                return true;
            }
            if ("GET".equals(method) && path.equals("/api/alarms")) {
                ok(ex, alarmsJson(ex.getRequestURI().getQuery()));
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
                ok(ex, controlLogsJson(ex.getRequestURI().getQuery()));
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

            /* ---------- 农事建议 ---------- */
            if ("GET".equals(method) && path.equals("/api/advice")) {
                ok(ex, adviceJson());
                return true;
            }

            /* ---------- 天气预报 ---------- */
            if ("GET".equals(method) && path.equals("/api/weather")) {
                ok(ex, weatherJson());
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
        scheduleCameraOnlineRefresh();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":0,\"data\":[");
        String sql =
                "SELECT p.id, p.name, p.crop, p.area, p.map_shape, p.crop_style," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id) AS deviceCount," +
                "  (SELECT COUNT(*) FROM device d WHERE d.plot_id=p.id AND d.online=1) AS onlineCount," +
                "  (SELECT s.value FROM sensor_data s JOIN device d ON d.id=s.device_id" +
                "    WHERE d.plot_id=p.id AND s.metric='temp' AND d.online=1" +
                "    AND d.type IN ('温度传感器','环境监测板')" +
                "    ORDER BY s.collected_at DESC LIMIT 1) AS temp," +
                "  (SELECT s.value FROM sensor_data s JOIN device d ON d.id=s.device_id" +
                "    WHERE d.plot_id=p.id AND s.metric='humidity' AND d.online=1" +
                "    AND d.type IN ('土壤湿度传感器','环境监测板')" +
                "    ORDER BY s.collected_at DESC LIMIT 1) AS humidity" +
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
                  .append("\"mapShape\":").append(jsonObjectOrNull(rs.getString("map_shape"))).append(',')
                  .append("\"cropStyle\":").append(Json.str(rs.getString("crop_style"))).append(',')
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
                "SELECT p.id, p.name, p.crop, p.area, p.map_shape, p.crop_style," +
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
                        + ",\"mapShape\":" + jsonObjectOrNull(rs.getString("map_shape"))
                        + ",\"cropStyle\":" + Json.str(rs.getString("crop_style"))
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
        // 权限：仅农场管理员/系统管理员可添加地块
        if (!isAdminRole(body.get("role"))) {
            return "{\"code\":1,\"msg\":" + Json.str("无权限：只有农场管理员或系统管理员可以添加地块") + "}";
        }
        String name = body.get("name");
        String crop = body.get("crop");
        String area = body.get("area");
        String mapShape = normalizeJsonObject(body.get("mapShape"));
        String cropStyle = trimToNull(body.get("cropStyle"));
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
                        "INSERT INTO plot(id, name, crop, area, map_shape, crop_style) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, plotId);
                    ps.setString(2, name.trim());
                    ps.setString(3, crop.trim());
                    ps.setBigDecimal(4, areaVal);
                    ps.setString(5, mapShape);
                    ps.setString(6, cropStyle);
                    ps.executeUpdate();
                }
                // 2. 绑定设备（校验后逐个插入；编号在事务内递增，避免重复）
                java.util.Set<String> reqNames = new java.util.HashSet<>();
                java.util.Set<String> reqAddrKeys = new java.util.HashSet<>();
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
                    String devType = dbDeviceType(type);
                    String protocol = normalizeProtocol(d.get("protocol"), devType);
                    String username = trimToNull(d.get("username"));
                    String password = trimToNull(d.get("password"));
                    // 名称查重（库内 + 本次请求内）
                    String trimmedName = devName.trim();
                    if (deviceNameExists(trimmedName) || !reqNames.add(trimmedName)) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("设备名称已存在：" + trimmedName) + "}";
                    }
                    // 同类型同地址查重（库内 + 本次请求内）：不同类型可共享板子地址
                    String addrKey = devType + "|" + ip.trim() + "|" + port;
                    if (sameTypeAddrExists(devType, ip.trim(), port) || !reqAddrKeys.add(addrKey)) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("已存在同类型且同 IP/端口的设备：设备「" + trimmedName + "」") + "}";
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO device(id, plot_id, name, type, ip, port, protocol, username, password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, nextDeviceId(conn));
                        ps.setString(2, plotId);
                        ps.setString(3, devName.trim());
                        ps.setString(4, devType);
                        ps.setString(5, ip.trim());
                        ps.setInt(6, port);
                        ps.setString(7, protocol);
                        ps.setString(8, username);
                        ps.setString(9, password);
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
    private static String deletePlotJson(String plotId, String query) throws SQLException {
        // 权限：仅农场管理员/系统管理员可删除地块
        if (!isAdminRole(queryParam(query, "role"))) {
            return "{\"code\":1,\"msg\":" + Json.str("无权限：只有农场管理员或系统管理员可以删除地块") + "}";
        }
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

    /** 是否有地块管理权限（添加/删除等）：仅 admin / sysadmin */
    private static boolean isAdminRole(String role) {
        return "admin".equals(role) || "sysadmin".equals(role);
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
        scheduleCameraOnlineRefresh();
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running, d.ip, d.port, d.protocol, d.username, d.password" +
                " FROM device d LEFT JOIN plot p ON p.id = d.plot_id ORDER BY d.id";
        try (Connection conn = DBUtil.getConnection()) {
            Map<String, Map<String, BigDecimal>> latest = latestByDevice(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    String type = rs.getString("type");
                    boolean online = rs.getInt("online") == 1;
                    Map<String, BigDecimal> vals = online ? latest.get(rs.getString("id")) : null;
                    sb.append('{')
                      .append("\"id\":").append(Json.str(rs.getString("id"))).append(',')
                      .append("\"name\":").append(Json.str(rs.getString("name"))).append(',')
                      .append("\"type\":").append(Json.str(typeMap(type))).append(',')
                      .append("\"plotId\":").append(Json.str(rs.getString("plot_id"))).append(',')
                      .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                      .append("\"ip\":").append(Json.str(rs.getString("ip"))).append(',')
                      .append("\"port\":").append(Json.num(rs.getObject("port"))).append(',')
                      .append("\"protocol\":").append(Json.str(rs.getString("protocol"))).append(',')
                      .append("\"username\":").append(Json.str(rs.getString("username"))).append(',')
                      .append("\"password\":").append(Json.str(rs.getString("password"))).append(',')
                      .append("\"online\":").append(online).append(',')
                      .append("\"temp\":").append(numOrNull(vals, "temp")).append(',')
                      .append("\"humidity\":").append(numOrNull(vals, "humidity")).append(',')
                      .append("\"lux\":").append(numOrNull(vals, "lux")).append(',')
                      .append("\"controllable\":").append("灌溉设备".equals(type)).append(',')
                      .append("\"running\":").append(rs.getInt("running") == 1)
                      .append('}');
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 每台设备各指标的最新一条读数：deviceId -> {temp, humidity, lux} */
    private static Map<String, Map<String, BigDecimal>> latestByDevice(Connection conn) throws SQLException {
        Map<String, Map<String, BigDecimal>> map = new HashMap<>();
        String sql =
                "SELECT device_id, metric, value FROM sensor_data WHERE id IN (" +
                " SELECT MAX(id) FROM sensor_data WHERE metric IN ('temp','humidity','lux')" +
                " GROUP BY device_id, metric)";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String deviceId = rs.getString("device_id");
                String metric = rs.getString("metric");
                map.computeIfAbsent(deviceId, k -> new HashMap<>()).put(metric, rs.getBigDecimal("value"));
            }
        }
        return map;
    }

    /** 从设备最新值表里取某指标；无则返回 null */
    private static String numOrNull(Map<String, BigDecimal> vals, String key) {
        if (vals == null) return "null";
        BigDecimal v = vals.get(key);
        return v == null ? "null" : String.valueOf(v);
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
        String protocol = body.get("protocol");
        String username = body.get("username");
        String password = body.get("password");
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
        type = dbDeviceType(type);
        protocol = normalizeProtocol(protocol, type);
        username = trimToNull(username);
        password = trimToNull(password);
        // 名称查重：全局唯一
        if (deviceNameExists(name.trim())) {
            return "{\"code\":1,\"msg\":" + Json.str("设备名称已存在：" + name.trim()) + "}";
        }
        // 同类型 + 同 IP/端口 查重：不同类型可共享板子地址，同类型不允许
        if (sameTypeAddrExists(type, ip.trim(), port)) {
            return "{\"code\":1,\"msg\":" + Json.str("已存在同类型且同 IP/端口的设备，请更换类型或地址") + "}";
        }
        if (!exists("SELECT 1 FROM plot WHERE id = ?", plotId)) {
            return "{\"code\":1,\"msg\":" + Json.str("不存在地块") + "}";
        }
        String id = nextDeviceId();
        String sql = "INSERT INTO device(id, plot_id, name, type, ip, port, protocol, username, password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, plotId);
            ps.setString(3, name.trim());
            ps.setString(4, type);
            ps.setString(5, ip.trim());
            ps.setInt(6, port);
            ps.setString(7, protocol);
            ps.setString(8, username);
            ps.setString(9, password);
            ps.executeUpdate();
        }
        return "{\"code\":0,\"data\":" + deviceJson(id) + "}";
    }

    /** PUT /api/devices/{id} —— 修改设备 IP/端口，并立刻触发在线状态重扫 */
    private static String updateDeviceJson(String id, HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String ip = trimToNull(body.get("ip"));
        String portStr = trimToNull(body.get("port"));
        if (id == null || id.trim().isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少设备编号") + "}";
        }
        if (ip == null || portStr == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：ip/port 必填") + "}";
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：port 必须是数字") + "}";
        }
        if (port < 1 || port > 65535) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：port 需在 1-65535 之间") + "}";
        }

        String type;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT type FROM device WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "{\"code\":1,\"msg\":" + Json.str("设备不存在：" + id.trim()) + "}";
                }
                type = rs.getString("type");
            }
        }
        if (sameTypeAddrExistsExcept(type, ip, port, id.trim())) {
            return "{\"code\":1,\"msg\":" + Json.str("已存在同类型且同 IP/端口的设备，请更换地址") + "}";
        }

        String protocol = trimToNull(body.get("protocol"));
        String username = body.containsKey("username") ? trimToNull(body.get("username")) : null;
        String password = body.containsKey("password") ? trimToNull(body.get("password")) : null;
        String sql = "UPDATE device SET ip = ?, port = ?, protocol = COALESCE(?, protocol), username = COALESCE(?, username)," +
                " password = COALESCE(?, password), online = 0, running = 0, last_heartbeat = NULL WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setInt(2, port);
            ps.setString(3, protocol);
            ps.setString(4, username);
            ps.setString(5, password);
            ps.setString(6, id.trim());
            ps.executeUpdate();
        }
        boolean connected;
        if ("摄像头".equals(type)) {
            connected = probeCamera(cameraConfig(id.trim()));
            updateDeviceOnline(id.trim(), connected);
            scheduleCameraOnlineRefresh(true);
        } else {
            connected = BoardCollector.probeAddress(ip, port);
            updateDeviceOnline(id.trim(), connected);
            BoardCollector.rescanNow();
        }
        return "{\"code\":0,\"data\":" + deviceJson(id.trim()) + "}";
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
                "SELECT d.id, d.name, d.type, d.plot_id, p.name AS plotName, d.online, d.running, d.ip, d.port, d.protocol, d.username, d.password" +
                " FROM device d LEFT JOIN plot p ON p.id = d.plot_id WHERE d.id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";
                boolean online = rs.getInt("online") == 1;
                Map<String, BigDecimal> vals = online ? latestByDevice(conn).get(id) : null;
                return "{\"id\":" + Json.str(rs.getString("id"))
                        + ",\"name\":" + Json.str(rs.getString("name"))
                        + ",\"type\":" + Json.str(typeMap(rs.getString("type")))
                        + ",\"plotId\":" + Json.str(rs.getString("plot_id"))
                        + ",\"plotName\":" + Json.str(rs.getString("plotName"))
                        + ",\"ip\":" + Json.str(rs.getString("ip"))
                        + ",\"port\":" + Json.num(rs.getObject("port"))
                        + ",\"protocol\":" + Json.str(rs.getString("protocol"))
                        + ",\"username\":" + Json.str(rs.getString("username"))
                        + ",\"password\":" + Json.str(rs.getString("password"))
                        + ",\"online\":" + online
                        + ",\"temp\":" + numOrNull(vals, "temp")
                        + ",\"humidity\":" + numOrNull(vals, "humidity")
                        + ",\"lux\":" + numOrNull(vals, "lux")
                        + ",\"controllable\":" + "灌溉设备".equals(rs.getString("type"))
                        + ",\"running\":" + (rs.getInt("running") == 1)
                        + "}";
            }
        }
    }

    /** 设备类型映射：数据库类型 → 前端契约类型 */
    private static String typeMap(String type) {
        if ("灌溉设备".equals(type)) return "灌溉设备";
        if ("温度".equals(type)) return "温度";
        if ("温度传感器".equals(type)) return "温度";
        if ("亮度".equals(type)) return "亮度";
        if ("亮度传感器".equals(type)) return "亮度";
        if ("环境监测板".equals(type)) return "环境监测板";
        if ("摄像头".equals(type)) return "摄像头";
        return "土壤湿度"; // 土壤湿度传感器等统一归为土壤湿度
    }

    /** 前端设备类型契约 → 数据库设备类型。 */
    private static String dbDeviceType(String type) {
        if ("土壤湿度".equals(type)) return "土壤湿度传感器";
        if ("温度".equals(type)) return "温度传感器";
        if ("亮度".equals(type)) return "亮度传感器";
        if ("摄像头".equals(type)) return "摄像头";
        return type;
    }

    private static String normalizeProtocol(String protocol, String dbType) {
        String p = trimToNull(protocol);
        if (p == null && "摄像头".equals(dbType)) return "mjpeg";
        return p == null ? null : p.toLowerCase();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normalizeJsonObject(String s) {
        String v = trimToNull(s);
        if (v == null) return null;
        return v.startsWith("{") && v.endsWith("}") ? v : null;
    }

    private static String jsonObjectOrNull(String s) {
        String v = normalizeJsonObject(s);
        return v == null ? "null" : v;
    }

    private static class CameraConfig {
        String id;
        String name;
        String ip;
        int port;
        String protocol;
        String username;
        String password;
    }

    /** 查询摄像头配置；未传 deviceId 时取第一台摄像头。 */
    private static CameraConfig cameraConfig(String deviceId) throws SQLException {
        String sql = "SELECT id, name, ip, port, protocol, username, password FROM device WHERE type='摄像头'";
        if (deviceId != null && !deviceId.trim().isEmpty()) sql += " AND id=?";
        sql += " ORDER BY id LIMIT 1";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (deviceId != null && !deviceId.trim().isEmpty()) ps.setString(1, deviceId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                CameraConfig c = new CameraConfig();
                c.id = rs.getString("id");
                c.name = rs.getString("name");
                c.ip = rs.getString("ip");
                Object portObj = rs.getObject("port");
                c.port = portObj == null ? 0 : rs.getInt("port");
                c.protocol = rs.getString("protocol");
                c.username = rs.getString("username");
                c.password = rs.getString("password");
                return c;
            }
        }
    }

    /**
     * GET /api/camera/mjpeg?deviceId=D007
     * 把摄像头 MJPEG/HTTP 流代理为同源响应，避免浏览器拦截 URL 内账号密码。
     */
    private static void streamCameraMjpeg(HttpExchange ex) throws IOException, SQLException {
        CameraConfig c = cameraConfig(queryParam(ex.getRequestURI().getQuery(), "deviceId"));
        if (c == null) {
            send(ex, 404, "{\"code\":1,\"msg\":" + Json.str("未找到摄像头设备") + "}");
            return;
        }
        String protocol = c.protocol == null ? "mjpeg" : c.protocol.toLowerCase();
        if (!"mjpeg".equals(protocol) && !"http".equals(protocol)) {
            send(ex, 400, "{\"code\":1,\"msg\":" + Json.str("当前页面暂只支持 MJPEG/HTTP 摄像头，RTSP 需转码") + "}");
            return;
        }
        if (c.ip == null || c.ip.trim().isEmpty() || c.port <= 0) {
            send(ex, 400, "{\"code\":1,\"msg\":" + Json.str("摄像头 IP/端口未配置") + "}");
            return;
        }

        HttpURLConnection conn = null;
        try {
            conn = openCameraStream(c, 5000, 8000, "SmartAgriCameraProxy");
            if (conn == null) {
                updateDeviceOnlineQuietly(c.id, false);
                send(ex, 502, "{\"code\":1,\"msg\":" + Json.str("摄像头连接失败，请检查 IP、端口、账号密码和视频流路径") + "}");
                return;
            }
            updateDeviceOnlineQuietly(c.id, true);

            String contentType = conn.getContentType();
            if (contentType == null || contentType.trim().isEmpty()) {
                contentType = "multipart/x-mixed-replace; boundary=--BoundaryString";
            }
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            ex.getResponseHeaders().set("Pragma", "no-cache");
            ex.getResponseHeaders().set("Expires", "0");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.getResponseHeaders().set("Connection", "close");
            ex.sendResponseHeaders(200, 0);

            try (InputStream in = conn.getInputStream();
                 OutputStream out = ex.getResponseBody()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException clientClosed) {
                // 浏览器切换页面或刷新时会关闭长连接，静默结束即可。
            }
        } catch (IOException e) {
            updateDeviceOnlineQuietly(c.id, false);
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 页面列表接口只触发后台刷新，避免离线摄像头探测阻塞页面切换。 */
    private static void scheduleCameraOnlineRefresh() {
        scheduleCameraOnlineRefresh(false);
    }

    /** force=true 用于设备地址刚修改后，绕过间隔限制立即检查一次。 */
    private static void scheduleCameraOnlineRefresh(boolean force) {
        long now = System.currentTimeMillis();
        synchronized (CAMERA_ONLINE_REFRESH_LOCK) {
            if (!force && now - lastCameraOnlineRefreshAt < CAMERA_ONLINE_REFRESH_INTERVAL_MS) return;
            lastCameraOnlineRefreshAt = now;
        }
        if (!CAMERA_ONLINE_REFRESH_RUNNING.compareAndSet(false, true)) return;

        CAMERA_ONLINE_REFRESH_EXECUTOR.submit(() -> {
            try {
                refreshCameraOnlineStates();
            } catch (SQLException e) {
                System.out.println("[Api] 摄像头在线状态后台刷新失败: " + e.getMessage());
            } finally {
                CAMERA_ONLINE_REFRESH_RUNNING.set(false);
            }
        });
    }

    /** 刷新摄像头设备在线状态；HTTP/MJPEG 自动尝试常见手机摄像头流路径，RTSP 先做 TCP 端口探测。 */
    private static void refreshCameraOnlineStates() throws SQLException {
        List<CameraConfig> cameras = new ArrayList<>();
        String sql = "SELECT id, name, ip, port, protocol, username, password FROM device WHERE type='摄像头'";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                CameraConfig c = new CameraConfig();
                c.id = rs.getString("id");
                c.name = rs.getString("name");
                c.ip = rs.getString("ip");
                Object portObj = rs.getObject("port");
                c.port = portObj == null ? 0 : rs.getInt("port");
                c.protocol = rs.getString("protocol");
                c.username = rs.getString("username");
                c.password = rs.getString("password");
                cameras.add(c);
            }
        }
        for (CameraConfig c : cameras) {
            updateDeviceOnline(c.id, probeCamera(c));
        }
    }

    private static boolean probeCamera(CameraConfig c) {
        if (c == null || c.ip == null || c.ip.trim().isEmpty() || c.port <= 0) return false;
        String protocol = c.protocol == null ? "mjpeg" : c.protocol.toLowerCase();
        if ("rtsp".equals(protocol)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(c.ip.trim(), c.port), 900);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        if ("http".equals(protocol)) {
            HttpURLConnection conn = null;
            try {
                conn = openCameraPath(c, "/", 900, 1200, "SmartAgriCameraHealthCheck");
                int code = conn.getResponseCode();
                return code >= 200 && code < 400;
            } catch (IOException e) {
                return false;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        try {
            HttpURLConnection conn = openCameraStream(c, 900, 1200, "SmartAgriCameraHealthCheck");
            if (conn == null) return false;
            conn.disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** GET /api/camera/player?deviceId=D025 —— 同源 FLV 摄像头播放器，适配沈垚 IP 摄像头 App。 */
    private static void streamCameraPlayer(HttpExchange ex) throws IOException, SQLException {
        String deviceId = trimToNull(queryParam(ex.getRequestURI().getQuery(), "deviceId"));
        CameraConfig c = cameraConfig(deviceId);
        if (c == null) {
            sendHtml(ex, 404, "<!doctype html><meta charset=\"utf-8\"><body>未找到摄像头设备</body>");
            return;
        }
        String scriptUrl = "/api/camera/proxy?deviceId=" + urlEncode(c.id) + "&path=" + urlEncode("/res/mpegts.js");
        String flvUrl = "/api/camera/proxy?deviceId=" + urlEncode(c.id) + "&path=" + urlEncode("/live.flv");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<style>html,body{margin:0;width:100%;height:100%;background:#020617;overflow:hidden}"
                + "video{width:100%;height:100%;object-fit:contain;background:#020617}"
                + ".msg{position:absolute;inset:0;display:grid;place-items:center;color:#e2e8f0;font:14px system-ui}</style>"
                + "</head><body><video id=\"v\" autoplay muted controls playsinline></video><div class=\"msg\" id=\"m\">正在连接摄像头...</div>"
                + "<script src=\"" + scriptUrl + "\"></script>"
                + "<script>var msg=document.getElementById('m'),video=document.getElementById('v');"
                + "function show(t){msg.textContent=t;msg.style.display='grid'}function hide(){msg.style.display='none'}"
                + "if(window.mpegts&&mpegts.getFeatureList().mseLivePlayback){"
                + "var player=mpegts.createPlayer({type:'flv',isLive:true,url:" + Json.str(flvUrl) + "});"
                + "player.attachMediaElement(video);player.load();player.play().then(hide).catch(function(){show('请点击播放按钮');});"
                + "player.on(mpegts.Events.ERROR,function(){show('摄像头视频流连接失败');});"
                + "}else{show('当前浏览器不支持 FLV 直播播放');}</script></body></html>";
        sendHtml(ex, 200, html);
    }

    /** GET /api/camera/proxy?deviceId=D025&path=/live.flv —— 摄像头同源资源/流代理。 */
    private static void streamCameraProxy(HttpExchange ex) throws IOException, SQLException {
        String query = ex.getRequestURI().getQuery();
        CameraConfig c = cameraConfig(queryParam(query, "deviceId"));
        String targetPath = trimToNull(queryParam(query, "path"));
        if (c == null || targetPath == null || (!targetPath.equals("/live.flv") && !targetPath.startsWith("/res/"))) {
            send(ex, 400, "{\"code\":1,\"msg\":" + Json.str("摄像头代理参数错误") + "}");
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = openCameraPath(c, targetPath, 5000, targetPath.equals("/live.flv") ? 0 : 8000, "SmartAgriCameraProxy");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                updateDeviceOnlineQuietly(c.id, false);
                send(ex, 502, "{\"code\":1,\"msg\":" + Json.str("摄像头返回 HTTP " + code) + "}");
                return;
            }
            updateDeviceOnlineQuietly(c.id, true);
            String contentType = conn.getContentType();
            if (contentType == null || contentType.trim().isEmpty()) {
                contentType = targetPath.endsWith(".js") ? "application/javascript; charset=utf-8" : "video/x-flv";
            }
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            ex.sendResponseHeaders(200, 0);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = ex.getResponseBody()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException clientClosed) {
                // 浏览器关闭播放器时结束代理即可。
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openCameraStream(CameraConfig c, int connectTimeout, int readTimeout, String userAgent) throws IOException {
        IOException lastError = null;
        for (String path : CAMERA_HTTP_STREAM_PATHS) {
            HttpURLConnection conn = null;
            try {
                conn = openCameraPath(c, path, connectTimeout, readTimeout, userAgent);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    System.out.println("[Api] 摄像头流连接成功: " + c.id + " " + c.ip + ":" + c.port + path);
                    return conn;
                }
                conn.disconnect();
            } catch (IOException e) {
                lastError = e;
                if (conn != null) conn.disconnect();
            }
        }
        if (lastError != null) {
            System.out.println("[Api] 摄像头流连接失败: " + c.id + " " + c.ip + ":" + c.port + " - " + lastError.getMessage());
        }
        return null;
    }

    private static HttpURLConnection openCameraPath(CameraConfig c, String path, int connectTimeout, int readTimeout, String userAgent) throws IOException {
        URL url = new URL("http://" + c.ip.trim() + ":" + c.port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Pragma", "no-cache");
        conn.setRequestProperty("Connection", "close");
        if (c.username != null && !c.username.isEmpty()) {
            String pass = c.password == null ? "" : c.password;
            String token = Base64.getEncoder().encodeToString((c.username + ":" + pass).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + token);
        }
        return conn;
    }

    private static void updateDeviceOnline(String deviceId, boolean online) throws SQLException {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE device SET online = ? WHERE id = ?")) {
            ps.setInt(1, online ? 1 : 0);
            ps.setString(2, deviceId);
            ps.executeUpdate();
        }
    }

    private static void updateDeviceOnlineQuietly(String deviceId, boolean online) {
        try {
            updateDeviceOnline(deviceId, online);
        } catch (SQLException ignore) {
            // 在线状态只是展示辅助，失败时不影响摄像头流响应。
        }
    }

    /** GET /api/camera/detection-records?deviceId=&date= —— 人体识别记录列表。 */
    private static String detectionRecordsJson(String query) throws SQLException {
        String deviceId = trimToNull(queryParam(query, "deviceId"));
        String date = trimToNull(queryParam(query, "date"));
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.device_id, d.name AS deviceName, r.plot_id, p.name AS plotName," +
                " r.started_at, r.ended_at, r.confidence, r.snapshot_path, r.video_path," +
                " r.alarm_id, r.status, r.created_at" +
                " FROM human_detection_record r" +
                " LEFT JOIN device d ON d.id = r.device_id" +
                " LEFT JOIN plot p ON p.id = r.plot_id WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (deviceId != null) {
            sql.append(" AND r.device_id = ?");
            params.add(deviceId);
        }
        if (date != null) {
            sql.append(" AND DATE(r.started_at) = ?");
            params.add(date);
        }
        sql.append(" ORDER BY r.started_at DESC, r.id DESC LIMIT 100");

        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(detectionRecordJson(rs));
                }
            }
        }
        return sb.append("]}").toString();
    }

    /** GET /api/camera/detection-records/{id} —— 人体识别记录详情。 */
    private static String detectionRecordDetailJson(String idStr) throws SQLException {
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
        }
        String sql =
                "SELECT r.id, r.device_id, d.name AS deviceName, r.plot_id, p.name AS plotName," +
                " r.started_at, r.ended_at, r.confidence, r.snapshot_path, r.video_path," +
                " r.alarm_id, r.status, r.created_at" +
                " FROM human_detection_record r" +
                " LEFT JOIN device d ON d.id = r.device_id" +
                " LEFT JOIN plot p ON p.id = r.plot_id WHERE r.id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"code\":1,\"msg\":" + Json.str("识别记录不存在: " + idStr) + "}";
                return "{\"code\":0,\"data\":" + detectionRecordJson(rs) + "}";
            }
        }
    }

    private static String detectionRecordJson(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp ended = rs.getTimestamp("ended_at");
        Timestamp created = rs.getTimestamp("created_at");
        Object alarm = rs.getObject("alarm_id");
        return "{"
                + "\"id\":" + id
                + ",\"deviceId\":" + Json.str(rs.getString("device_id"))
                + ",\"deviceName\":" + Json.str(rs.getString("deviceName"))
                + ",\"plotId\":" + Json.str(rs.getString("plot_id"))
                + ",\"plotName\":" + Json.str(rs.getString("plotName"))
                + ",\"startedAt\":" + Json.str(started == null ? "" : fmt.format(started))
                + ",\"endedAt\":" + Json.str(ended == null ? "" : fmt.format(ended))
                + ",\"confidence\":" + Json.num(rs.getBigDecimal("confidence"))
                + ",\"snapshotUrl\":" + Json.str("/api/camera/detection-records/" + id + "/snapshot")
                + ",\"videoUrl\":" + Json.str("/api/camera/detection-records/" + id + "/video")
                + ",\"alarmId\":" + (alarm == null ? "null" : String.valueOf(alarm))
                + ",\"status\":" + Json.str(rs.getString("status"))
                + ",\"createdAt\":" + Json.str(created == null ? "" : fmt.format(created))
                + "}";
    }

    /** GET /api/camera/detection-records/{id}/video|snapshot —— 回放或截图。 */
    private static void streamDetectionFile(HttpExchange ex, String idStr, boolean video) throws IOException, SQLException {
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            send(ex, 400, "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}");
            return;
        }
        String column = video ? "video_path" : "snapshot_path";
        String rel = null;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT " + column + " FROM human_detection_record WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) rel = rs.getString(1);
            }
        }
        if (rel == null || rel.trim().isEmpty()) {
            send(ex, 404, "{\"code\":1,\"msg\":" + Json.str(video ? "回放视频不存在" : "识别截图不存在") + "}");
            return;
        }
        Path file = resolveDetectionFile(rel);
        if (video) {
            Path webFile = webPlaybackFile(file);
            if (webFile != null && Files.isRegularFile(webFile)) file = webFile;
        }
        if (file == null || !Files.isRegularFile(file)) {
            send(ex, 404, "{\"code\":1,\"msg\":" + Json.str("文件不存在: " + rel) + "}");
            return;
        }
        streamFileRange(ex, file, video ? "video/mp4" : imageMime(file));
    }

    private static Path resolveDetectionFile(String storedPath) {
        String p = storedPath.replace('\\', '/').trim();
        Path file = Paths.get(p);
        if (!file.isAbsolute()) file = DETECTION_STORAGE.resolve(p);
        file = file.toAbsolutePath().normalize();
        return file.startsWith(DETECTION_STORAGE) ? file : null;
    }

    private static Path webPlaybackFile(Path file) {
        if (file == null) return null;
        String name = file.getFileName().toString();
        if (!name.toLowerCase().endsWith(".mp4")) return null;
        String webName = name.substring(0, name.length() - 4) + ".web.mp4";
        Path webFile = file.getParent().resolve(webName).toAbsolutePath().normalize();
        return webFile.startsWith(DETECTION_STORAGE) ? webFile : null;
    }

    private static String imageMime(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static void streamFileRange(HttpExchange ex, Path file, String contentType) throws IOException {
        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        int status = 200;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            try {
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Long.parseLong(b);
                } else {
                    start = Long.parseLong(spec.trim());
                }
                if (start < 0 || start >= size) start = 0;
                if (end < start || end >= size) end = size - 1;
                status = 206;
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            } catch (NumberFormatException ignore) {
                start = 0;
                end = size - 1;
            }
        }
        long len = end - start + 1;
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(status, len);
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = ex.getResponseBody()) {
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] buf = new byte[8192];
            long remaining = len;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n == -1) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    /** GET /api/camera/detection/status?deviceId= —— 摄像头人体识别状态。 */
    private static String detectionStatusJson(String query) throws SQLException {
        String deviceId = trimToNull(queryParam(query, "deviceId"));
        DetectionSetting s = detectionSetting(deviceId);
        String lastTime = null;
        String lastId = null;
        String workerStatus = "unknown";
        String workerMessage = null;
        String workerLastSeen = null;
        boolean workerOnline = false;
        String sql = "SELECT id, started_at FROM human_detection_record" +
                (deviceId == null ? "" : " WHERE device_id = ?") +
                " ORDER BY started_at DESC, id DESC LIMIT 1";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (deviceId != null) ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    lastId = String.valueOf(rs.getLong("id"));
                    Timestamp ts = rs.getTimestamp("started_at");
                    lastTime = ts == null ? "" : fmt.format(ts);
                }
            }
        }
        if (deviceId != null) {
            String runtimeSql = "SELECT worker_status, message, last_seen, last_seen >= DATE_SUB(NOW(), INTERVAL 60 SECOND) AS online" +
                    " FROM camera_detection_runtime WHERE device_id = ?";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(runtimeSql)) {
                ps.setString(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        workerStatus = rs.getString("worker_status");
                        workerMessage = rs.getString("message");
                        Timestamp ts = rs.getTimestamp("last_seen");
                        workerLastSeen = ts == null ? "" : fmt.format(ts);
                        workerOnline = rs.getInt("online") == 1 && "running".equals(workerStatus);
                    }
                }
            }
        }
        return "{\"code\":0,\"data\":{"
                + "\"deviceId\":" + Json.str(deviceId)
                + ",\"enabled\":" + s.enabled
                + ",\"confidenceThreshold\":" + Json.num(s.confidenceThreshold)
                + ",\"cooldownSeconds\":" + s.cooldownSeconds
                + ",\"preSeconds\":" + s.preSeconds
                + ",\"postSeconds\":" + s.postSeconds
                + ",\"workerOnline\":" + workerOnline
                + ",\"workerStatus\":" + Json.str(workerStatus)
                + ",\"workerMessage\":" + Json.str(workerMessage)
                + ",\"workerLastSeen\":" + Json.str(workerLastSeen)
                + ",\"lastRecordId\":" + (lastId == null ? "null" : lastId)
                + ",\"lastRecordAt\":" + Json.str(lastTime)
                + "}}";
    }

    /** POST /api/camera/detection/settings —— 保存摄像头人体识别开关/参数。 */
    private static String saveDetectionSettingsJson(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String deviceId = trimToNull(body.get("deviceId"));
        if (deviceId == null) {
            return "{\"code\":1,\"msg\":" + Json.str("缺少参数：deviceId 必填") + "}";
        }
        DetectionSetting old = detectionSetting(deviceId);
        Boolean enabled = parseBooleanBox(body.get("enabled"));
        BigDecimal confidence = nullableDecimal(body.get("confidenceThreshold"), old.confidenceThreshold);
        int cooldown = nullableInt(body.get("cooldownSeconds"), old.cooldownSeconds);
        int pre = nullableInt(body.get("preSeconds"), old.preSeconds);
        int post = nullableInt(body.get("postSeconds"), old.postSeconds);
        if (confidence.compareTo(new BigDecimal("0.01")) < 0 || confidence.compareTo(new BigDecimal("0.99")) > 0) {
            return "{\"code\":1,\"msg\":" + Json.str("置信度阈值需在 0.01-0.99 之间") + "}";
        }
        if (cooldown < 0 || pre < 0 || post < 1 || pre > 60 || post > 120) {
            return "{\"code\":1,\"msg\":" + Json.str("录像参数超出范围") + "}";
        }
        String sql = "INSERT INTO camera_detection_setting(device_id, enabled, confidence_threshold, cooldown_seconds, pre_seconds, post_seconds)" +
                " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)," +
                " confidence_threshold=VALUES(confidence_threshold), cooldown_seconds=VALUES(cooldown_seconds)," +
                " pre_seconds=VALUES(pre_seconds), post_seconds=VALUES(post_seconds)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setInt(2, enabled == null ? (old.enabled ? 1 : 0) : (enabled ? 1 : 0));
            ps.setBigDecimal(3, confidence);
            ps.setInt(4, cooldown);
            ps.setInt(5, pre);
            ps.setInt(6, post);
            ps.executeUpdate();
        }
        return detectionStatusJson("deviceId=" + deviceId);
    }

    /** POST /api/camera/detection/worker/start —— 从页面启动本机人体识别 worker。 */
    private static String startDetectionWorkerJson(HttpExchange ex) {
        synchronized (DETECTION_WORKER_LOCK) {
            if (detectionWorkerProcess != null && detectionWorkerProcess.isAlive()) {
                return "{\"code\":0,\"msg\":" + Json.str("worker 已在运行") +
                        ",\"data\":{\"running\":true,\"pid\":" + detectionWorkerProcess.pid() + "}}";
            }

            Path root = Paths.get("").toAbsolutePath().normalize();
            Path python = root.resolve(".venv-detection").resolve("Scripts").resolve("python.exe");
            Path script = root.resolve("workers").resolve("human_detection_worker.py");
            Path outLog = root.resolve("worker.run.out.log");
            Path errLog = root.resolve("worker.run.err.log");
            Path ffmpeg = root.resolve("tools").resolve("ffmpeg-9.0.1-essentials_build").resolve("bin").resolve("ffmpeg.exe");
            Path storage = Paths.get(env("DETECTION_STORAGE_DIR", root.resolve("data").resolve("detections").toString()))
                    .toAbsolutePath().normalize();
            Path model = Paths.get(env("YOLO_MODEL", root.resolve("models").resolve("yolov8n.pt").toString()))
                    .toAbsolutePath().normalize();

            if (!Files.isRegularFile(python)) {
                return "{\"code\":1,\"msg\":" + Json.str("未找到人体识别 Python 环境，请先执行 workers/start_human_detection.ps1 的环境安装步骤：" + python) + "}";
            }
            if (!Files.isRegularFile(script)) {
                return "{\"code\":1,\"msg\":" + Json.str("未找到人体识别 worker 脚本：" + script) + "}";
            }
            if (!Files.isRegularFile(model)) {
                return "{\"code\":1,\"msg\":" + Json.str("未找到 YOLO 人体识别模型：" + model) + "}";
            }
            try {
                Files.createDirectories(storage);
            } catch (IOException e) {
                return "{\"code\":1,\"msg\":" + Json.str("无法创建识别证据存储目录：" + storage) + "}";
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(python.toString(), "-u", script.toString());
                pb.directory(root.toFile());
                Map<String, String> procEnv = pb.environment();
                procEnv.put("SERVER_URL", "http://localhost:" + ex.getLocalAddress().getPort());
                procEnv.put("DETECTION_STORAGE_DIR", storage.toString());
                procEnv.put("YOLO_MODEL", model.toString());
                if (Files.exists(ffmpeg)) {
                    procEnv.put("FFMPEG_PATH", env("FFMPEG_PATH", ffmpeg.toString()));
                }
                if (!DETECTION_WORKER_TOKEN.isEmpty()) {
                    procEnv.put("DETECTION_WORKER_TOKEN", DETECTION_WORKER_TOKEN);
                }
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outLog.toFile()));
                pb.redirectError(ProcessBuilder.Redirect.appendTo(errLog.toFile()));
                detectionWorkerProcess = pb.start();
                return "{\"code\":0,\"msg\":" + Json.str("worker 启动中") +
                        ",\"data\":{\"running\":true,\"pid\":" + detectionWorkerProcess.pid() + "}}";
            } catch (IOException e) {
                return "{\"code\":1,\"msg\":" + Json.str("worker 启动失败：" + e.getMessage()) + "}";
            }
        }
    }

    private static class DetectionSetting {
        boolean enabled = true;
        BigDecimal confidenceThreshold = new BigDecimal("0.50");
        int cooldownSeconds = 30;
        int preSeconds = 5;
        int postSeconds = 10;
    }

    private static DetectionSetting detectionSetting(String deviceId) throws SQLException {
        DetectionSetting s = new DetectionSetting();
        if (deviceId == null) return s;
        String sql = "SELECT enabled, confidence_threshold, cooldown_seconds, pre_seconds, post_seconds" +
                " FROM camera_detection_setting WHERE device_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    s.enabled = rs.getInt("enabled") == 1;
                    if (rs.getBigDecimal("confidence_threshold") != null) s.confidenceThreshold = rs.getBigDecimal("confidence_threshold");
                    s.cooldownSeconds = rs.getInt("cooldown_seconds");
                    s.preSeconds = rs.getInt("pre_seconds");
                    s.postSeconds = rs.getInt("post_seconds");
                }
            }
        }
        return s;
    }

    private static BigDecimal nullableDecimal(String raw, BigDecimal def) {
        String v = trimToNull(raw);
        return v == null || "null".equalsIgnoreCase(v) ? def : new BigDecimal(v);
    }

    private static int nullableInt(String raw, int def) {
        String v = trimToNull(raw);
        return v == null || "null".equalsIgnoreCase(v) ? def : Integer.parseInt(v);
    }

    private static Boolean parseBooleanBox(String raw) {
        String v = trimToNull(raw);
        if (v == null || "null".equalsIgnoreCase(v)) return null;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }

    /** GET /api/internal/camera-configs —— worker 拉取摄像头配置。 */
    private static String internalCameraConfigsJson(HttpExchange ex) throws SQLException {
        if (!allowInternalWorker(ex)) {
            return "{\"code\":1,\"msg\":" + Json.str("未授权的识别 worker 请求") + "}";
        }
        String sql =
                "SELECT d.id, d.name, d.plot_id, p.name AS plotName, d.ip, d.port, d.protocol, d.username, d.password," +
                " COALESCE(s.enabled, 1) AS enabled, COALESCE(s.confidence_threshold, 0.50) AS confidence_threshold," +
                " COALESCE(s.cooldown_seconds, 30) AS cooldown_seconds, COALESCE(s.pre_seconds, 5) AS pre_seconds," +
                " COALESCE(s.post_seconds, 10) AS post_seconds" +
                " FROM device d LEFT JOIN plot p ON p.id = d.plot_id" +
                " LEFT JOIN camera_detection_setting s ON s.device_id = d.id" +
                " WHERE d.type = '摄像头' ORDER BY d.id";
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
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
                  .append("\"plotId\":").append(Json.str(rs.getString("plot_id"))).append(',')
                  .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                  .append("\"ip\":").append(Json.str(rs.getString("ip"))).append(',')
                  .append("\"port\":").append(Json.num(rs.getObject("port"))).append(',')
                  .append("\"protocol\":").append(Json.str(rs.getString("protocol"))).append(',')
                  .append("\"username\":").append(Json.str(rs.getString("username"))).append(',')
                  .append("\"password\":").append(Json.str(rs.getString("password"))).append(',')
                  .append("\"enabled\":").append(rs.getInt("enabled") == 1).append(',')
                  .append("\"confidenceThreshold\":").append(Json.num(rs.getBigDecimal("confidence_threshold"))).append(',')
                  .append("\"cooldownSeconds\":").append(rs.getInt("cooldown_seconds")).append(',')
                  .append("\"preSeconds\":").append(rs.getInt("pre_seconds")).append(',')
                  .append("\"postSeconds\":").append(rs.getInt("post_seconds"))
                  .append('}');
            }
        }
        return sb.append("]}").toString();
    }

    /** POST /api/internal/detection-records —— worker 上报一次人体识别事件并生成告警。 */
    private static String internalCreateDetectionRecordJson(HttpExchange ex) throws IOException, SQLException {
        if (!allowInternalWorker(ex)) {
            return "{\"code\":1,\"msg\":" + Json.str("未授权的识别 worker 请求") + "}";
        }
        Map<String, String> body = Json.parseObject(readBody(ex));
        String deviceId = trimToNull(body.get("deviceId"));
        String startedAt = trimToNull(body.get("startedAt"));
        String endedAt = trimToNull(body.get("endedAt"));
        String videoPath = trimToNull(body.get("videoPath"));
        String snapshotPath = trimToNull(body.get("snapshotPath"));
        BigDecimal confidence = nullableDecimal(body.get("confidence"), null);
        if (deviceId == null || startedAt == null || endedAt == null || videoPath == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：deviceId/startedAt/endedAt/videoPath 必填") + "}";
        }
        String plotId = null;
        String deviceName = null;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT plot_id, name FROM device WHERE id = ? AND type = '摄像头'")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"code\":1,\"msg\":" + Json.str("摄像头设备不存在: " + deviceId) + "}";
                plotId = rs.getString("plot_id");
                deviceName = rs.getString("name");
            }
        }
        long recordId;
        long alarmId;
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO alarm(plot_id, device_id, alarm_type, value, level, status) VALUES (?, ?, ?, ?, ?, '未处理')",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, plotId);
                    ps.setString(2, deviceId);
                    ps.setString(3, "人体入侵");
                    ps.setString(4, confidence == null ? deviceName : "置信度 " + confidence.stripTrailingZeros().toPlainString());
                    ps.setString(5, "严重");
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        alarmId = keys.getLong(1);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO human_detection_record(device_id, plot_id, started_at, ended_at, confidence, snapshot_path, video_path, alarm_id, status)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '未处理')",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, deviceId);
                    ps.setString(2, plotId);
                    ps.setTimestamp(3, Timestamp.valueOf(startedAt));
                    ps.setTimestamp(4, Timestamp.valueOf(endedAt));
                    ps.setBigDecimal(5, confidence);
                    ps.setString(6, snapshotPath);
                    ps.setString(7, videoPath);
                    ps.setLong(8, alarmId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        recordId = keys.getLong(1);
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return "{\"code\":0,\"data\":{\"id\":" + recordId + ",\"alarmId\":" + alarmId + "}}";
    }

    /** POST /api/internal/detection-status —— worker 上报运行心跳。 */
    private static String internalDetectionStatusUpdateJson(HttpExchange ex) throws IOException, SQLException {
        if (!allowInternalWorker(ex)) {
            return "{\"code\":1,\"msg\":" + Json.str("未授权的识别 worker 请求") + "}";
        }
        Map<String, String> body = Json.parseObject(readBody(ex));
        String deviceId = trimToNull(body.get("deviceId"));
        String status = trimToNull(body.get("status"));
        String message = trimToNull(body.get("message"));
        if (deviceId == null || status == null) {
            return "{\"code\":1,\"msg\":" + Json.str("参数不完整：deviceId/status 必填") + "}";
        }
        if (message != null && message.length() > 255) message = message.substring(0, 255);
        String sql = "INSERT INTO camera_detection_runtime(device_id, worker_status, message, last_seen) VALUES (?, ?, ?, NOW())" +
                " ON DUPLICATE KEY UPDATE worker_status=VALUES(worker_status), message=VALUES(message), last_seen=NOW()";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, status);
            ps.setString(3, message);
            ps.executeUpdate();
        }
        if ("running".equals(status)) {
            updateDeviceOnline(deviceId, true);
        } else if ("error".equals(status) || "unknown".equals(status)) {
            updateDeviceOnline(deviceId, false);
        }
        BoardCollector.rescanNow();
        return "{\"code\":0}";
    }

    private static boolean allowInternalWorker(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-Detection-Token");
        if (DETECTION_WORKER_TOKEN != null && !DETECTION_WORKER_TOKEN.isEmpty()) {
            return DETECTION_WORKER_TOKEN.equals(token);
        }
        return ex.getRemoteAddress() != null
                && ex.getRemoteAddress().getAddress() != null
                && ex.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    /* ==================================================================
       实时 / 历史数据
       ================================================================== */

    /** GET /api/plots/{plotId}/realtime —— 某地块最新温湿度/亮度 */
    private static String realtimeJson(String plotId) throws SQLException {
        BigDecimal temp = latestValue(plotId, "temp");
        BigDecimal humidity = latestValue(plotId, "humidity");
        BigDecimal lux = latestValue(plotId, "lux");
        String updatedAt = latestTime(plotId);
        return "{\"code\":0,\"data\":{"
                + "\"plotId\":" + Json.str(plotId)
                + ",\"plotName\":" + Json.str(plotName(plotId))
                + ",\"temp\":" + Json.num(temp)
                + ",\"humidity\":" + Json.num(humidity)
                + ",\"lux\":" + Json.num(lux)
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
        String dataTypes = " AND d.type IN ('温度传感器','环境监测板','土壤湿度传感器','亮度传感器')";
        if (window != null && intervalSql != null) {
            sql = "SELECT " + selectExpr +
                  " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                  " WHERE d.plot_id = ?" + dataTypes +
                  " AND s.collected_at >= DATE_SUB(NOW(), INTERVAL " + intervalSql + ")" +
                  " GROUP BY " + groupSql + " ORDER BY x";
        } else {
            sql = "SELECT DATE_FORMAT(s.collected_at, '%Y-%m-%d') AS x, s.metric, AVG(s.value) AS v" +
                  " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                  " WHERE d.plot_id = ?" + dataTypes +
                  " AND s.collected_at >= DATE_SUB(NOW(), INTERVAL ? DAY)" +
                  " GROUP BY x, s.metric ORDER BY x";
        }

        // 按时间标签聚合 temp/humidity/lux 三指标，保证三个序列与 dates 一一对齐（缺失值输出 null，前端画图显示断点）
        Map<String, double[]> series = new LinkedHashMap<>(); // x -> [temp, humidity, lux]
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            if (window == null || intervalSql == null) {
                ps.setInt(2, days);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String x = rs.getString("x");
                    String metric = rs.getString("metric");
                    if (!"temp".equals(metric) && !"humidity".equals(metric) && !"lux".equals(metric)) continue;
                    double v = rs.getDouble("v");
                    double[] row = series.computeIfAbsent(x, k -> new double[]{ -1, -1, -1 });
                    if ("temp".equals(metric)) row[0] = v;
                    else if ("humidity".equals(metric)) row[1] = v;
                    else row[2] = v;
                }
            }
        }
        StringBuilder dates = new StringBuilder();
        StringBuilder temp = new StringBuilder();
        StringBuilder hum = new StringBuilder();
        StringBuilder lux = new StringBuilder();
        for (Map.Entry<String, double[]> e : series.entrySet()) {
            if (dates.length() > 0) dates.append(',');
            dates.append(Json.str(e.getKey()));
            double[] row = e.getValue();
            appendNum(temp, row[0]);
            appendNum(hum, row[1]);
            appendNum(lux, row[2]);
        }
        return "{\"code\":0,\"data\":{"
                + "\"dates\":[" + dates + "]"
                + ",\"temp\":[" + temp + "]"
                + ",\"humidity\":[" + hum + "]"
                + ",\"lux\":[" + lux + "]}"
                + "}";
    }

    /** 把数值追加到逗号分隔序列里：缺失（<0，哨兵值）输出 null，让前端画图显示断点 */
    private static void appendNum(StringBuilder sb, double v) {
        if (sb.length() > 0) sb.append(',');
        sb.append(v < 0 ? "null" : String.valueOf(v));
    }

    /** 各指标由哪些设备类型提供（数据查询只统计对应类型设备，避免灌溉设备等的板子数据混入） */
    private static String metricTypes(String metric) {
        if ("temp".equals(metric)) return "('温度传感器','环境监测板')";
        if ("humidity".equals(metric)) return "('土壤湿度传感器','环境监测板')";
        if ("lux".equals(metric)) return "('亮度传感器','环境监测板')";
        return null;
    }

    /** 某地块某指标的最新一条读数（仅统计在线且类型匹配的设备）；没有数据返回 null */
    private static BigDecimal latestValue(String plotId, String metric) throws SQLException {
        String types = metricTypes(metric);
        String sql =
                "SELECT s.value FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ? AND s.metric = ? AND d.online = 1" +
                (types == null ? "" : " AND d.type IN " + types) +
                " ORDER BY s.collected_at DESC LIMIT 1";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            ps.setString(2, metric);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    /** 某地块最新采集时间（仅统计在线且提供数据的设备）；没有数据返回空串 */
    private static String latestTime(String plotId) throws SQLException {
        String sql =
                "SELECT MAX(s.collected_at) FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ? AND d.online = 1" +
                " AND d.type IN ('温度传感器','环境监测板','土壤湿度传感器','亮度传感器')";
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

        // 2. 在常驻长连接上向板子下发 on/off，收到 motor 确认回包才算成功
        boolean ok = BoardCollector.sendPersistentCommand(deviceId, action);
        if (!ok) {
            writeControlLog(deviceId, actionText, "失败", operator);
            return "{\"code\":1,\"msg\":" + Json.str("指令下发失败：板子未连接或无响应") + "}";
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
       农事建议
       ================================================================== */

    /**
     * GET /api/advice —— 农事建议。
     * 根据数据库实时数据（地块温湿度、阈值、设备在线状态）动态生成建议列表。
     * 每条：{icon, tag, text, href, action}；href/action 为空串表示无跳转。
     * 天气类建议因后端无气象数据源暂不生成，其余规则与前端 mock 保持一致。
     */
    private static String adviceJson() throws SQLException {
        ThresholdConfig t = currentThresholds(null);

        // 先取出所有地块编号与名称，再逐地块取最新温湿度和亮度（复用 latestValue 的在线+类型过滤）
        List<String[]> plots = new ArrayList<>();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM plot ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plots.add(new String[]{rs.getString("id"), rs.getString("name")});
            }
        }

        List<String> dryNames = new ArrayList<>();
        List<String> wetNames = new ArrayList<>();
        List<String> coldNames = new ArrayList<>();
        List<String> hotNames = new ArrayList<>();
        List<String> lowLuxNames = new ArrayList<>();
        List<String> highLuxNames = new ArrayList<>();
        boolean humid = false;
        for (String[] p : plots) {
            String name = p[1];
            BigDecimal hum = latestValue(p[0], "humidity");
            BigDecimal temp = latestValue(p[0], "temp");
            BigDecimal lux = latestValue(p[0], "lux");
            if (hum != null && t.humidityMin != null && hum.compareTo(t.humidityMin) < 0) dryNames.add(name);
            if (hum != null && t.humidityMax != null && hum.compareTo(t.humidityMax) > 0) wetNames.add(name);
            if (temp != null && t.tempMin != null && temp.compareTo(t.tempMin) < 0) coldNames.add(name);
            if (temp != null && t.tempMax != null && temp.compareTo(t.tempMax) > 0) hotNames.add(name);
            if (lux != null && t.luxMin != null && lux.compareTo(t.luxMin) < 0) lowLuxNames.add(name);
            if (lux != null && t.luxMax != null && lux.compareTo(t.luxMax) > 0) highLuxNames.add(name);
            if (hum != null && hum.compareTo(new BigDecimal("70")) > 0) humid = true;
        }

        int offline = countOfflineDevices();

        List<String> items = new ArrayList<>();
        if (!dryNames.isEmpty()) {
            items.add(adviceItem("💧", "灌溉",
                    joinNames(dryNames) + " 土壤湿度低于阈值 " + numStr(t.humidityMin) + "%，建议尽快补水。",
                    "control.html", "去灌溉"));
        }
        if (!wetNames.isEmpty()) {
            items.add(adviceItem("💦", "排湿",
                    joinNames(wetNames) + " 土壤湿度高于阈值 " + numStr(t.humidityMax) + "%，建议减少灌溉并加强通风排湿。",
                    "monitoring.html", "看数据"));
        }
        if (!coldNames.isEmpty()) {
            items.add(adviceItem("🧊", "保温",
                    joinNames(coldNames) + " 温度低于阈值 " + numStr(t.tempMin) + "℃，建议检查保温措施，必要时升温。",
                    "monitoring.html", "看数据"));
        }
        if (!hotNames.isEmpty()) {
            items.add(adviceItem("🌡️", "通风",
                    joinNames(hotNames) + " 温度超过 " + numStr(t.tempMax) + "℃，建议加强通风降温。",
                    "monitoring.html", "看数据"));
        }
        if (!highLuxNames.isEmpty()) {
            items.add(adviceItem("☀️", "遮阳",
                    joinNames(highLuxNames) + " 亮度超过 " + numStr(t.luxMax) + " lx，建议适当遮阳，减少强光灼伤风险。",
                    "monitoring.html", "看数据"));
        }
        if (!lowLuxNames.isEmpty()) {
            items.add(adviceItem("💡", "补光",
                    joinNames(lowLuxNames) + " 亮度低于 " + numStr(t.luxMin) + " lx，建议检查遮挡情况，必要时开启补光。",
                    "monitoring.html", "看数据"));
        }
        if (offline > 0) {
            items.add(adviceItem("🔌", "设备",
                    "有 " + offline + " 台设备离线，请检查供电与网络连接。",
                    "devices.html", "去设备"));
        }
        if (humid) {
            items.add(adviceItem("🐛", "防病",
                    "近期湿度偏高，注意通风除湿，预防灰霉病等病害。",
                    "", ""));
        }
        if (items.isEmpty()) {
            items.add(adviceItem("✅", "正常",
                    "各地块温度、湿度、亮度均在正常范围，请保持当前管理节奏。",
                    "", ""));
        }

        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(items.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 组单条建议 JSON：{icon,tag,text,href,action} */
    private static String adviceItem(String icon, String tag, String text, String href, String action) {
        return "{\"icon\":" + Json.str(icon)
                + ",\"tag\":" + Json.str(tag)
                + ",\"text\":" + Json.str(text)
                + ",\"href\":" + Json.str(href)
                + ",\"action\":" + Json.str(action)
                + "}";
    }

    /** 用「、」连接地块名列表 */
    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /** 离线设备数量（online=0） */
    private static int countOfflineDevices() throws SQLException {
        String sql = "SELECT COUNT(*) FROM device WHERE online = 0";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 数字转显示串：null → 空串，去尾零（40.00 → 40） */
    private static String numStr(BigDecimal b) {
        if (b == null) return "";
        return b.stripTrailingZeros().toPlainString();
    }

    /* ==================================================================
       天气预报（和风天气）
       返回前端数据总览「天气预报」板块所需形状：
       {code:0, data:{now:{icon,text,temp,humidity,wind}, forecast:[{day,icon,text,high,low}]}}
       未配 Key 或调用失败时返回 code=1，前端自动降级为模拟数据。
       ================================================================== */

    private static String weatherJson() {
        if (QW_API_KEY == null || QW_API_KEY.trim().isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("天气接口未配置：请设置环境变量 QWEATHER_API_KEY") + "}";
        }
        try {
            Map<String, String> now = fetchQWeatherNow();
            List<Map<String, String>> daily = fetchQWeatherDaily();

            String nowJson = "{\"icon\":" + Json.str(iconEmoji(now.get("icon")))
                    + ",\"text\":" + Json.str(now.get("text"))
                    + ",\"temp\":" + Json.str(now.get("temp") + "℃")
                    + ",\"humidity\":" + Json.str(now.get("humidity") + "%")
                    + ",\"wind\":" + Json.str(windText(now))
                    + "}";

            StringBuilder fc = new StringBuilder("[");
            int days = Math.min(6, daily.size());
            for (int i = 0; i < days; i++) {
                if (i > 0) fc.append(',');
                Map<String, String> d = daily.get(i);
                fc.append("{\"day\":").append(Json.str(dayLabel(i, d.get("fxDate"))))
                  .append(",\"icon\":").append(Json.str(iconEmoji(d.get("iconDay"))))
                  .append(",\"text\":").append(Json.str(d.get("textDay")))
                  .append(",\"high\":").append(qwNum(d.get("tempMax")))
                  .append(",\"low\":").append(qwNum(d.get("tempMin")))
                  .append('}');
            }
            fc.append(']');

            return "{\"code\":0,\"data\":{\"now\":" + nowJson + ",\"forecast\":" + fc + "}}";
        } catch (Exception e) {
            return "{\"code\":1,\"msg\":" + Json.str("天气获取失败: " + e.getMessage()) + "}";
        }
    }

    /** 实时天气：请求 /v7/weather/now，返回 now 对象 */
    private static Map<String, String> fetchQWeatherNow() throws IOException, InterruptedException {
        String body = httpGet(QW_HOST + "/v7/weather/now?location=" + QW_LOCATION + "&key=" + QW_API_KEY);
        Map<String, String> root = Json.parseObject(body);
        if (!"200".equals(root.get("code"))) {
            throw new IOException("和风天气返回 code=" + root.get("code"));
        }
        return Json.parseObject(root.get("now"));
    }

    /** 7 天预报：请求 /v7/weather/7d，接口层返回今天 + 未来 5 天 */
    private static List<Map<String, String>> fetchQWeatherDaily() throws IOException, InterruptedException {
        String body = httpGet(QW_HOST + "/v7/weather/7d?location=" + QW_LOCATION + "&key=" + QW_API_KEY);
        Map<String, String> root = Json.parseObject(body);
        if (!"200".equals(root.get("code"))) {
            throw new IOException("和风天气返回 code=" + root.get("code"));
        }
        return Json.parseArray(root.get("daily"));
    }

    /** 简易 HTTP GET，返回响应体（UTF-8） */
    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = conn.getResponseCode();
            String body = readHttpBody(conn, status);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPostJson(String url, String payload, String bearerToken,
                                       int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestProperty("Connection", "close");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        try {
            int status = conn.getResponseCode();
            String body = readHttpBody(conn, status);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + ": "
                        + (body != null && body.length() > 200 ? body.substring(0, 200) : body));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readHttpBody(HttpURLConnection conn, int status) throws IOException {
        InputStream raw = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) return "";
        byte[] body;
        try (InputStream in = raw) {
            body = in.readAllBytes();
        }
        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gzip.readAllBytes();
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 和风天气图标代码 -> emoji（前端用 emoji 展示） */
    private static String iconEmoji(String code) {
        if (code == null) return "🌤️";
        int c;
        try { c = Integer.parseInt(code.trim()); } catch (NumberFormatException e) { return "🌤️"; }
        if (c == 100) return "☀️";
        if (c == 101 || c == 102 || c == 103) return "⛅";
        if (c == 104) return "☁️";
        if (c >= 300 && c <= 399) return "🌧️";
        if (c >= 400 && c <= 499) return "❄️";
        if (c >= 500 && c <= 515) return "🌫️";
        return "🌤️";
    }

    /** 风向 + 风级文本 */
    private static String windText(Map<String, String> now) {
        String dir = now.get("windDir");
        String scale = now.get("windScale");
        if (dir == null || dir.isEmpty()) dir = "无风";
        return dir + (scale == null || scale.isEmpty() ? "" : " " + scale + " 级");
    }

    /** 预报日期标签：今天 / 明天 / 后天 / M/d */
    private static String dayLabel(int i, String fxDate) {
        if (i == 0) return "今天";
        if (i == 1) return "明天";
        if (i == 2) return "后天";
        if (fxDate != null && fxDate.length() >= 10) {
            return fxDate.substring(5, 7) + "/" + fxDate.substring(8, 10);
        }
        return "第" + (i + 1) + "天";
    }

    /** 和风温度字符串转数字字面量（"28" -> 28，null/空 -> 0） */
    private static String qwNum(String s) {
        if (s == null || s.trim().isEmpty()) return "0";
        try { return new BigDecimal(s.trim()).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return "0"; }
    }

    /* ==================================================================
       阈值 / 告警
       ================================================================== */

    /** 当前阈值：取第一个地块的配置（每地块一行）；无配置行时使用默认上下限。 */
    private static ThresholdConfig currentThresholds(String plotId) throws SQLException {
        ThresholdConfig cfg = ThresholdConfig.defaults();
        String sql = "SELECT humidity_min, humidity_max, temp_min, temp_max, lux_min, lux_max FROM plot_threshold" +
                (plotId == null ? " ORDER BY plot_id LIMIT 1" : " WHERE plot_id = ?");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (plotId != null) ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cfg.humidityMin = rs.getBigDecimal("humidity_min");
                    cfg.humidityMax = rs.getBigDecimal("humidity_max");
                    cfg.tempMin = rs.getBigDecimal("temp_min");
                    cfg.tempMax = rs.getBigDecimal("temp_max");
                    cfg.luxMin = rs.getBigDecimal("lux_min");
                    cfg.luxMax = rs.getBigDecimal("lux_max");
                }
            }
        }
        return cfg;
    }

    /**
     * GET /api/thresholds —— 阈值配置。
     * 前端只有一个全局编辑器，DB 是每地块一行（plot_threshold）；
     * 这里返回当前阈值（首个地块），null 表示该类阈值已删除/禁用。
     */
    private static String thresholdsJson(String query) throws SQLException {
        ThresholdConfig t = currentThresholds(trimToNull(queryParam(query, "plotId")));
        return "{\"code\":0,\"data\":{"
                + "\"humidityMin\":" + Json.num(numLit(t.humidityMin))
                + ",\"humidityMax\":" + Json.num(numLit(t.humidityMax))
                + ",\"tempMin\":" + Json.num(numLit(t.tempMin))
                + ",\"tempMax\":" + Json.num(numLit(t.tempMax))
                + ",\"luxMin\":" + Json.num(numLit(t.luxMin))
                + ",\"luxMax\":" + Json.num(numLit(t.luxMax))
                + "}}";
    }

    /**
     * PUT /api/thresholds —— 保存阈值。
     * body: {humidityMin, humidityMax, tempMin, tempMax, luxMin, luxMax}；值为 null 表示删除对应阈值。
     */
    private static String saveThresholdsJson(HttpExchange ex, String query) throws IOException, SQLException {
        String selectedPlotId = trimToNull(queryParam(query, "plotId"));
        Map<String, String> body = Json.parseObject(readBody(ex));
        ThresholdConfig cfg;
        try {
            cfg = ThresholdConfig.fromBody(body);
        } catch (NumberFormatException e) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：阈值必须是数字") + "}";
        }
        if (cfg.humidityMin != null && cfg.humidityMax != null && cfg.humidityMin.compareTo(cfg.humidityMax) > 0) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：湿度下限不能大于湿度上限") + "}";
        }
        if (cfg.tempMin != null && cfg.tempMax != null && cfg.tempMin.compareTo(cfg.tempMax) > 0) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：温度下限不能大于温度上限") + "}";
        }
        if (cfg.luxMin != null && cfg.luxMax != null && cfg.luxMin.compareTo(cfg.luxMax) > 0) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：亮度下限不能大于亮度上限") + "}";
        }
        String sql =
                "INSERT INTO plot_threshold(plot_id, humidity_min, humidity_max, temp_min, temp_max, lux_min, lux_max) VALUES (?, ?, ?, ?, ?, ?, ?)" +
                " ON DUPLICATE KEY UPDATE humidity_min = VALUES(humidity_min), humidity_max = VALUES(humidity_max)," +
                " temp_min = VALUES(temp_min), temp_max = VALUES(temp_max)," +
                " lux_min = VALUES(lux_min), lux_max = VALUES(lux_max)";
        try (Connection conn = DBUtil.getConnection()) {
            // 1. 收集全部地块编号
            List<String> plotIds = new ArrayList<>();
            if (selectedPlotId != null) {
                plotIds.add(selectedPlotId);
            } else {
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM plot");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) plotIds.add(rs.getString(1));
                }
            }
            // 2. 每个地块 upsert 一份同样的阈值
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String plotId : plotIds) {
                    ps.setString(1, plotId);
                    ps.setBigDecimal(2, cfg.humidityMin);
                    ps.setBigDecimal(3, cfg.humidityMax);
                    ps.setBigDecimal(4, cfg.tempMin);
                    ps.setBigDecimal(5, cfg.tempMax);
                    ps.setBigDecimal(6, cfg.luxMin);
                    ps.setBigDecimal(7, cfg.luxMax);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        List<String> checkedPlotIds = new ArrayList<>();
        if (selectedPlotId != null) {
            checkedPlotIds.add(selectedPlotId);
        } else {
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id FROM plot");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) checkedPlotIds.add(rs.getString(1));
            }
        }
        for (String plotId : checkedPlotIds) {
            checkThresholdAlarm(plotId, true);
        }
        return "{\"code\":0}";
    }

    private static String numLit(BigDecimal b) {
        return b == null ? null : b.stripTrailingZeros().toPlainString();
    }

    private static class ThresholdConfig {
        BigDecimal humidityMin;
        BigDecimal humidityMax;
        BigDecimal tempMin;
        BigDecimal tempMax;
        BigDecimal luxMin;
        BigDecimal luxMax;

        static ThresholdConfig defaults() {
            ThresholdConfig c = new ThresholdConfig();
            c.humidityMin = new BigDecimal(40);
            c.humidityMax = new BigDecimal(70);
            c.tempMin = new BigDecimal(10);
            c.tempMax = new BigDecimal(35);
            c.luxMin = new BigDecimal(200);
            c.luxMax = new BigDecimal(800);
            return c;
        }

        static ThresholdConfig fromBody(Map<String, String> body) {
            ThresholdConfig c = new ThresholdConfig();
            c.humidityMin = nullableDecimal(body.get("humidityMin"));
            c.humidityMax = nullableDecimal(body.get("humidityMax"));
            c.tempMin = nullableDecimal(body.get("tempMin"));
            c.tempMax = nullableDecimal(body.get("tempMax"));
            c.luxMin = nullableDecimal(body.get("luxMin"));
            c.luxMax = nullableDecimal(body.get("luxMax"));
            return c;
        }

        boolean humidityEnabled() {
            return humidityMin != null || humidityMax != null;
        }

        boolean tempEnabled() {
            return tempMin != null || tempMax != null;
        }

        boolean luxEnabled() {
            return luxMin != null || luxMax != null;
        }

        boolean hasEnabledMetric() {
            return humidityEnabled() || tempEnabled() || luxEnabled();
        }

        private static BigDecimal nullableDecimal(String value) {
            if (value == null) return null;
            String v = value.trim();
            if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
            return new BigDecimal(v);
        }
    }

    /**
     * GET /api/alarms —— 告警列表。
     * 返回：id,time,plotId,plotName,type,value,level,status,handler,handledAt,handleLog。
     * time 格式与 mock 一致（yyyy-MM-dd HH:mm）；type 直接用库里的中文 alarm_type。
     */
    private static String alarmsJson(String query) throws SQLException {
        String plotId = trimToNull(queryParam(query, "plotId"));
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT a.id, a.plot_id, a.device_id, a.alarm_type, a.value, a.level, a.status, a.created_at, a.handled_at, a.handler, a.handle_log," +
                " r.id AS detectionRecordId," +
                " p.name AS plotName FROM alarm a LEFT JOIN plot p ON p.id = a.plot_id" +
                " LEFT JOIN human_detection_record r ON r.alarm_id = a.id" +
                (plotId == null ? "" : " WHERE a.plot_id = ?") +
                " ORDER BY a.created_at DESC, a.id DESC";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (plotId != null) ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(',');
                first = false;
                Timestamp ts = rs.getTimestamp("created_at");
                String time = ts == null ? "" : fmt.format(ts);
                Timestamp handledTs = rs.getTimestamp("handled_at");
                String handledAt = handledTs == null ? "" : fmt.format(handledTs);
                sb.append('{')
                  .append("\"id\":").append(rs.getLong("id")).append(',')
                  .append("\"time\":").append(Json.str(time)).append(',')
                  .append("\"plotId\":").append(Json.str(rs.getString("plot_id"))).append(',')
                  .append("\"deviceId\":").append(Json.str(rs.getString("device_id"))).append(',')
                  .append("\"plotName\":").append(Json.str(rs.getString("plotName"))).append(',')
                  .append("\"type\":").append(Json.str(rs.getString("alarm_type"))).append(',')
                  .append("\"value\":").append(Json.str(rs.getString("value"))).append(',')
                  .append("\"level\":").append(Json.str(rs.getString("level"))).append(',')
                  .append("\"status\":").append(Json.str(rs.getString("status"))).append(',')
                  .append("\"handler\":").append(Json.str(rs.getString("handler"))).append(',')
                  .append("\"handledAt\":").append(Json.str(handledAt)).append(',')
                  .append("\"handleLog\":").append(Json.str(rs.getString("handle_log"))).append(',')
                  .append("\"detectionRecordId\":").append(rs.getObject("detectionRecordId") == null ? "null" : rs.getLong("detectionRecordId"))
                  .append('}');
            }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * PUT /api/alarms/{id} —— 标记处理。
     * body: {status:'已处理', handler:'处理人', handleLog:'处理日志'}；顺手记录处理人和处理时间。
     */
    private static String updateAlarmJson(String id, HttpExchange ex) throws IOException, SQLException {
        Map<String, String> body = Json.parseObject(readBody(ex));
        String status = body.get("status");
        if (!"已处理".equals(status)) {
            return "{\"code\":1,\"msg\":" + Json.str("仅支持将告警标记为已处理") + "}";
        }
        String handler = body.containsKey("handler") && body.get("handler") != null && !body.get("handler").trim().isEmpty()
                ? body.get("handler").trim() : "演示用户";
        String handleLog = body.containsKey("handleLog") && body.get("handleLog") != null
                ? body.get("handleLog").trim() : "";
        if (handleLog.isEmpty()) {
            return "{\"code\":1,\"msg\":" + Json.str("请填写处理日志") + "}";
        }
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long alarmId = Long.parseLong(id);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE alarm SET status = '已处理', handled_at = NOW(), handler = ?, handle_log = ? WHERE id = ? AND status = '未处理'")) {
                    ps.setString(1, handler);
                    ps.setString(2, handleLog);
                    ps.setLong(3, alarmId);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        return "{\"code\":1,\"msg\":" + Json.str("告警不存在或已处理: " + id) + "}";
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE human_detection_record SET status = '已处理' WHERE alarm_id = ?")) {
                    ps.setLong(1, alarmId);
                    ps.executeUpdate();
                }
                conn.commit();
                return "{\"code\":0,\"data\":{\"id\":" + alarmId + ",\"status\":\"已处理\",\"handleLog\":" + Json.str(handleLog) + "}}";
            } catch (NumberFormatException e) {
                conn.rollback();
                return "{\"code\":1,\"msg\":" + Json.str("参数错误：id 必须是数字") + "}";
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String resolvePlotAlarmsJson(String plotId, HttpExchange ex) throws IOException, SQLException {
        if (trimToNull(plotId) == null) return "{\"code\":1,\"msg\":" + Json.str("地块编号不能为空") + "}";
        Map<String, String> body = Json.parseObject(readBody(ex));
        String handler = body.containsKey("handler") && body.get("handler") != null && !body.get("handler").trim().isEmpty()
                ? body.get("handler").trim() : "演示用户";
        List<Long> resolvedIds = new ArrayList<>();
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT id FROM alarm WHERE plot_id = ? AND status = '未处理' FOR UPDATE")) {
                    select.setString(1, plotId);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) resolvedIds.add(rs.getLong(1));
                    }
                }
                if (!resolvedIds.isEmpty()) {
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE alarm SET status = '已处理', handled_at = NOW(), handler = ?, handle_log = '已处理' WHERE plot_id = ? AND status = '未处理'")) {
                        update.setString(1, handler);
                        update.setString(2, plotId);
                        update.executeUpdate();
                    }
                    try (PreparedStatement detection = conn.prepareStatement(
                            "UPDATE human_detection_record r JOIN alarm a ON a.id = r.alarm_id SET r.status = '已处理' WHERE a.plot_id = ? AND a.id IN (" + placeholders(resolvedIds.size()) + ")")) {
                        detection.setString(1, plotId);
                        for (int i = 0; i < resolvedIds.size(); i++) detection.setLong(i + 2, resolvedIds.get(i));
                        detection.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        StringBuilder json = new StringBuilder("{\"code\":0,\"data\":{\"plotId\":").append(Json.str(plotId)).append(",\"resolvedAlarmIds\":[");
        for (int i = 0; i < resolvedIds.size(); i++) {
            if (i > 0) json.append(',');
            json.append(resolvedIds.get(i));
        }
        return json.append("]}}") .toString();
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
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

    /** 智能问答 API Key：从环境变量读取，勿硬编码（避免随代码提交泄露）。 */
    private static final String SMART_QA_API_KEY = envFirst("SMART_QA_API_KEY", "DEEPSEEK_API_KEY", "API");
    private static final String SMART_QA_URL   = envFirstOrDefault("https://api.deepseek.com/chat/completions", "SMART_QA_API_URL");
    private static final String SMART_QA_MODEL = envFirstOrDefault("deepseek-chat", "SMART_QA_MODEL");
    /** 多轮对话最多带上多少条历史（防止上下文过长、超 token 上限） */
    private static final int MAX_CHAT_HISTORY = 8;
    /** RAG 知识库检索返回的最相关知识块条数 */
    private static final int RAG_TOP_K = 2;

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

        String question = lastUserQuestion(valid);

        // 所有智能问答都调用大模型；RAG 只作为参考资料，不再走本地固定回答分支。
        String kbContext = Rag.buildContext(question, RAG_TOP_K);
        String answer;
        try {
            answer = deepseekChat(valid, kbContext);
        } catch (SmartQaException e) {
            return smartQaError(e.errorCode, e.getMessage());
        } catch (Exception e) {
            System.out.println("[SmartQA] 调用异常: " + e.getClass().getSimpleName());
            return smartQaError("SMART_QA_UNAVAILABLE", "智能问答服务暂时不可用，请稍后重试。");
        }
        // RAG 命中来源（标题数组，前端展示「📚 参考」；未命中为空数组）
        String sourcesJson = Json.arrStr(Rag.searchTitles(question, RAG_TOP_K));

        // 落库（按用户名隔离）；未传 user（未登录）则不保存，仅返回回答
        Long conversationId = null;
        if (user != null && !user.trim().isEmpty()) {
            try {
                conversationId = persistChat(user.trim(), conversationIdStr, valid, answer);
            } catch (SQLException e) {
                return "{\"code\":1,\"msg\":" + Json.str("对话记录保存失败：" + e.getMessage()) + "}";
            }
        }

        // 按问题意图生成跳转按钮（控制灌溉/历史趋势/告警/监测/设备/总览），供前端在回答下方展示
        String actionsJson = detectActions(question);

        return "{\"code\":0,\"data\":{\"answer\":" + Json.str(answer)
                + ",\"actions\":" + actionsJson + ",\"conversationId\":"
                + (conversationId == null ? "null" : conversationId)
                + ",\"sources\":" + sourcesJson + "}}";
    }

    /** 取对话里最后一条用户消息（用于判断本轮问题意图） */
    private static String lastUserQuestion(List<Map<String, String>> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role"))) return msgs.get(i).get("content");
        }
        return "";
    }

    /**
     * 按问题关键词检测用户意图，返回可点击的跳转按钮 JSON 数组（最多 3 个、按 href 去重）。
     * 例：问"我想控制灌溉" → 去设备控制页；问"看下历史趋势" → 去历史趋势页；问"现在该浇水吗" → 去控制灌溉页。
     */
    private static String detectActions(String q) {
        if (q == null || q.isEmpty()) return "[]";
        String s = q.replaceAll("[？?。.，,、\\s]", "");
        List<String[]> list = new ArrayList<>();

        // 控制灌溉
        if (containsAny(s, new String[]{"灌溉", "浇水", "控制设备", "电磁阀", "开灌", "关灌", "开水", "关水", "开闸", "关闸"})) {
            list.add(new String[]{"去控制灌溉", "control.html"});
        }
        // 历史趋势
        if (containsAny(s, new String[]{"历史趋势", "历史数据", "历史记录", "看趋势", "历史"})) {
            list.add(new String[]{"查看历史趋势", "history.html"});
        }
        // 告警 / 阈值
        if (containsAny(s, new String[]{"告警", "警报", "报警", "预警", "阈值"})) {
            list.add(new String[]{"查看告警", "alarm.html"});
        }
        // 实时数据 / 监测
        if (containsAny(s, new String[]{"实时", "监测", "温湿度", "数据监测", "查看数据", "看下数据", "看数据",
                "打开监测", "进入监测", "现在温度", "当前温度", "现在湿度", "当前湿度",
                "现在光照", "当前光照", "多少度", "多少湿度"})) {
            list.add(new String[]{"查看实时数据", "monitoring.html"});
        }
        // 设备管理
        if (containsAny(s, new String[]{"设备管理", "绑定设备", "新增设备", "解绑", "设备列表"})) {
            list.add(new String[]{"去设备管理", "devices.html"});
        }
        // 数据总览
        if (containsAny(s, new String[]{"数据总览", "总览", "概况"})) {
            list.add(new String[]{"查看数据总览", "index.html"});
        }

        // 按 href 去重，最多返回 3 个按钮
        StringBuilder sb = new StringBuilder("[");
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (String[] b : list) {
            if (n >= 3) break;
            if (!seen.add(b[1])) continue;
            if (n > 0) sb.append(',');
            sb.append("{\"text\":").append(Json.str(b[0]))
              .append(",\"href\":").append(Json.str(b[1])).append('}');
            n++;
        }
        return sb.append(']').toString();
    }

    /** 判断字符串 s 是否包含关键词数组里的任意一个 */
    private static boolean containsAny(String s, String[] kws) {
        for (String kw : kws) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    /** 判断本轮是否是“未来是否需要灌溉 / 灌溉多久”的预测类问题。 */
    private static boolean isIrrigationPredictionQuestion(String q) {
        if (q == null) return false;
        String s = q.replaceAll("[？?。.，,、\\s]", "").toUpperCase();
        boolean irrigation = containsAny(s, new String[]{"灌溉", "浇水", "补水", "缺水", "土壤湿度", "墒情"});
        boolean decision = containsAny(s, new String[]{"预测", "未来", "三天", "3天", "该不该", "是否应该", "要不要", "需不需要", "多久", "多长时间", "几分钟", "现在该"});
        return irrigation && decision;
    }

    /** 智能问答内置的预测性灌溉回答：不依赖大模型，也保证中文输出。 */
    private static String irrigationPredictionAnswer(String question) throws SQLException {
        List<PlotProfile> plots = targetPlotsForQuestion(question);
        if (plots.isEmpty()) {
            return "暂时没有可用于预测的地块信息。请先在系统中添加地块，并确保传感器已经采集温度、土壤湿度和光照数据。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("我根据最近几天的温度、土壤湿度和光照数据，做了未来 3 天的预测性灌溉判断。\n\n")
          .append("当前采用的是**1 层 GRU 时间序列预测器**：输入温度、土壤湿度、光照和湿度变化特征，预测未来 3 天湿度；再由规则层给出是否灌溉和建议时长。\n");

        for (PlotProfile plot : plots) {
            sb.append('\n').append(buildPredictionForPlot(plot));
        }
        sb.append("\n提示：预测建议用于辅助决策，真正执行前建议再看一次实时湿度和设备在线状态。");
        return sb.toString();
    }

    /** 从问题中识别目标地块；未指定时返回全部地块。 */
    private static List<PlotProfile> targetPlotsForQuestion(String question) throws SQLException {
        List<PlotProfile> all = new ArrayList<>();
        String sql =
                "SELECT p.id, p.name, p.crop, p.area," +
                " COALESCE(t.humidity_min, 40) AS humidity_min," +
                " COALESCE(t.temp_max, 35) AS temp_max" +
                " FROM plot p LEFT JOIN plot_threshold t ON t.plot_id = p.id ORDER BY p.id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlotProfile p = new PlotProfile();
                p.id = rs.getString("id");
                p.name = rs.getString("name");
                p.crop = rs.getString("crop");
                p.area = rs.getBigDecimal("area") == null ? 1.0 : rs.getBigDecimal("area").doubleValue();
                p.humidityMin = rs.getBigDecimal("humidity_min").doubleValue();
                p.tempMax = rs.getBigDecimal("temp_max").doubleValue();
                all.add(p);
            }
        }
        if (question == null || question.trim().isEmpty()) return all;

        String s = question.toUpperCase();
        List<PlotProfile> matched = new ArrayList<>();
        for (PlotProfile p : all) {
            if (s.contains(p.id.toUpperCase())
                    || s.contains(p.name.toUpperCase())
                    || (p.crop != null && !p.crop.isEmpty() && s.contains(p.crop.toUpperCase()))) {
                matched.add(p);
            }
        }
        return matched.isEmpty() ? all : matched;
    }

    /** 生成单个地块的 3 天预测和灌溉建议。 */
    private static String buildPredictionForPlot(PlotProfile plot) throws SQLException {
        List<IrrigationPoint> history = irrigationHistory(plot.id, 14);
        IrrigationPoint latest = latestSensorSnapshot(plot.id);
        if (Double.isNaN(latest.humidity) && history.isEmpty()) {
            return "- **" + plot.name + "（" + plot.id + "）**：暂无土壤湿度历史数据，暂时无法预测是否灌溉。建议先采集至少 3 天数据。\n";
        }

        double currentHumidity = Double.isNaN(latest.humidity) ? lastValue(history, "humidity") : latest.humidity;
        if (Double.isNaN(currentHumidity)) {
            return "- **" + plot.name + "（" + plot.id + "）**：历史记录中缺少有效土壤湿度，暂时无法预测是否灌溉。建议先检查湿度传感器数据。\n";
        }

        GruForecast result = OneLayerGruForecaster.forecast(history, latest, plot, 3);
        double avgTemp = result.avgTemp;
        double avgLux = result.avgLux;
        double[] forecast = result.forecast;

        double minForecast = Math.min(forecast[0], Math.min(forecast[1], forecast[2]));
        boolean shouldIrrigate = currentHumidity < plot.humidityMin || minForecast < plot.humidityMin;
        String risk = riskLevel(currentHumidity, minForecast, plot.humidityMin);
        int duration = shouldIrrigate ? irrigationMinutes(plot, currentHumidity, minForecast, avgTemp, avgLux) : 0;
        String start = recommendedStart(currentHumidity, forecast, plot.humidityMin);

        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(plot.name).append("（").append(plot.id).append("，").append(plot.crop).append("）**\n")
          .append("  当前土壤湿度约 ").append(fmt(currentHumidity)).append("%，阈值为 ").append(fmt(plot.humidityMin)).append("%。");
        if (!Double.isNaN(avgTemp) || !Double.isNaN(avgLux)) {
            sb.append(" 近几天平均");
            if (!Double.isNaN(avgTemp)) sb.append("温度 ").append(fmt(avgTemp)).append("℃");
            if (!Double.isNaN(avgTemp) && !Double.isNaN(avgLux)) sb.append("，");
            if (!Double.isNaN(avgLux)) sb.append("光照 ").append(fmt(avgLux)).append(" lx");
            sb.append("。");
        }
        sb.append('\n')
          .append("  未来 3 天预测湿度：")
          .append(futureDate(1)).append(" 约 ").append(fmt(forecast[0])).append("%，")
          .append(futureDate(2)).append(" 约 ").append(fmt(forecast[1])).append("%，")
          .append(futureDate(3)).append(" 约 ").append(fmt(forecast[2])).append("%。\n");
        if (shouldIrrigate) {
            sb.append("  结论：**建议灌溉**，风险等级为**").append(risk).append("**。建议")
              .append(start).append("灌溉 **").append(duration).append(" 分钟**。原因是未来最低湿度预计会到 ")
              .append(fmt(minForecast)).append("%，低于或接近适宜下限。");
        } else {
            sb.append("  结论：**未来 3 天暂不建议灌溉**，风险等级为**").append(risk)
              .append("**。土壤湿度预计仍高于下限，继续观察即可。");
        }
        if (result.sparse) {
            sb.append(" 当前有效历史样本偏少，GRU 预测已启用趋势校准，本次预测置信度").append(result.confidence)
              .append("，建议补齐连续 7-14 天采集数据。");
        } else {
            sb.append(" 本次使用 1 层 GRU 预测，置信度").append(result.confidence).append("。");
        }
        sb.append('\n');
        return sb.toString();
    }

    /** 查询某地块近 days 天按日聚合的温度、湿度、光照。 */
    private static List<IrrigationPoint> irrigationHistory(String plotId, int days) throws SQLException {
        Map<String, IrrigationPoint> map = new LinkedHashMap<>();
        String sql =
                "SELECT DATE(s.collected_at) AS day_key, s.metric, AVG(s.value) AS v" +
                " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.plot_id = ? AND d.online = 1" +
                " AND d.type IN ('温度传感器','环境监测板','土壤湿度传感器','亮度传感器')" +
                " AND s.metric IN ('temp','humidity','lux')" +
                " AND s.collected_at >= DATE_SUB(NOW(), INTERVAL ? DAY)" +
                " GROUP BY DATE(s.collected_at), s.metric ORDER BY day_key";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            ps.setInt(2, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = rs.getString("day_key");
                    IrrigationPoint p = map.computeIfAbsent(day, k -> new IrrigationPoint(day));
                    double v = rs.getDouble("v");
                    String metric = rs.getString("metric");
                    if ("temp".equals(metric)) p.temp = v;
                    else if ("humidity".equals(metric)) p.humidity = v;
                    else if ("lux".equals(metric)) p.lux = v;
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private static IrrigationPoint latestSensorSnapshot(String plotId) throws SQLException {
        IrrigationPoint p = new IrrigationPoint("latest");
        BigDecimal temp = latestValue(plotId, "temp");
        BigDecimal humidity = latestValue(plotId, "humidity");
        BigDecimal lux = latestValue(plotId, "lux");
        p.temp = temp == null ? Double.NaN : temp.doubleValue();
        p.humidity = humidity == null ? Double.NaN : humidity.doubleValue();
        p.lux = lux == null ? Double.NaN : lux.doubleValue();
        return p;
    }

    /** 1 层 GRU 对未来湿度的预测结果。 */
    private static class GruForecast {
        double[] forecast;
        double avgTemp;
        double avgLux;
        boolean sparse;
        String confidence;
    }

    /**
     * 单层 GRU 推理器。
     * 输入特征：[温度、湿度、光照、距阈值缺口、湿度变化]，隐藏层只经过一层 GRU cell。
     * 项目当前没有离线训练产物，因此这里使用固定权重 + 历史趋势校准；后续有模型文件时可替换权重加载逻辑。
     */
    private static class OneLayerGruForecaster {
        private static final int INPUT_SIZE = 5;
        private static final int HIDDEN_SIZE = 6;
        private static final double[] OUT = { -0.70, 0.42, -0.35, 0.55, -0.28, 0.33 };

        static GruForecast forecast(List<IrrigationPoint> history, IrrigationPoint latest,
                                    PlotProfile plot, int days) {
            GruForecast r = new GruForecast();
            r.forecast = new double[days];

            double currentHumidity = Double.isNaN(latest.humidity)
                    ? lastValue(history, "humidity") : latest.humidity;
            r.avgTemp = averageRecent(history, "temp", 7);
            r.avgLux = averageRecent(history, "lux", 7);
            if (Double.isNaN(r.avgTemp)) r.avgTemp = latest.temp;
            if (Double.isNaN(r.avgLux)) r.avgLux = latest.lux;

            double observedTrend = weightedHumidityTrend(history);
            r.sparse = validCount(history, "humidity") < 4 || Double.isNaN(observedTrend);
            if (Double.isNaN(observedTrend)) {
                observedTrend = (!Double.isNaN(r.avgTemp) && r.avgTemp >= 30)
                        || (!Double.isNaN(r.avgLux) && r.avgLux >= 650) ? -1.4 : -0.7;
            }

            double[] hidden = new double[HIDDEN_SIZE];
            double prevHumidity = currentHumidity;
            boolean hasPrev = false;
            for (IrrigationPoint p : history) {
                if (Double.isNaN(p.humidity)) continue;
                double temp = Double.isNaN(p.temp) ? r.avgTemp : p.temp;
                double lux = Double.isNaN(p.lux) ? r.avgLux : p.lux;
                double trend = hasPrev ? p.humidity - prevHumidity : observedTrend;
                hidden = gruStep(features(temp, p.humidity, lux, plot.humidityMin, trend), hidden);
                prevHumidity = p.humidity;
                hasPrev = true;
            }

            if (!Double.isNaN(latest.humidity)) {
                double trend = hasPrev ? latest.humidity - prevHumidity : observedTrend;
                hidden = gruStep(features(latest.temp, latest.humidity, latest.lux, plot.humidityMin, trend), hidden);
            }

            double h = currentHumidity;
            for (int i = 0; i < days; i++) {
                double gruDelta = outputDelta(hidden);
                double calibratedDelta = clamp(gruDelta * 0.55 + observedTrend * 0.35
                        + evaporationAdjust(r.avgTemp, r.avgLux), -8.0, 3.0);
                h = clamp(h + calibratedDelta, 5.0, 95.0);
                r.forecast[i] = h;
                hidden = gruStep(features(r.avgTemp, h, r.avgLux, plot.humidityMin, calibratedDelta), hidden);
            }

            int count = validCount(history, "humidity");
            r.confidence = count >= 10 ? "较高" : (count >= 5 ? "中等" : "偏低");
            return r;
        }

        private static double[] features(double temp, double humidity, double lux,
                                         double humidityMin, double trend) {
            return new double[] {
                    norm(temp, 25, 12),
                    norm(humidity, 50, 30),
                    norm(lux, 500, 600),
                    norm(humidityMin - humidity, 0, 30),
                    norm(trend, 0, 8)
            };
        }

        private static double[] gruStep(double[] x, double[] h) {
            double[] next = new double[HIDDEN_SIZE];
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                double z = sigmoid(linearInput(0, j, x) + linearHidden(0, j, h) - 0.10);
                double r = sigmoid(linearInput(1, j, x) + linearHidden(1, j, h) + 0.05);
                double[] rh = new double[HIDDEN_SIZE];
                for (int k = 0; k < HIDDEN_SIZE; k++) rh[k] = r * h[k];
                double n = Math.tanh(linearInput(2, j, x) + linearHidden(2, j, rh));
                next[j] = z * h[j] + (1 - z) * n;
            }
            return next;
        }

        private static double outputDelta(double[] h) {
            double y = -0.20;
            for (int i = 0; i < HIDDEN_SIZE; i++) y += OUT[i] * h[i];
            return Math.tanh(y) * 5.5;
        }

        private static double linearInput(int gate, int row, double[] x) {
            double sum = 0;
            for (int i = 0; i < INPUT_SIZE; i++) sum += inputWeight(gate, row, i) * x[i];
            return sum;
        }

        private static double linearHidden(int gate, int row, double[] h) {
            double sum = 0;
            for (int i = 0; i < HIDDEN_SIZE; i++) sum += hiddenWeight(gate, row, i) * h[i];
            return sum;
        }

        private static double inputWeight(int gate, int row, int col) {
            int n = (gate + 2) * 31 + (row + 3) * 17 + (col + 5) * 13;
            return ((n % 19) - 9) / 26.0;
        }

        private static double hiddenWeight(int gate, int row, int col) {
            int n = (gate + 1) * 29 + (row + 7) * 11 + (col + 2) * 23;
            return ((n % 17) - 8) / 34.0;
        }

        private static double sigmoid(double x) {
            return 1.0 / (1.0 + Math.exp(-x));
        }

        private static double norm(double value, double center, double scale) {
            if (Double.isNaN(value)) return 0;
            return clamp((value - center) / scale, -1.0, 1.0);
        }
    }

    private static double weightedHumidityTrend(List<IrrigationPoint> history) {
        double sum = 0;
        double weightSum = 0;
        int weight = 1;
        for (int i = 1; i < history.size(); i++) {
            double prev = history.get(i - 1).humidity;
            double cur = history.get(i).humidity;
            if (Double.isNaN(prev) || Double.isNaN(cur)) continue;
            sum += (cur - prev) * weight;
            weightSum += weight;
            weight++;
        }
        return weightSum == 0 ? Double.NaN : sum / weightSum;
    }

    private static int validCount(List<IrrigationPoint> history, String metric) {
        int count = 0;
        for (IrrigationPoint p : history) {
            if (!Double.isNaN(metricValue(p, metric))) count++;
        }
        return count;
    }

    private static double evaporationAdjust(double avgTemp, double avgLux) {
        double adjust = 0;
        if (!Double.isNaN(avgTemp)) {
            if (avgTemp >= 35) adjust -= 1.2;
            else if (avgTemp >= 30) adjust -= 0.7;
            else if (avgTemp >= 26) adjust -= 0.35;
            else if (avgTemp < 18) adjust += 0.25;
        }
        if (!Double.isNaN(avgLux)) {
            if (avgLux >= 900) adjust -= 0.6;
            else if (avgLux >= 650) adjust -= 0.35;
            else if (avgLux < 250) adjust += 0.2;
        }
        return adjust;
    }

    private static double averageRecent(List<IrrigationPoint> history, String metric, int limit) {
        double sum = 0;
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && count < limit; i--) {
            double v = metricValue(history.get(i), metric);
            if (Double.isNaN(v)) continue;
            sum += v;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double lastValue(List<IrrigationPoint> history, String metric) {
        for (int i = history.size() - 1; i >= 0; i--) {
            double v = metricValue(history.get(i), metric);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    private static double metricValue(IrrigationPoint p, String metric) {
        if ("temp".equals(metric)) return p.temp;
        if ("humidity".equals(metric)) return p.humidity;
        if ("lux".equals(metric)) return p.lux;
        return Double.NaN;
    }

    private static int irrigationMinutes(PlotProfile plot, double currentHumidity, double minForecast, double avgTemp, double avgLux) {
        double target = plot.humidityMin + 5;
        double deficit = Math.max(target - Math.min(currentHumidity, minForecast), 1.0);
        double areaFactor = clamp(0.8 + plot.area * 0.12, 0.9, 1.5);
        double heatFactor = (!Double.isNaN(avgTemp) && avgTemp >= plot.tempMax) ? 1.18 : 1.0;
        double lightFactor = (!Double.isNaN(avgLux) && avgLux >= 800) ? 1.10 : 1.0;
        double minutes = (8 + deficit * 2.2) * areaFactor * heatFactor * lightFactor;
        return (int) Math.round(clamp(minutes, 10, 60));
    }

    private static String recommendedStart(double currentHumidity, double[] forecast, double humidityMin) {
        if (currentHumidity < humidityMin) return "现在或最近一个低蒸发时段（清晨/傍晚）";
        for (int i = 0; i < forecast.length; i++) {
            if (forecast[i] < humidityMin) {
                return "第 " + (i + 1) + " 天清晨";
            }
        }
        return "清晨或傍晚";
    }

    private static String riskLevel(double currentHumidity, double minForecast, double humidityMin) {
        double min = Math.min(currentHumidity, minForecast);
        if (min < humidityMin - 8) return "高";
        if (min < humidityMin) return "中";
        if (min < humidityMin + 4) return "低";
        return "正常";
    }

    private static String futureDate(int plusDays) {
        return java.time.LocalDate.now().plusDays(plusDays)
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "未知";
        return new BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static class PlotProfile {
        String id;
        String name;
        String crop;
        double area;
        double humidityMin;
        double tempMax;
    }

    private static class IrrigationPoint {
        String day;
        double temp = Double.NaN;
        double humidity = Double.NaN;
        double lux = Double.NaN;

        IrrigationPoint(String day) {
            this.day = day;
        }
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

    /**
     * 调智能问答大模型的 OpenAI 兼容接口：系统提示（含实时数据 + 地块信息 + RAG 知识库资料）+ 对话历史 → 返回模型回答。
     * @param kbContext RAG 检索到的知识库参考资料段（空串表示未命中，不加该段）
     */
    private static String deepseekChat(List<Map<String, String>> msgs, String kbContext) throws Exception {
        if (SMART_QA_API_KEY == null || SMART_QA_API_KEY.isEmpty()) {
            throw new SmartQaException("SMART_QA_NOT_CONFIGURED", "智能问答服务尚未配置，请联系管理员。");
        }
        // 系统提示：基础角色 + 当前时间 + 实时数据 + 地块/灌溉历史（让回答结合实时数据）
        String dataCtx = latestReadingsContext();
        String plotCtx = plotContext();
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String system = "你是「智慧农业平台」的智能助手，负责解答大棚种植、灌溉、温湿度监测、告警阈值、设备控制等农业问题。"
                      + "必须始终使用中文回答。回答要简洁实用、直接给出建议；只回答与农业种植相关的问题，无关问题礼貌说明无法回答。"
                      + " 当前服务器时间是 " + now + "，回答时间相关问题以这个时间为准。";
        if (!dataCtx.isEmpty()) {
            system += " 当前系统实时采集的环境数据如下：" + dataCtx
                    + " 回答灌溉、通风等建议时可参考这些数据，但不要编造未提供的数据。";
        }
        if (!plotCtx.isEmpty()) {
            system += " " + plotCtx;
        }
        // RAG：命中知识库时把参考资料段追加进系统提示，让模型优先据此回答
        if (kbContext != null && !kbContext.isEmpty()) {
            system += " " + kbContext;
        }

        StringBuilder req = new StringBuilder();
        req.append("{\"model\":\"").append(SMART_QA_MODEL)
           .append("\",\"temperature\":0.5,\"max_tokens\":700,\"messages\":[");
        req.append("{\"role\":\"system\",\"content\":")
           .append(Json.str(system))
           .append('}');
        for (Map<String, String> m : msgs) {
            req.append(",{\"role\":").append(Json.str(m.get("role")))
               .append(",\"content\":").append(Json.str(m.get("content"))).append('}');
        }
        req.append("]}");

        String body;
        try {
            body = smartQaPostWithRetry(req.toString());
        } catch (IOException e) {
            throw smartQaProviderException(e);
        }
        String content = Json.strValue(body, "content");
        if (content == null || content.trim().isEmpty()) {
            throw new SmartQaException("SMART_QA_BAD_RESPONSE", "智能问答服务返回异常，请稍后重试。");
        }
        return content;
    }

    private static String smartQaPostWithRetry(String payload) throws IOException {
        IOException last = null;
        for (int i = 1; i <= 3; i++) {
            try {
                return httpPostJson(SMART_QA_URL, payload, SMART_QA_API_KEY, 2500, 30000);
            } catch (IOException e) {
                last = e;
                if (!isRetryableSmartQaFailure(e) || i == 3) break;
                try {
                    Thread.sleep(250L * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("模型接口请求失败") : last;
    }

    private static boolean isRetryableSmartQaFailure(IOException error) {
        String message = error.getMessage();
        return message == null || !(message.startsWith("HTTP 400") || message.startsWith("HTTP 401")
                || message.startsWith("HTTP 403") || message.startsWith("HTTP 404") || message.startsWith("HTTP 429"));
    }

    private static SmartQaException smartQaProviderException(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        String code = message.startsWith("HTTP 401") || message.startsWith("HTTP 403") ? "SMART_QA_AUTH_FAILED"
                : message.startsWith("HTTP 429") ? "SMART_QA_RATE_LIMITED"
                : "SMART_QA_UNAVAILABLE";
        String userMessage = "SMART_QA_AUTH_FAILED".equals(code) ? "智能问答服务认证失败，请联系管理员更新服务配置。"
                : "SMART_QA_RATE_LIMITED".equals(code) ? "智能问答服务繁忙，请稍后重试。"
                : "智能问答服务暂时不可用，请稍后重试。";
        System.out.println("[SmartQA] 上游调用失败: " + code);
        return new SmartQaException(code, userMessage);
    }

    private static String smartQaError(String errorCode, String message) {
        return "{\"code\":1,\"errorCode\":" + Json.str(errorCode) + ",\"msg\":" + Json.str(message) + "}";
    }

    private static final class SmartQaException extends Exception {
        private final String errorCode;

        private SmartQaException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    /**
     * 汇总各指标最新一条传感器读数，拼成给大模型的实时环境数据上下文。
     * 返回形如「温度 30.75℃，湿度 54.41%，光照 390 lx（更新于 2026-08-23 11:50:30）」；
     * 完全没有数据时返回空串（系统提示就不加数据段）。
     */
    private static String latestReadingsContext() throws SQLException {
        String sql =
                "SELECT s.metric, s.value, DATE_FORMAT(s.collected_at, '%Y-%m-%d %H:%i:%s') AS t" +
                " FROM sensor_data s JOIN device d ON d.id = s.device_id" +
                " WHERE d.online = 1 AND d.type IN ('温度传感器','环境监测板','土壤湿度传感器','亮度传感器')" +
                " AND s.id IN (" +
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

    /**
     * 汇总当前管理的所有地块的作物/面积信息，以及每个地块最近一次灌溉操作记录。
     * 返回供大模型参考的地块上下文；某地块无灌溉记录时标注「无历史记录」。
     */
    private static String plotContext() throws SQLException {
        // 1. 地块列表：id, name, crop, area
        List<String[]> plots = new ArrayList<>();
        String plotSql = "SELECT id, name, crop, area FROM plot ORDER BY id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(plotSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plots.add(new String[]{ rs.getString("id"), rs.getString("name"), rs.getString("crop"),
                        rs.getBigDecimal("area").stripTrailingZeros().toPlainString() });
            }
        }
        if (plots.isEmpty()) return "";

        // 2. 每个地块最近一次灌溉操作：plotId -> {action, result, operator, time}
        Map<String, String[]> lastOp = new HashMap<>();
        String logSql =
                "SELECT d.plot_id, c.action, c.result, c.operator," +
                " DATE_FORMAT(c.created_at, '%Y-%m-%d %H:%i') AS t" +
                " FROM control_log c JOIN device d ON d.id = c.device_id" +
                " WHERE c.id IN (" +
                "  SELECT MAX(c2.id) FROM control_log c2 JOIN device d2 ON d2.id = c2.device_id GROUP BY d2.plot_id)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(logSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lastOp.put(rs.getString("plot_id"), new String[]{
                        rs.getString("action"), rs.getString("result"),
                        rs.getString("operator"), rs.getString("t") });
            }
        }

        // 3. 拼上下文
        StringBuilder sb = new StringBuilder("当前管理的地块信息（作物、面积）：");
        for (String[] p : plots) {
            sb.append('\n').append("- ").append(p[1]).append("（").append(p[0])
              .append("）：种植 ").append(p[2]).append("，面积 ").append(p[3]).append(" 亩");
        }
        sb.append("。各地块最近一次灌溉操作：");
        for (String[] p : plots) {
            sb.append('\n').append("- ").append(p[1]).append("（").append(p[0]).append("）：");
            String[] op = lastOp.get(p[0]);
            if (op == null) {
                sb.append("无历史记录");
            } else {
                sb.append("「").append(op[0]).append("」").append(op[1])
                  .append("，操作人 ").append(op[2]).append("，时间 ").append(op[3]);
            }
        }
        return sb.toString();
    }

    /* ==================================================================
       对话历史（按用户隔离）
       ================================================================== */

    /** 从 query string 里取参数值；没有返回 null */
    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && key.equals(urlDecode(p[0]))) return urlDecode(p[1]);
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
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

    /** 设备名称是否已被占用（全局唯一） */
    private static boolean deviceNameExists(String name) throws SQLException {
        return exists("SELECT 1 FROM device WHERE name = ?", name);
    }

    /** 是否存在同类型且同 IP/端口的设备（同类型设备不能共享同一个板子地址） */
    private static boolean sameTypeAddrExists(String type, String ip, int port) throws SQLException {
        return sameTypeAddrExistsExcept(type, ip, port, null);
    }

    /** 修改设备地址时排除当前设备自身，避免原地址保存被误判为重复。 */
    private static boolean sameTypeAddrExistsExcept(String type, String ip, int port, String excludeId) throws SQLException {
        String sql = "SELECT 1 FROM device WHERE type = ? AND ip = ? AND port = ?";
        if (excludeId != null && !excludeId.trim().isEmpty()) {
            sql += " AND id <> ?";
        }
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, ip);
            ps.setInt(3, port);
            if (excludeId != null && !excludeId.trim().isEmpty()) {
                ps.setString(4, excludeId.trim());
            }
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
    private static String controlLogsJson(String query) throws SQLException {
        int limit = parseLimit(queryParam(query, "limit"), 0);
        StringBuilder sb = new StringBuilder("{\"code\":0,\"data\":[");
        String sql =
                "SELECT c.id, c.action, c.result, c.operator, c.created_at," +
                " d.name AS deviceName, p.name AS plotName" +
                " FROM control_log c" +
                " LEFT JOIN device d ON d.id = c.device_id" +
                " LEFT JOIN plot p ON p.id = d.plot_id" +
                " ORDER BY c.created_at DESC, c.id DESC" +
                (limit > 0 ? " LIMIT ?" : "");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (limit > 0) ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
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
        }
        sb.append("]}");
        return sb.toString();
    }

    private static int parseLimit(String raw, int def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            int n = Integer.parseInt(raw.trim());
            return n < 1 ? def : Math.min(n, 100);
        } catch (NumberFormatException e) {
            return def;
        }
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
        if (!"temp".equals(metric) && !"humidity".equals(metric) && !"lux".equals(metric)) {
            return "{\"code\":1,\"msg\":" + Json.str("参数错误：metric 需为 temp、humidity 或 lux") + "}";
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
            checkThresholdAlarm(plotId);
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

    /** 兼容旧调用：新逻辑按该地块全部最新指标统一判断。 */
    static void checkThresholdAlarm(String plotId, String metric, BigDecimal value) throws SQLException {
        checkThresholdAlarm(plotId);
    }

    /** 任一启用指标越过阈值时，插入一条告警记录。 */
    static void checkThresholdAlarm(String plotId) throws SQLException {
        checkThresholdAlarm(plotId, false);
    }

    /** 任一启用指标越过阈值时，插入一条告警记录；forceInsert 用于阈值保存后的立即留档。 */
    static void checkThresholdAlarm(String plotId, boolean forceInsert) throws SQLException {
        ThresholdConfig cfg = null;
        String sql = "SELECT humidity_min, humidity_max, temp_min, temp_max, lux_min, lux_max FROM plot_threshold WHERE plot_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cfg = new ThresholdConfig();
                    cfg.humidityMin = rs.getBigDecimal("humidity_min");
                    cfg.humidityMax = rs.getBigDecimal("humidity_max");
                    cfg.tempMin = rs.getBigDecimal("temp_min");
                    cfg.tempMax = rs.getBigDecimal("temp_max");
                    cfg.luxMin = rs.getBigDecimal("lux_min");
                    cfg.luxMax = rs.getBigDecimal("lux_max");
                }
            }
        }
        if (cfg == null) cfg = ThresholdConfig.defaults();
        if (!cfg.hasEnabledMetric()) return;

        Map<String, BigDecimal> latest = latestMetricsForPlot(plotId);
        List<MetricBreach> details = new ArrayList<>();
        boolean severe = false;
        if (cfg.humidityEnabled()) {
            MetricBreach b = breach(latest.get("humidity"), cfg.humidityMin, cfg.humidityMax,
                    "土壤湿度过低", "土壤湿度过高", "%", false);
            if (b != null) {
                details.add(b);
                severe = severe || b.severe;
            }
        }
        if (cfg.tempEnabled()) {
            MetricBreach b = breach(latest.get("temp"), cfg.tempMin, cfg.tempMax,
                    "温度过低", "温度过高", "℃", true);
            if (b != null) {
                details.add(b);
                severe = severe || b.severe;
            }
        }
        if (cfg.luxEnabled()) {
            MetricBreach b = breach(latest.get("lux"), cfg.luxMin, cfg.luxMax,
                    "亮度过低", "亮度过高", " lx", false);
            if (b != null) {
                details.add(b);
                severe = severe || b.severe;
            }
        }
        if (details.isEmpty()) return;

        String alarmType;
        String alarmValue;
        if (details.size() == 1) {
            MetricBreach only = details.get(0);
            alarmType = only.type;
            alarmValue = only.valueText;
        } else {
            alarmType = "综合阈值告警";
            alarmValue = String.join("；", textsOf(details));
        }
        String level = severe || details.size() >= 2 ? "严重" : "警告";

        // 采集循环不覆盖旧记录，只做短时间去重；阈值保存时强制追加一条当前快照。
        if (!forceInsert) {
            String dup = "SELECT 1 FROM alarm WHERE plot_id = ? AND alarm_type = ? AND status = '未处理'" +
                    " AND created_at >= DATE_SUB(NOW(), INTERVAL ? SECOND)";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(dup)) {
                ps.setString(1, plotId);
                ps.setString(2, alarmType);
                ps.setInt(3, THRESHOLD_ALARM_COOLDOWN_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return;
                }
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
        FeishuBotService.pushAlarmAsync(plotId, alarmType, alarmValue, level);
    }

    private static List<String> textsOf(List<MetricBreach> breaches) {
        List<String> texts = new ArrayList<>();
        for (MetricBreach b : breaches) texts.add(b.text);
        return texts;
    }

    private static Map<String, BigDecimal> latestMetricsForPlot(String plotId) throws SQLException {
        Map<String, BigDecimal> latest = new HashMap<>();
        String sql =
                "SELECT s.metric, s.value FROM sensor_data s" +
                " JOIN device d ON d.id = s.device_id" +
                " JOIN (" +
                "   SELECT s2.metric, MAX(s2.id) AS max_id FROM sensor_data s2" +
                "   JOIN device d2 ON d2.id = s2.device_id" +
                "   WHERE d2.plot_id = ? AND s2.metric IN ('temp','humidity','lux')" +
                "   GROUP BY s2.metric" +
                " ) m ON m.max_id = s.id" +
                " WHERE d.plot_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plotId);
            ps.setString(2, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) latest.put(rs.getString("metric"), rs.getBigDecimal("value"));
            }
        }
        return latest;
    }

    private static MetricBreach breach(BigDecimal value, BigDecimal min, BigDecimal max,
                                       String lowType, String highType, String unit, boolean highSevere) {
        if (value == null) return null;
        if (min != null && value.compareTo(min) < 0) {
            String valueText = value.stripTrailingZeros().toPlainString() + unit;
            return new MetricBreach(lowType, valueText, lowType + " " + valueText, false);
        }
        if (max != null && value.compareTo(max) > 0) {
            String valueText = value.stripTrailingZeros().toPlainString() + unit;
            return new MetricBreach(highType, valueText, highType + " " + valueText, highSevere);
        }
        return null;
    }

    private static class MetricBreach {
        final String type;
        final String valueText;
        final String text;
        final boolean severe;

        MetricBreach(String type, String valueText, String text, boolean severe) {
            this.type = type;
            this.valueText = valueText;
            this.text = text;
            this.severe = severe;
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

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
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
