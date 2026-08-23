package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备数据采集器：周期从所有配置了 ip/port 的设备（板子协议）读取温湿度/光照，写入 sensor_data。
 *
 * 板子协议（TCP，见 07_板子环境数据采集.sql）：
 *   连接后发送 "query\n"，
 *   板子返回数据流，每行形如：
 *     DATA      TEMP:31.22 HUMI:56.03 LUX:533.34
 *     HEARTBEAT TEMP:31.26 HUMI:56.05 LUX:533.34
 *   DATA 是采集读数，HEARTBEAT 是周期性心跳，两者都带当前 TEMP/HUMI/LUX。
 *
 * 采集目标来自 device 表：ip/port 不为空的设备都会被轮询（动态添加设备后自动纳入采集）。
 * 写入映射（metric 名对齐前端监测页 /api/plots/{plotId}/realtime 读取的字段）：
 *   TEMP -> temp、HUMI -> humidity、LUX -> lux
 * 采集时间用 MySQL NOW(3)。非板子设备连不上/不回包时优雅失败（记入 deviceStatus），不影响其他设备。
 */
public class BoardCollector {

    /** 采集间隔：每 30 秒读一次板子 */
    private static final long COLLECT_INTERVAL_MS = 30_000L;

    /** 每次连接后读取数据的窗口（毫秒），取窗口内第一条有效读数。
     *  板子收到 query 后立刻回第一条 DATA，窗口留 5 秒足够覆盖网络抖动。 */
    private static final long READ_WINDOW_MS = 5_000L;

    /** 最近一次采集状态（方便排查） */
    private static volatile boolean lastOk = false;
    private static volatile String  lastError = "";
    private static volatile String  lastReading = "";

    /** 最近一轮各设备采集结果快照：deviceId -> "ok:TEMP:.. HUMI:.. LUX:.." 或 "err:<信息>"。
     *  volatile + 整体替换发布：锁内组快照、锁外整体赋值，读取方无锁、不撕裂。 */
    private static volatile Map<String, String> deviceStatus = new HashMap<>();

    /** 周期采集与手动刷新共用一把锁，避免并发连板子（板子可能单线程处理） */
    private static final Object LOCK = new Object();

    public static boolean isLastOk()      { return lastOk; }
    public static String  getLastError()  { return lastError; }
    public static String  getLastReading() { return lastReading; }
    public static Map<String, String> getDeviceStatus() { return deviceStatus; }

    private BoardCollector() {}

    /** 启动后台守护线程周期采集，采集失败只记状态、不中断循环 */
    public static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                collect();
                try {
                    Thread.sleep(COLLECT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "board-collector");
        t.setDaemon(true);
        t.start();
        System.out.println("[BoardCollector] 已启动：每 " + COLLECT_INTERVAL_MS / 1000
                + " 秒遍历所有配置了 IP/端口的设备并采集");
    }

    /** 供 API 手动刷新调用：立即读一次全部设备并写库 */
    public static Map<String, String> refreshNow() {
        return collect();
    }

