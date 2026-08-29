package server;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

/**
 * 飞书智能问答兜底服务。
 *
 * 明确命令由 FeishuCommandRouter 处理；其余农业问题进入这里，
 * 由大模型结合模拟/真实农场上下文生成回答。
 */
public class FeishuSmartQaService {

    private static final Path LOCAL_CONFIG = Paths.get("config", "feishu.local.properties");
    private static final int RAG_TOP_K = 3;

    private FeishuSmartQaService() {}

    public static String answer(String question, boolean mockMode) {
        if (!bool(readConfig("FEISHU_SMART_QA_ENABLED"), true)) {
            return "我已收到：" + question + "\n当前智能问答兜底未启用，可先使用：地块状态、设备状态、今日告警、无人机位置、今日总结。";
        }

        String apiKey = firstNonBlank(readConfig("SMART_QA_API_KEY"), readConfig("DEEPSEEK_API_KEY"));
        if (isBlank(apiKey)) {
            return "智能问答入口已经接好，但还没有配置大模型 API Key。\n"
                    + "请在环境变量 SMART_QA_API_KEY 或本机 config/feishu.local.properties 中配置后重启后端。\n"
                    + "当前仍可使用：地块状态、设备状态、今日告警、无人机位置、今日总结。";
        }

        try {
            String context = mockMode ? mockContext() : realContext(question);
            return callModel(question, context, apiKey);
        } catch (Exception e) {
            return "智能问答调用失败：" + e.getMessage()
                    + "\n你仍可先使用固定命令：地块状态、设备状态、今日告警、无人机位置、今日总结。";
        }
    }

