package server;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

/**
 * 飞书机器人命令路由：把聊天里的短命令转换成智慧农业平台的数据摘要。
 */
public class FeishuCommandRouter {

    private static final DecimalFormat NUM_FMT = new DecimalFormat("0.##");
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("MM-dd HH:mm");

    private FeishuCommandRouter() {}

    public static String route(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty() || "帮助".equals(text) || "help".equalsIgnoreCase(text) || "?".equals(text)) {
            return help();
        }
        if ("ping".equalsIgnoreCase(text)) {
            return "pong";
        }
        if (containsAny(text, "地块", "田块", "大棚", "环境", "实时")) {
            return plotStatus();
        }
        if (containsAny(text, "设备", "板子", "水泵", "灌溉")) {
            return deviceStatus();
        }
        if (containsAny(text, "告警", "报警", "异常", "风险")) {
            return todayAlarms();
        }
        if (containsAny(text, "无人机", "位置", "坐标")) {
            return droneStatus();
        }
        if (containsAny(text, "今日总结", "日报", "总结", "日志")) {
            return dailySummary();
        }
        return "我已收到：" + text + "\n\n" + help();
    }

    public static String dailySummary() {
        return "智慧农业今日总结\n\n"
                + plotStatus() + "\n\n"
                + todayAlarms() + "\n\n"
                + todayOperations();
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
        return "当前支持：ping、地块状态、设备状态、今日告警、无人机位置、今日总结。";
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
}
