package server;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Properties;

/**
 * 飞书机器人命令路由：把聊天里的短命令转换成智慧农业平台的数据摘要。
 */
public class FeishuCommandRouter {

    private static final Path LOCAL_CONFIG = Paths.get("config", "feishu.local.properties");
    private static final DecimalFormat NUM_FMT = new DecimalFormat("0.##");
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("MM-dd HH:mm");

    private static final MockPlot[] MOCK_PLOTS = {
            new MockPlot("P001", "一号大棚", "番茄", 26.4, 62, 520, 4, 4),
            new MockPlot("P002", "二号大棚", "黄瓜", 24.1, 55, 480, 3, 3),
            new MockPlot("P003", "三号菜地", "生菜", 28.7, 41, 760, 2, 1),
            new MockPlot("P004", "四号果园", "草莓", 22.8, 68, 610, 3, 3)
    };

    private static final MockDevice[] MOCK_DEVICES = {
            new MockDevice("D001", "土壤湿度传感器-01", "土壤湿度", "一号大棚", true, false, false, ""),
            new MockDevice("D002", "温度传感器-01", "温度", "一号大棚", true, false, false, ""),
            new MockDevice("D003", "灌溉电磁阀-01", "灌溉设备", "一号大棚", true, true, false, ""),
            new MockDevice("D006", "灌溉电磁阀-02", "灌溉设备", "二号大棚", true, true, true, "192.168.70.167:8888"),
            new MockDevice("D007", "土壤湿度传感器-03", "土壤湿度", "三号菜地", false, false, false, ""),
            new MockDevice("D009", "大棚摄像头-01", "摄像头", "一号大棚", true, false, false, "192.168.70.168:8080")
    };

    private static final MockAlarm[] MOCK_ALARMS = {
            new MockAlarm("A001", "09:32", "三号菜地", "土壤湿度过低", "38%", "警告", "未处理"),
            new MockAlarm("A002", "06:15", "一号大棚", "温度过高", "36.5℃", "严重", "已处理")
    };

    private static final MockOperation[] MOCK_OPERATIONS = {
            new MockOperation("10:05", "二号大棚", "灌溉电磁阀-02", "开启", "成功", "农户·张老三"),
            new MockOperation("08:30", "一号大棚", "灌溉电磁阀-01", "关闭", "成功", "农户·张老三")
    };

    private FeishuCommandRouter() {}