    /** 从 device 表加载采集目标：所有 ip/port 不为空的设备 */
    private static List<String[]> loadTargets() throws SQLException {
        List<String[]> list = new ArrayList<>();
        String sql = "SELECT id, ip, port FROM device" +
                     " WHERE ip IS NOT NULL AND TRIM(ip) <> '' AND port IS NOT NULL ORDER BY id";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new String[]{ rs.getString("id"), rs.getString("ip"), rs.getString("port") });
            }
        }
        return list;
    }

    /**
     * 读一轮全部目标设备并写库；成功返回第一台成功的读数，全部失败返回 null。
     * 单台失败只记入状态与摘要，不中断整轮；所有状态字段都在锁内写。
     */
    private static Map<String, String> collect() {
        synchronized (LOCK) {
            List<String[]> targets;
            try {
                targets = loadTargets();
            } catch (SQLException e) {
                lastOk = false;
                lastError = "加载设备列表失败：" + e;
                return null;
            }
            if (targets.isEmpty()) {
                lastOk = false;
                lastError = "没有配置 IP/端口的设备";
                deviceStatus = new HashMap<>();
                return null;
            }

            Map<String, String> status = new HashMap<>();
            Map<String, String> first = null;
            StringBuilder errs = new StringBuilder();
            for (String[] t : targets) {
                String deviceId = t[0];
                String host = t[1];
                int port;
                try {
                    port = Integer.parseInt(t[2]);
                } catch (NumberFormatException e) {
                    status.put(deviceId, "err:port 非法(" + t[2] + ")");
                    errs.append(deviceId).append(":端口非法; ");
                    continue;
                }
                try {
                    Map<String, String> reading = fetchOnce(host, port);
                    if (reading != null) {
                        writeToDb(deviceId, reading);
                        status.put(deviceId, "ok:TEMP:" + reading.get("temp")
                                + " HUMI:" + reading.get("humidity")
                                + " LUX:" + reading.get("lux"));
                        if (first == null) first = reading;
                    } else {
                        status.put(deviceId, "err:未读到有效读数");
                        errs.append(deviceId).append(":未读到有效读数; ");
                    }
                } catch (Exception e) {
                    status.put(deviceId, "err:" + e);
                    errs.append(deviceId).append(":").append(e).append("; ");
                }
            }

            lastOk = first != null;
            lastReading = first == null ? "" : "TEMP:" + first.get("temp")
                    + " HUMI:" + first.get("humidity") + " LUX:" + first.get("lux");
            lastError = errs.length() == 0 ? "" : errs.toString();
            deviceStatus = status;
            return first;
        }
    }

    /**
     * 连设备发 "query\n"，在读取窗口内解析第一条 DATA/HEARTBEAT 读数。
     * <p>设备每条记录以 NUL 字节(0x00)结尾（不是换行），所以按字节读、遇 0x00 切一条。
     * @return {temp, humidity, lux}；窗口内没读到有效记录返回 null
     */
    static Map<String, String> fetchOnce(String host, int port) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write("query\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + READ_WINDOW_MS;
            while (System.currentTimeMillis() < end) {
                try {
                    int n = in.read(buf);
                    if (n < 0) break; // 连接被设备关闭
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == 0) {
                            // 一条完整记录（可能跨多个 read）
                            Map<String, String> m = parseLine(sb.toString().trim());
                            sb.setLength(0);
                            if (m != null) return m;
                        } else {
                            sb.append((char) (buf[i] & 0xFF));
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 设备按固定周期推数据，超时说明还没到下一个推送周期，继续等
                }
            }
        }
        return null;
    }

    /**
     * 解析一行设备数据，形如：
     *   DATA TEMP:31.22 HUMI:56.03 LUX:533.34
     *   HEARTBEAT TEMP:31.26 HUMI:56.05 LUX:533.34
     * @return {temp, humidity, lux}；不是有效行返回 null
     */
    static Map<String, String> parseLine(String line) {
        if (line == null) return null;
        int idx = line.indexOf(' ');
        if (idx < 0) return null;
        String tag = line.substring(0, idx).trim();
        if (!"DATA".equalsIgnoreCase(tag) && !"HEARTBEAT".equalsIgnoreCase(tag)) return null;
        String temp = extract(line.substring(idx + 1), "TEMP");
        String humi = extract(line.substring(idx + 1), "HUMI");
        String lux  = extract(line.substring(idx + 1), "LUX");
        if (temp == null || humi == null || lux == null) return null;
        Map<String, String> m = new HashMap<>();
        m.put("temp", temp);
        m.put("humidity", humi);
        m.put("lux", lux);
        return m;
    }

    /** 从 "TEMP:31.22 HUMI:56.03 ..." 中取 key 冒号后的数值 */
    private static String extract(String body, String key) {
        String p = key + ":";
        int i = body.indexOf(p);
        if (i < 0) return null;
        int start = i + p.length();
        int end = body.indexOf(' ', start);
        if (end < 0) end = body.length();
        String v = body.substring(start, end).trim();
        return v.isEmpty() ? null : v;
    }

    /** 把一次读数写入 sensor_data：temp/humidity/lux 三行，同一采集时间戳 */
    static void writeToDb(String deviceId, Map<String, String> reading) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            String sql = "INSERT INTO sensor_data (device_id, metric, value, collected_at) VALUES (?,?,?,NOW(3))";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                addReading(ps, deviceId, "temp",     reading.get("temp"));
                addReading(ps, deviceId, "humidity", reading.get("humidity"));
                addReading(ps, deviceId, "lux",      reading.get("lux"));
                ps.executeBatch();
            }
            c.commit();
        }

        // 阈值告警：按设备所属地块检查 temp/humidity 是否越过阈值（未处理告警自动去重）
        String plotId = Api.plotOfDevice(deviceId);
        if (plotId != null) {
            try {
                Api.checkThresholdAlarm(plotId, "temp", new BigDecimal(reading.get("temp")));
                Api.checkThresholdAlarm(plotId, "humidity", new BigDecimal(reading.get("humidity")));
            } catch (SQLException e) {
                // 告警检查失败不影响本次采集结果（数据已入库），下个周期会重试
                System.out.println("[BoardCollector] 告警检查失败（数据已入库）：" + e);
            }
        }
    }

    private static void addReading(PreparedStatement ps, String deviceId, String metric, String value) throws SQLException {
        ps.setString(1, deviceId);
        ps.setString(2, metric);
        ps.setBigDecimal(3, new BigDecimal(value));
        ps.addBatch();
    }
}