    private static String callModel(String question, String context, String apiKey) throws Exception {
        String url = firstNonBlank(readConfig("SMART_QA_API_URL"), "https://api.deepseek.com/chat/completions");
        String model = firstNonBlank(readConfig("SMART_QA_MODEL"), "deepseek-chat");
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        String system = "你是「智慧农业平台」的飞书智能助手，负责回答大棚种植、灌溉、温湿度监测、告警阈值、设备控制、巡田无人机等问题。"
                + "必须使用中文，回答要简洁、实用、适合农场值班人员直接执行。"
                + "如果问题与农业或本平台无关，请礼貌说明无法回答。"
                + "当前服务器时间是 " + now + "。"
                + "回答时优先结合下面的平台上下文，不要编造上下文里没有的实时数据。\n"
                + context;

        String body = "{\"model\":" + Json.str(model)
                + ",\"temperature\":0.6,\"messages\":["
                + "{\"role\":\"system\",\"content\":" + Json.str(system) + "},"
                + "{\"role\":\"user\",\"content\":" + Json.str(question) + "}"
                + "]}";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String respBody = resp.body();
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + abbreviate(respBody, 180));
        }
        String content = Json.strValue(respBody, "content");
        if (isBlank(content)) {
            throw new IOException("响应解析失败，未找到 content 字段");
        }
        return content.trim();
    }

    private static String mockContext() {
        return "【当前为模拟数据模式】\n"
                + "地块：一号大棚种植番茄，温度26.4℃，湿度62%，光照520 lx，4/4设备在线；"
                + "二号大棚种植黄瓜，温度24.1℃，湿度55%，光照480 lx，3/3设备在线；"
                + "三号菜地种植生菜，温度28.7℃，湿度41%，光照760 lx，1/2设备在线，存在湿度偏低风险；"
                + "四号果园种植草莓，温度22.8℃，湿度68%，光照610 lx，3/3设备在线。\n"
                + "设备：灌溉电磁阀-01在线未灌溉，灌溉电磁阀-02在线且正在灌溉，土壤湿度传感器-03离线，大棚摄像头-01在线。\n"
                + "今日告警：三号菜地土壤湿度过低38%，警告，未处理；一号大棚温度过高36.5℃，严重，已处理。\n"
                + "今日操作：10:05 二号大棚灌溉电磁阀-02开启成功；08:30 一号大棚灌溉电磁阀-01关闭成功。\n"
                + "无人机：DRONE-01正在巡检三号菜地，坐标29.6428,106.5663，电量78%，高度18m，任务状态为巡田拍照中。";
    }

    private static String realContext(String question) {
        StringBuilder sb = new StringBuilder("【当前为真实数据模式】");
        try {
            String latest = latestReadingsContext();
            if (!latest.isEmpty()) sb.append('\n').append(latest);
        } catch (SQLException e) {
            sb.append("\n实时传感器数据暂不可用：").append(e.getMessage());
        }
        try {
            String plots = plotContext();
            if (!plots.isEmpty()) sb.append('\n').append(plots);
        } catch (SQLException e) {
            sb.append("\n地块/操作上下文暂不可用：").append(e.getMessage());
        }
        try {
            String devices = deviceContext();
            if (!devices.isEmpty()) sb.append('\n').append(devices);
        } catch (SQLException e) {
            sb.append("\n设备上下文暂不可用：").append(e.getMessage());
        }
        try {
            String alarms = alarmContext();
            if (!alarms.isEmpty()) sb.append('\n').append(alarms);
        } catch (SQLException e) {
            sb.append("\n告警上下文暂不可用：").append(e.getMessage());
        }
        try {
            String drone = droneContext();
            if (!drone.isEmpty()) sb.append('\n').append(drone);
        } catch (SQLException e) {
            sb.append("\n无人机上下文暂不可用：").append(e.getMessage());
        }
        try {
            String kb = Rag.buildContext(question, RAG_TOP_K);
            if (!kb.isEmpty()) sb.append('\n').append(kb);
        } catch (Exception e) {
            sb.append("\n知识库检索暂不可用：").append(e.getMessage());
        }
        return sb.toString();
    }

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
                BigDecimal value = rs.getBigDecimal("value");
                String v = value == null ? null : value.stripTrailingZeros().toPlainString();
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

    private static String plotContext() throws SQLException {
        String plotSql = "SELECT p.id, p.name, p.crop, p.area,"
                + " (SELECT c.action FROM control_log c JOIN device d ON d.id = c.device_id"
                + "   WHERE d.plot_id = p.id ORDER BY c.created_at DESC LIMIT 1) AS last_action,"
                + " (SELECT c.result FROM control_log c JOIN device d ON d.id = c.device_id"
                + "   WHERE d.plot_id = p.id ORDER BY c.created_at DESC LIMIT 1) AS last_result,"
                + " (SELECT DATE_FORMAT(c.created_at, '%Y-%m-%d %H:%i') FROM control_log c JOIN device d ON d.id = c.device_id"
                + "   WHERE d.plot_id = p.id ORDER BY c.created_at DESC LIMIT 1) AS last_time"
                + " FROM plot p ORDER BY p.id LIMIT 8";
        StringBuilder sb = new StringBuilder("地块与最近操作：");
        int count = 0;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(plotSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                count++;
                BigDecimal area = rs.getBigDecimal("area");
                sb.append('\n').append("- ").append(rs.getString("name")).append("（").append(rs.getString("id")).append("）：")
                  .append("种植 ").append(rs.getString("crop"));
                if (area != null) sb.append("，面积 ").append(area.stripTrailingZeros().toPlainString()).append(" 亩");
                String action = rs.getString("last_action");
                if (isBlank(action)) {
                    sb.append("，最近无灌溉操作记录");
                } else {
                    sb.append("，最近操作 ").append(rs.getString("last_time")).append(' ')
                      .append(action).append(' ').append(rs.getString("last_result"));
                }
            }
        }
        return count == 0 ? "" : sb.toString();
    }

    private static String deviceContext() throws SQLException {
        String sql = "SELECT d.id, d.name, d.type, d.online, d.running, d.ip, d.port, d.last_heartbeat,"
                + " p.name AS plot_name FROM device d LEFT JOIN plot p ON p.id = d.plot_id"
                + " ORDER BY d.online ASC, d.id LIMIT 12";
        StringBuilder sb = new StringBuilder("设备运行状态：");
        int total = 0;
        int online = 0;
        int running = 0;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                if (rs.getInt("online") == 1) online++;
                if (rs.getInt("running") == 1) running++;
                sb.append('\n').append("- ").append(rs.getString("name")).append("（").append(rs.getString("id")).append("，")
                  .append(nullTo(rs.getString("type"), "未知类型")).append("，")
                  .append(nullTo(rs.getString("plot_name"), "未绑定地块")).append("）：")
                  .append(rs.getInt("online") == 1 ? "在线" : "离线");
                if (rs.getString("type") != null && rs.getString("type").contains("灌溉")) {
                    sb.append("，").append(rs.getInt("running") == 1 ? "正在灌溉" : "未灌溉");
                }
                String ip = rs.getString("ip");
                int port = rs.getInt("port");
                if (!isBlank(ip)) {
                    sb.append("，地址 ").append(ip);
                    if (port > 0) sb.append(':').append(port);
                }
            }
        }
        if (total == 0) return "";
        return "设备概况：在线 " + online + "/" + total + "，运行中 " + running + "。\n" + sb;
    }

    private static String alarmContext() throws SQLException {
        String sql = "SELECT a.id, p.name AS plot_name, a.alarm_type, a.value, a.level, a.status,"
                + " DATE_FORMAT(a.created_at, '%Y-%m-%d %H:%i') AS t"
                + " FROM alarm a LEFT JOIN plot p ON p.id = a.plot_id"
                + " WHERE DATE(a.created_at) = CURDATE()"
                + " ORDER BY (a.status = '未处理') DESC, a.created_at DESC LIMIT 10";
        StringBuilder sb = new StringBuilder("今日告警：");
        int count = 0;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                count++;
                sb.append('\n').append("- #").append(rs.getLong("id")).append(' ')
                  .append(nullTo(rs.getString("t"), "未知时间")).append(' ')
                  .append(nullTo(rs.getString("plot_name"), "未知地块")).append("：")
                  .append(nullTo(rs.getString("alarm_type"), "未知告警")).append(' ')
                  .append(nullTo(rs.getString("value"), "-")).append("，")
                  .append(nullTo(rs.getString("level"), "未知级别")).append("，")
                  .append(nullTo(rs.getString("status"), "未知状态"));
            }
        }
        return count == 0 ? "今日暂无告警。" : sb.toString();
    }

    private static String droneContext() throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            if (!tableExists(conn, "drone_status")) {
                return "无人机状态：真实无人机状态表 drone_status 尚未接入。";
            }
            String sql = "SELECT * FROM drone_status ORDER BY updated_at DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "无人机状态：暂无记录。";
                return "无人机状态：地块 " + nullTo(value(rs, "plot_id"), "-")
                        + "，坐标 " + nullTo(value(rs, "lat"), "?") + "," + nullTo(value(rs, "lng"), "?")
                        + "，电量 " + nullTo(value(rs, "battery"), "-")
                        + "，任务 " + nullTo(value(rs, "task_status"), "-")
                        + "，更新时间 " + nullTo(value(rs, "updated_at"), "-");
            }
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    private static String value(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            return value == null ? null : String.valueOf(value);
        } catch (SQLException e) {
            return null;
        }
    }

    private static String readConfig(String key) {
        String env = System.getenv(key);
        if (!isBlank(env)) return env.trim();
        if (!Files.isRegularFile(LOCAL_CONFIG)) return "";

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(LOCAL_CONFIG)) {
            props.load(in);
            return props.getProperty(key, "").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean bool(String value, boolean def) {
        if (isBlank(value)) return def;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value) || "启用".equals(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullTo(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
