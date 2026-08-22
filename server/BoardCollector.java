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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 板子数据采集器：从环境监测板周期读取温湿度/光照，写入 sensor_data。
 *
 * 板子协议（TCP，见 07_板子环境数据采集.sql）：
 *   连接后发送 "query\n"，
 *   板子返回数据流，每行形如：
 *     DATA      TEMP:31.22 HUMI:56.03 LUX:533.34
 *     HEARTBEAT TEMP:31.26 HUMI:56.05 LUX:533.34
 *   DATA 是采集读数，HEARTBEAT 是周期性心跳，两者都带当前 TEMP/HUMI/LUX。
 *
 * 写入映射（metric 名对齐前端监测页 /api/plots/{plotId}/realtime 读取的字段）：
 *   TEMP -> temp、HUMI -> humidity、LUX -> lux
 * 统一写到 D006（环境监测板-01，P001）名下，采集时间用 MySQL NOW(3)。
 */
public class BoardCollector {

    /** 板子地址 */
    public static final String BOARD_HOST = "192.168.70.190";
    public static final int    BOARD_PORT = 8888;

    /** 写入 sensor_data 的设备 id（见 07_板子环境数据采集.sql） */
    public static final String DEVICE_ID = "D006";

    /** 采集间隔：每 30 秒读一次板子 */
    private static final long COLLECT_INTERVAL_MS = 30_000L;

    /** 每次连接后读取数据的窗口（毫秒），取窗口内第一条有效读数。
     *  板子收到 query 后立刻回第一条 DATA，窗口留 5 秒足够覆盖网络抖动。 */
    private static final long READ_WINDOW_MS = 5_000L;

    /** 最近一次采集状态（方便排查） */
    private static volatile boolean lastOk = false;
    private static volatile String  lastError = "";
    private static volatile String  lastReading = "";

    /** 周期采集与手动刷新共用一把锁，避免并发连板子（板子可能单线程处理） */
    private static final Object LOCK = new Object();

    public static boolean isLastOk()    { return lastOk; }
    public static String  getLastError()  { return lastError; }
    public static String  getLastReading() { return lastReading; }

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
                + " 秒读取 " + BOARD_HOST + ":" + BOARD_PORT + "，写入设备 " + DEVICE_ID);
    }

    /** 供 API 手动刷新调用：立即读一次板子并写库 */
    public static Map<String, String> refreshNow() {
        return collect();
    }

    /** 读一次板子并写库；成功返回读数，失败返回 null（并记录 lastError） */
    private static Map<String, String> collect() {
        synchronized (LOCK) {
            try {
                Map<String, String> reading = fetchOnce();
                if (reading != null) {
                    writeToDb(reading);
                    lastOk = true;
                    lastError = "";
                    lastReading = "TEMP:" + reading.get("temp")
                            + " HUMI:" + reading.get("humidity")
                            + " LUX:" + reading.get("lux");
                } else {
                    lastOk = false;
                    lastError = "未读到有效读数（板子无响应？）";
                }
                return reading;
            } catch (Exception e) {
                lastOk = false;
                lastError = String.valueOf(e);
                return null;
            }
        }
    }

    /**
     * 连板子发 "query\n"，在读取窗口内解析第一条 DATA/HEARTBEAT 读数。
     * <p>板子每条记录以 NUL 字节(0x00)结尾（不是换行），所以按字节读、遇 0x00 切一条。
     * @return {temp, humidity, lux}；窗口内没读到有效记录返回 null
     */
    static Map<String, String> fetchOnce() throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(BOARD_HOST, BOARD_PORT), 3000);
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
                    if (n < 0) break; // 连接被板子关闭
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
                    // 板子按固定周期推数据，超时说明还没到下一个推送周期，继续等
                }
            }
        }
        return null;
    }

    /**
     * 解析一行板子数据，形如：
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
    static void writeToDb(Map<String, String> reading) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            String sql = "INSERT INTO sensor_data (device_id, metric, value, collected_at) VALUES (?,?,?,NOW(3))";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                addReading(ps, "temp",     reading.get("temp"));
                addReading(ps, "humidity", reading.get("humidity"));
                addReading(ps, "lux",      reading.get("lux"));
                ps.executeBatch();
            }
            c.commit();
        }

        // 阈值告警：按设备所属地块检查 temp/humidity 是否越过阈值（未处理告警自动去重）
        String plotId = Api.plotOfDevice(DEVICE_ID);
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

    private static void addReading(PreparedStatement ps, String metric, String value) throws SQLException {
        ps.setString(1, DEVICE_ID);
        ps.setString(2, metric);
        ps.setBigDecimal(3, new BigDecimal(value));
        ps.addBatch();
    }
}