    public static String route(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty() || "帮助".equals(text) || "help".equalsIgnoreCase(text) || "?".equals(text)) {
            return help();
        }
        if ("ping".equalsIgnoreCase(text)) {
            return "pong";
        }
        boolean mockMode = useMockData();
        if (!mockMode) {
            return FeishuSmartQaService.answer(text, false);
        }
        if (isPlotStatusQuestion(text)) {
            return mockPlotStatus();
        }
        if (isDeviceStatusQuestion(text)) {
            return mockDeviceStatus();
        }
        if (isAlarmStatusQuestion(text)) {
            return mockTodayAlarms();
        }
        if (containsAny(text, "无人机", "位置", "坐标")) {
            return mockDroneStatus();
        }
        if (containsAny(text, "今日总结", "日报", "总结", "日志")) {
            return dailySummary();
        }
        return FeishuSmartQaService.answer(text, true);
    }

    public static String dailySummary() {
        if (useMockData()) {
            return "智慧农业今日总结（模拟数据）\n\n"
                    + mockPlotStatus() + "\n\n"
                    + mockTodayAlarms() + "\n\n"
                    + mockTodayOperations() + "\n\n"
                    + mockDroneStatus();
        }
        return FeishuSmartQaService.answer("请生成今天的智慧农业日报总结，重点包括地块环境、设备运行、告警处理、灌溉操作和明日建议。", false);
    }

    public static String alarmNotification(String plotId, String alarmType, String value, String level) {
        String plotName = queryPlotName(plotId);
        return "智慧农业告警\n"
                + "地块：" + safe(plotName, plotId) + "（" + safe(plotId, "-") + "）\n"
                + "类型：" + safe(alarmType, "-") + "\n"
                + "数值：" + safe(value, "-") + "\n"
                + "级别：" + safe(level, "-") + "\n"
                + "时间：" + TIME_FMT.format(new java.util.Date());
    }

    private static String help() {
        return "当前支持：ping、地块状态、设备状态、今日告警、无人机位置、今日总结。"
                + "\n当前数据源：" + ("mock".equals(dataMode()) ? "模拟数据" : "真实数据库/板子");
    }

    private static String mockPlotStatus() {
        StringBuilder sb = new StringBuilder("地块状态（模拟数据）");
        for (MockPlot p : MOCK_PLOTS) {
            sb.append('\n')
              .append(p.id).append(' ')
              .append(p.name).append("：")
              .append(p.crop).append("，")
              .append("温度 ").append(NUM_FMT.format(p.temp)).append("℃，")
              .append("湿度 ").append(NUM_FMT.format(p.humidity)).append("%，")
              .append("光照 ").append(NUM_FMT.format(p.lux)).append(" lx，")
              .append("设备 ").append(p.onlineCount).append('/').append(p.deviceCount).append(" 在线");
        }
        return sb.toString();
    }

    private static String mockDeviceStatus() {
        int online = 0;
        int running = 0;
        for (MockDevice d : MOCK_DEVICES) {
            if (d.online) online++;
            if (d.running) running++;
        }

        StringBuilder sb = new StringBuilder("设备状态（模拟数据）：在线 ")
                .append(online).append('/').append(MOCK_DEVICES.length)
                .append("，运行 ").append(running);
        for (MockDevice d : MOCK_DEVICES) {
            sb.append('\n')
              .append(d.id).append(' ')
              .append(d.name).append("（").append(d.plotName).append("）：")
              .append(d.online ? "在线" : "离线");
            if (d.controllable) {
                sb.append('，').append(d.running ? "正在灌溉" : "未灌溉");
            }
            if (!isBlank(d.address)) {
                sb.append('，').append(d.address);
            }
        }
        return sb.toString();
    }

    private static String mockTodayAlarms() {
        StringBuilder sb = new StringBuilder("今日告警（模拟数据）");
        for (MockAlarm a : MOCK_ALARMS) {
            sb.append('\n')
              .append('#').append(a.id).append(' ')
              .append(a.time).append(' ')
              .append(a.plotName).append("：")
              .append(a.type).append(' ')
              .append(a.value).append("，")
              .append(a.level).append("，")
              .append(a.status);
        }
        return sb.toString();
    }

    private static String mockTodayOperations() {
        StringBuilder sb = new StringBuilder("今日操作（模拟数据）");
        for (MockOperation op : MOCK_OPERATIONS) {
            sb.append('\n')
              .append(op.time).append(' ')
              .append(op.plotName).append(' ')
              .append(op.deviceName).append(' ')
              .append(op.action).append(' ')
              .append(op.result).append("，操作人 ").append(op.operator);
        }
        return sb.toString();
    }

    private static String mockDroneStatus() {
        return "无人机位置（模拟数据）\n"
                + "DRONE-01：正在巡检三号菜地，坐标 29.6428,106.5663，电量 78%，高度 18m，任务状态：巡田拍照中。";
    }

    private static String plotStatus() {
        String sql = "SELECT p.id, p.name, p.crop, p.area,"
                + " (SELECT COUNT(*) FROM device d WHERE d.plot_id = p.id) AS device_count,"
                + " (SELECT COUNT(*) FROM device d WHERE d.plot_id = p.id AND d.online = 1) AS online_count,"
                + " (SELECT s.value FROM sensor_data s JOIN device d ON d.id = s.device_id"
                + "   WHERE d.plot_id = p.id AND s.metric = 'temp' AND d.online = 1"
                + "   ORDER BY s.collected_at DESC LIMIT 1) AS temp,"
                + " (SELECT s.value FROM sensor_data s JOIN device d ON d.id = s.device_id"
                + "   WHERE d.plot_id = p.id AND s.metric = 'humidity' AND d.online = 1"
                + "   ORDER BY s.collected_at DESC LIMIT 1) AS humidity,"
                + " (SELECT s.value FROM sensor_data s JOIN device d ON d.id = s.device_id"
                + "   WHERE d.plot_id = p.id AND s.metric = 'lux' AND d.online = 1"
                + "   ORDER BY s.collected_at DESC LIMIT 1) AS lux"
                + " FROM plot p ORDER BY p.id LIMIT 8";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder("地块状态");
            int count = 0;
            while (rs.next()) {
                count++;
                sb.append('\n')
                  .append(rs.getString("id")).append(' ')
                  .append(rs.getString("name")).append("：")
                  .append(rs.getString("crop")).append("，")
                  .append("温度 ").append(num(rs.getBigDecimal("temp"), "℃")).append("，")
                  .append("湿度 ").append(num(rs.getBigDecimal("humidity"), "%")).append("，")
                  .append("光照 ").append(num(rs.getBigDecimal("lux"), " lx")).append("，")
                  .append("设备 ").append(rs.getInt("online_count")).append('/')
                  .append(rs.getInt("device_count")).append(" 在线");
            }
            if (count == 0) return "暂无地块数据。";
            return sb.toString();
        } catch (SQLException e) {
            return "查询地块状态失败：" + e.getMessage();
        }
    }

    private static String deviceStatus() {
        String countSql = "SELECT COUNT(*) total, SUM(online = 1) online_count, SUM(running = 1) running_count FROM device";
        String listSql = "SELECT d.id, d.name, d.type, d.online, d.running, d.ip, d.port, d.last_heartbeat,"
                + " p.name AS plot_name FROM device d LEFT JOIN plot p ON p.id = d.plot_id"
                + " ORDER BY d.online ASC, d.id LIMIT 10";
        try (Connection conn = DBUtil.getConnection()) {
            StringBuilder sb = new StringBuilder("设备状态");
            try (PreparedStatement ps = conn.prepareStatement(countSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sb.append("：在线 ").append(rs.getInt("online_count"))
                      .append('/').append(rs.getInt("total"))
                      .append("，运行 ").append(rs.getInt("running_count"));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(listSql);
                 ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    sb.append('\n')
                      .append(rs.getString("id")).append(' ')
                      .append(rs.getString("name")).append("（")
                      .append(safe(rs.getString("plot_name"), "未绑定地块")).append("）：")
                      .append(rs.getInt("online") == 1 ? "在线" : "离线");
                    if (rs.getString("type") != null && rs.getString("type").contains("灌溉")) {
                        sb.append('，').append(rs.getInt("running") == 1 ? "正在灌溉" : "未灌溉");
                    }
                    String ip = rs.getString("ip");
                    int port = rs.getInt("port");
                    if (ip != null && !ip.trim().isEmpty()) {
                        sb.append("，").append(ip);
                        if (port > 0) sb.append(':').append(port);
                    }
                }
                if (count == 0) return "暂无设备数据。";
            }
            return sb.toString();
        } catch (SQLException e) {
            return "查询设备状态失败：" + e.getMessage();
        }
    }

    private static String todayAlarms() {
        String sql = "SELECT a.id, a.plot_id, p.name AS plot_name, a.alarm_type, a.value, a.level,"
                + " a.status, a.created_at FROM alarm a LEFT JOIN plot p ON p.id = a.plot_id"
                + " WHERE DATE(a.created_at) = CURDATE()"
                + " ORDER BY (a.status = '未处理') DESC, a.created_at DESC LIMIT 10";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder("今日告警");
            int count = 0;
            while (rs.next()) {
                count++;
                sb.append('\n')
                  .append('#').append(rs.getLong("id")).append(' ')
                  .append(time(rs.getTimestamp("created_at"))).append(' ')
                  .append(safe(rs.getString("plot_name"), rs.getString("plot_id"))).append("：")
                  .append(rs.getString("alarm_type")).append(' ')
                  .append(safe(rs.getString("value"), "-")).append("，")
                  .append(rs.getString("level")).append("，")
                  .append(rs.getString("status"));
            }
            if (count == 0) return "今日暂无告警。";
            return sb.toString();
        } catch (SQLException e) {
            return "查询今日告警失败：" + e.getMessage();
        }
    }

    private static String todayOperations() {
        String sql = "SELECT c.created_at, d.name AS device_name, p.name AS plot_name, c.action, c.result, c.operator"
                + " FROM control_log c LEFT JOIN device d ON d.id = c.device_id"
                + " LEFT JOIN plot p ON p.id = d.plot_id"
                + " WHERE DATE(c.created_at) = CURDATE() ORDER BY c.created_at DESC LIMIT 8";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder("今日操作");
            int count = 0;
            while (rs.next()) {
                count++;
                sb.append('\n')
                  .append(time(rs.getTimestamp("created_at"))).append(' ')
                  .append(safe(rs.getString("plot_name"), "未知地块")).append(' ')
                  .append(safe(rs.getString("device_name"), "未知设备")).append(' ')
                  .append(rs.getString("action")).append(' ')
                  .append(rs.getString("result"));
                String operator = rs.getString("operator");
                if (operator != null && !operator.trim().isEmpty()) {
                    sb.append("，操作人 ").append(operator);
                }
            }
            if (count == 0) return "今日暂无灌溉控制操作。";
            return sb.toString();
        } catch (SQLException e) {
            return "查询今日操作失败：" + e.getMessage();
        }
    }

    private static String droneStatus() {
        try (Connection conn = DBUtil.getConnection()) {
            if (!tableExists(conn, "drone_status")) {
                return "无人机状态表尚未接入。建议后续新增 drone_status 表，字段可包含 drone_id、plot_id、lat、lng、battery、task_status、updated_at。";
            }
            String sql = "SELECT * FROM drone_status ORDER BY updated_at DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "暂无无人机状态数据。";
                return "无人机位置：地块 " + safe(value(rs, "plot_id"), "-")
                        + "，坐标 " + safe(value(rs, "lat"), "?") + "," + safe(value(rs, "lng"), "?")
                        + "，电量 " + safe(value(rs, "battery"), "-")
                        + "，状态 " + safe(value(rs, "task_status"), "-")
                        + "，更新时间 " + safe(value(rs, "updated_at"), "-");
            }
        } catch (SQLException e) {
            return "查询无人机状态失败：" + e.getMessage();
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    private static String queryPlotName(String plotId) {
        if (plotId == null || plotId.trim().isEmpty()) return null;
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM plot WHERE id = ?")) {
            ps.setString(1, plotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : plotId;
            }
        } catch (SQLException e) {
            return plotId;
        }
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("@_user_\\d+", "")
                .replaceAll("<at[^>]*>.*?</at>", "")
                .replace('\u00A0', ' ')
                .trim();
        while (cleaned.startsWith("@")) {
            int blank = cleaned.indexOf(' ');
            if (blank < 0) return "";
            cleaned = cleaned.substring(blank + 1).trim();
        }
        return cleaned;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static boolean isPlotStatusQuestion(String text) {
        return containsAny(text, "地块状态", "田块状态", "地块情况", "田块情况", "大棚状态", "大棚情况",
                "环境数据", "实时数据", "当前数据", "现在数据", "温湿度", "光照数据")
                || ("地块".equals(text) || "田块".equals(text) || "大棚".equals(text));
    }

    private static boolean isDeviceStatusQuestion(String text) {
        return containsAny(text, "设备状态", "设备情况", "设备列表", "板子状态", "板子情况",
                "水泵状态", "水泵情况", "灌溉设备状态", "设备在线", "哪些设备");
    }

    private static boolean isAlarmStatusQuestion(String text) {
        return containsAny(text, "今日告警", "告警状态", "告警情况", "告警列表", "报警状态",
                "报警情况", "异常状态", "异常情况", "风险告警", "未处理告警");
    }

    private static String num(BigDecimal n, String unit) {
        return n == null ? "-" : NUM_FMT.format(n) + unit;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String time(Timestamp ts) {
        return ts == null ? "--" : TIME_FMT.format(ts);
    }

    private static String value(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            return value == null ? null : String.valueOf(value);
        } catch (SQLException e) {
            return null;
        }
    }

    private static boolean useMockData() {
        return "mock".equals(dataMode());
    }

    private static String dataMode() {
        String mode = readConfig("FEISHU_DATA_MODE");
        if (isBlank(mode)) return "real";
        mode = mode.trim().toLowerCase();
        if ("mock".equals(mode) || "simulator".equals(mode) || "模拟".equals(mode)) return "mock";
        return "real";
    }

    private static String readConfig(String key) {
        String env = System.getenv(key);
        if (!isBlank(env)) return env;
        if (!Files.isRegularFile(LOCAL_CONFIG)) return "";

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(LOCAL_CONFIG)) {
            props.load(in);
            return props.getProperty(key, "");
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class MockPlot {
        final String id;
        final String name;
        final String crop;
        final double temp;
        final double humidity;
        final double lux;
        final int deviceCount;
        final int onlineCount;

        MockPlot(String id, String name, String crop, double temp, double humidity, double lux, int deviceCount, int onlineCount) {
            this.id = id;
            this.name = name;
            this.crop = crop;
            this.temp = temp;
            this.humidity = humidity;
            this.lux = lux;
            this.deviceCount = deviceCount;
            this.onlineCount = onlineCount;
        }
    }

    private static class MockDevice {
        final String id;
        final String name;
        final String type;
        final String plotName;
        final boolean online;
        final boolean controllable;
        final boolean running;
        final String address;

        MockDevice(String id, String name, String type, String plotName, boolean online,
                   boolean controllable, boolean running, String address) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.plotName = plotName;
            this.online = online;
            this.controllable = controllable;
            this.running = running;
            this.address = address;
        }
    }

    private static class MockAlarm {
        final String id;
        final String time;
        final String plotName;
        final String type;
        final String value;
        final String level;
        final String status;

        MockAlarm(String id, String time, String plotName, String type, String value, String level, String status) {
            this.id = id;
            this.time = time;
            this.plotName = plotName;
            this.type = type;
            this.value = value;
            this.level = level;
            this.status = status;
        }
    }

    private static class MockOperation {
        final String time;
        final String plotName;
        final String deviceName;
        final String action;
        final String result;
        final String operator;

        MockOperation(String time, String plotName, String deviceName, String action, String result, String operator) {
            this.time = time;
            this.plotName = plotName;
            this.deviceName = deviceName;
            this.action = action;
            this.result = result;
            this.operator = operator;
        }
    }
}
