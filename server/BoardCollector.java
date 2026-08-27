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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 设备数据采集器：与每台板子保持一条 TCP 长连接，持续接收板子推送的数据并写库。
 *
 * 板子协议（TCP，见 wifi-iot/app/sensor_tcp_server/sensor_server_demo.c）：
 *   连接后发送 "query\n"，板子回 DATA TEMP:.. HUMI:.. LUX:..，之后每 5 秒推 HEARTBEAT。
 *   （记录以 NUL 字节 0x00 结尾；控制指令 on/off 同样走这条连接，板子回 motor started/stopped。）
 *
 * 板子固件一次只处理一条客户端连接（单客户端 accept 循环），因此：
 *   - 连接按 <ip:port> 去重：同一块板子上的多个设备（如温度/湿度/亮度传感器都指向同一块板子）
 *     共用一条长连接，板子推的数据会写入所有共享该连接的设备；
 *   - 灌溉控制指令复用该长连接下发。
 * 连接断开后自动重连；设备动态增删由后台监控线程每 30 秒扫描 device 表。
 */
public class BoardCollector {

    private static final long MONITOR_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_DELAY_MS = 5_000L;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int POLL_TIMEOUT_MS = 500;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int COMMAND_TIMEOUT_MS = 15_000;
    private static final int ONLINE_STALE_SECONDS = 25;

    private static volatile boolean lastOk = false;
    private static volatile String lastError = "";
    private static volatile String lastReading = "";

    /** 各设备最近一次采集结果快照：deviceId -> "ok:.." 或 "err:.." */
    private static volatile Map<String, String> deviceStatus = new HashMap<>();

    /** 各设备最近一次有效读数：deviceId -> {temp, humidity, lux}（供手动刷新返回） */
    private static final Map<String, Map<String, String>> latestReadings = new ConcurrentHashMap<>();

    /** 板子长连接：<ip:port> -> DeviceConn */
    private static final Map<String, DeviceConn> connectors = new HashMap<>();

    /** 每个 <ip:port> 共享连接的设备列表（每轮扫描重建） */
    private static final Map<String, List<String>> connDevices = new HashMap<>();

    /** 正在运行连接的 <ip:port> 集合（地址删除后移除，线程退出） */
    private static final Set<String> runningConns = ConcurrentHashMap.newKeySet();

    /** 每块板子的待发送命令队列，按 <ip:port> 共享，避免重连后命令落到旧连接对象。 */
    private static final Map<String, BlockingQueue<CommandRequest>> commandQueues = new ConcurrentHashMap<>();

    /**
     * 传感器落库线程池：读循环只负责收板子数据、更新内存快照，真正的数据库写入丢给后台线程。
     * 否则每个心跳要给多台设备写库（每次都新开数据库连接，单次 ~0.5s），会长时间阻塞读循环，
     * 导致板子回的控制确认（motor started/stopped）积压在 socket 缓冲里读不到、指令超时。
     */
    private static final java.util.concurrent.ExecutorService writePool =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "sensor-writer");
                t.setDaemon(true);
                return t;
            });

    public static boolean isLastOk()        { return lastOk; }
    public static String  getLastError()    { return lastError; }
    public static String  getLastReading()  { return lastReading; }
    public static Map<String, String> getDeviceStatus() { return deviceStatus; }

    private BoardCollector() {}

    /** 判断某个地址当前是否已有可用长连接。 */
    public static boolean hasLiveConnection(String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) return false;
        String key = host.trim() + ":" + port;
        synchronized (connectors) {
            DeviceConn conn = connectors.get(key);
            return conn != null && conn.hasLiveSocket();
        }
    }

    /** 快速探测普通板载设备地址是否能建立 TCP 连接。 */
    public static boolean probeAddress(String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) return false;
        if (hasLiveConnection(host, port)) return true;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 启动后台监控线程：立即扫描一次，之后每 30 秒扫描设备表，动态建连/断连 */
    public static void start() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    ensureConnectors();
                } catch (Exception e) {
                    System.out.println("[BoardCollector] 设备列表扫描失败: " + e);
                }
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "board-monitor");
        monitor.setDaemon(true);
        monitor.start();
        System.out.println("[BoardCollector] 常驻长连接模式已启动：同一块板子（ip:port）共享一条 TCP 连接，数据写入所有关联传感器");
    }

    /** 设备地址变更后供 API 主动触发一次扫描，避免等后台 30 秒轮询。 */
    public static void rescanNow() {
        try {
            ensureConnectors();
        } catch (Exception e) {
            System.out.println("[BoardCollector] 设备列表即时扫描失败: " + e);
        }
    }

    /** 供 API 手动刷新调用：通过板子长连接发送 query，再返回最近一次捕获的读数。 */
    public static Map<String, String> refreshNow() {
        List<String> keys;
        synchronized (connDevices) {
            keys = new ArrayList<>(connDevices.keySet());
        }
        boolean sent = false;
        long end = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
        while (System.currentTimeMillis() < end && !sent) {
            for (String key : keys) {
                if (enqueueCommand(key, "query")) {
                    sent = true;
                    break;
                }
            }
            if (!sent) sleepQuietly(300);
        }
        if (!sent) {
            lastError = "板子长连接未就绪，query 未下发";
            return null;
        }
        sleepQuietly(300);
        for (Map.Entry<String, Map<String, String>> e : latestReadings.entrySet()) {
            return new HashMap<>(e.getValue());
        }
        lastError = "暂无板子读数";
        return null;
    }

    private static boolean enqueueCommand(String key, String action) {
        CommandRequest req = new CommandRequest(action);
        commandQueues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>()).offer(req);
        try {
            return req.done.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) && req.ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 在板子共享长连接上给设备下发 on/off；该板子未连接/设备无地址返回 false */
    public static boolean sendPersistentCommand(String deviceId, String action) {
        String key = deviceKey(deviceId);
        if (key == null) {
            System.out.println("[BoardCollector] 控制指令失败：设备无地址 " + deviceId + " -> " + action);
            return false;
        }

        DeviceConn conn;
        synchronized (connectors) {
            conn = connectors.get(key);
        }
        System.out.println("[BoardCollector] 控制指令入队: device=" + deviceId
                + ", action=" + action
                + ", key=" + key
                + ", hasConn=" + (conn != null)
                + ", liveSocket=" + (conn != null && conn.hasLiveSocket())
                + ", running=" + runningConns.contains(key)
                + ", devices=" + currentDevices(key));
        if (enqueueCommand(key, action)) {
            System.out.println("[BoardCollector] 控制指令成功（长连接队列）: " + deviceId + " -> " + action);
            return true;
        }
        System.out.println("[BoardCollector] 控制指令失败：长连接队列超时 " + deviceId + " -> " + action + " @ " + key);
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 查设备 ip:port，拼成共享连接 key；未配置地址返回 null */
    private static String deviceKey(String deviceId) {
        String sql = "SELECT ip, port FROM device WHERE id = ?";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String ip = rs.getString("ip");
                    Object port = rs.getObject("port");
                    if (ip != null && !ip.trim().isEmpty() && port != null) {
                        return ip.trim() + ":" + port;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("[BoardCollector] 查设备地址失败: " + e);
        }
        return null;
    }

    /** 从 device 表加载采集目标：所有 ip/port 不为空的非摄像头设备 */
    private static List<String[]> loadTargets() throws SQLException {
        List<String[]> list = new ArrayList<>();
        String sql = "SELECT id, ip, port FROM device" +
                     " WHERE ip IS NOT NULL AND TRIM(ip) <> '' AND port IS NOT NULL" +
                     " AND type <> '摄像头' ORDER BY id";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new String[]{ rs.getString("id"), rs.getString("ip"), rs.getString("port") });
            }
        }
        return list;
    }

    /** 扫描设备表：无地址设备置离线；同一 <ip:port> 只建一条长连接（多个设备共享） */
    private static synchronized void ensureConnectors() throws SQLException {
        // 1. 无 ip/port 的设备不可能在线
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE device SET online = 0 WHERE ip IS NULL OR TRIM(ip) = '' OR port IS NULL")) {
            ps.executeUpdate();
        }
        // 2. 加载目标，按 <ip:port> 分组
        List<String[]> targets = loadTargets();
        markStaleTargetsOffline();
        Map<String, List<String>> byAddr = new HashMap<>();
        for (String[] t : targets) {
            String key = t[1] + ":" + t[2];
            byAddr.computeIfAbsent(key, k -> new ArrayList<>()).add(t[0]);
        }
        synchronized (connDevices) {
            connDevices.clear();
            connDevices.putAll(byAddr);
        }
        // 3. 停止已不存在的 <ip:port> 连接
        for (String key : runningConns) {
            if (!byAddr.containsKey(key)) runningConns.remove(key);
        }
        // 4. 为新出现的 <ip:port> 建连
        for (Map.Entry<String, List<String>> e : byAddr.entrySet()) {
            String key = e.getKey();
            if (runningConns.add(key)) {
                String[] hp = key.split(":");
                try {
                    startConnector(key, hp[0], Integer.parseInt(hp[1]));
                } catch (NumberFormatException ex) {
                    runningConns.remove(key);
                }
            }
        }
        markLiveConnectorsOnline(byAddr.keySet());
    }

    /** 已存在的长连接无需等下一条读数，重扫后立刻把挂到该地址的设备同步为在线。 */
    private static void markLiveConnectorsOnline(Iterable<String> keys) {
        for (String key : keys) {
            DeviceConn conn;
            synchronized (connectors) {
                conn = connectors.get(key);
            }
            if (conn != null && conn.hasLiveSocket()) {
                setOnlineFor(key, true);
            }
        }
    }

    /**
     * 为一块板子启动常驻连接线程：连接后发 query，循环读 DATA/HEARTBEAT 写库到所有共享设备；
     * 断流（读超时）或断开后全部置离线并间隔重连。控制指令由外部经 conn.command() 复用本连接发送。
     */
    private static void startConnector(String key, String host, int port) {
        DeviceConn conn = new DeviceConn(key);
        synchronized (connectors) {
            connectors.put(key, conn);
        }
        Thread t = new Thread(() -> {
            while (runningConns.contains(key)) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                    s.setSoTimeout(POLL_TIMEOUT_MS);
                    conn.socket = s;
                    setOnlineFor(key, true);
                    putStatusFor(key, "ok:已连接");
                    OutputStream out = s.getOutputStream();
                    out.write("query\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    InputStream in = s.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[512];
                    long lastReadAt = System.currentTimeMillis();
                    while (runningConns.contains(key)) {
                        try {
                            conn.drainCommands(out);
                            int n = in.read(buf);
                            if (n < 0) break; // 板子关闭连接
                            lastReadAt = System.currentTimeMillis();
                            for (int i = 0; i < n; i++) {
                                if (buf[i] == 0) {
                                    String record = sb.toString().trim();
                                    sb.setLength(0);
                                    handleRecord(conn, record);
                                } else {
                                    sb.append((char) (buf[i] & 0xFF));
                                }
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            conn.drainCommands(out);
                            if (System.currentTimeMillis() - lastReadAt > READ_TIMEOUT_MS) {
                                putStatusFor(key, "err:读超时，板子断流");
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    putStatusFor(key, "err:" + e.getMessage());
                } finally {
                    conn.socket = null;
                    markOfflineForIfStale(key);
                }
                if (!runningConns.contains(key)) break;
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            synchronized (connectors) {
                connectors.remove(key);
            }
        }, "board-conn-" + key);
        t.setDaemon(true);
        t.start();
    }

    /** 处理一条 NUL 结尾的记录：DATA/HEARTBEAT 写入所有共享设备；motor 回包确认控制指令 */
    private static void handleRecord(DeviceConn conn, String record) {
        if (record.isEmpty()) return;
        if (record.contains("motor")) {
            // on/off 指令的确认回包（motor started / motor stopped）
            synchronized (conn.lock) {
                conn.confirmed = true;
            }
            return;
        }
        Map<String, String> m = parseLine(record);
        if (m == null) return; // 其它未知记录忽略
        List<String> ids = currentDevices(conn.key);
        if (ids.isEmpty()) return;
        // 内存快照同步更新（毫秒级），数据库落库丢给后台线程池，避免阻塞读循环
        for (String id : ids) {
            final String did = id;
            final Map<String, String> reading = new HashMap<>(m);
            writePool.execute(() -> {
                try {
                    writeToDb(did, reading);
                } catch (Exception e) {
                    System.out.println("[BoardCollector] " + did + " 写库失败: " + e);
                }
            });
            putStatus(id, "ok:TEMP:" + m.get("temp") + " HUMI:" + m.get("humidity") + " LUX:" + m.get("lux"));
            latestReadings.put(id, reading);
        }
        lastOk = true;
        lastReading = "TEMP:" + m.get("temp") + " HUMI:" + m.get("humidity") + " LUX:" + m.get("lux");
        lastError = "";
    }

    /** 当前共享某条连接的所有设备 id */
    private static List<String> currentDevices(String key) {
        synchronized (connDevices) {
            List<String> ids = connDevices.get(key);
            return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        }
    }

    /** 解析一行设备数据，形如：DATA TEMP:31.22 HUMI:56.03 LUX:533.34 */
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
        markDeviceOnline(deviceId);

        // 阈值告警：按地块最新 temp/humidity/lux 统一判断，启用指标全部越界才报警。
        String plotId = Api.plotOfDevice(deviceId);
        if (plotId != null) {
            try {
                Api.checkThresholdAlarm(plotId);
            } catch (SQLException e) {
                System.out.println("[BoardCollector] 告警检查失败（数据已入库）：" + e);
            }
        }
    }

    /** 成功收到并写入新数据后，以最新数据为准标记设备在线。 */
    private static void markDeviceOnline(String deviceId) {
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE device SET online = 1, last_heartbeat = NOW(3) WHERE id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[BoardCollector] 更新设备在线心跳失败: " + e);
        }
    }

    /** 周期扫描时，把一段时间内没有新传感器数据的板载设备判为离线。 */
    private static void markStaleTargetsOffline() {
        String sql =
                "UPDATE device d SET d.online = 0" +
                " WHERE d.ip IS NOT NULL AND TRIM(d.ip) <> '' AND d.port IS NOT NULL" +
                " AND d.type <> '摄像头'" +
                " AND NOT EXISTS (" +
                "   SELECT 1 FROM sensor_data s" +
                "   WHERE s.device_id = d.id AND s.collected_at >= DATE_SUB(NOW(3), INTERVAL ? SECOND)" +
                " )";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ONLINE_STALE_SECONDS);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[BoardCollector] 标记超时设备离线失败: " + e);
        }
    }

    /** 连接断开时只在数据确实过期后置离线，避免短连接板子被反复显示离线。 */
    private static void markOfflineForIfStale(String key) {
        List<String> ids = currentDevices(key);
        if (ids.isEmpty()) return;
        String sql =
                "UPDATE device d SET d.online = 0" +
                " WHERE d.id = ?" +
                " AND NOT EXISTS (" +
                "   SELECT 1 FROM sensor_data s" +
                "   WHERE s.device_id = d.id AND s.collected_at >= DATE_SUB(NOW(3), INTERVAL ? SECOND)" +
                " )";
        try (Connection c = DBUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (String id : ids) {
                ps.setString(1, id);
                ps.setInt(2, ONLINE_STALE_SECONDS);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.out.println("[BoardCollector] 检查连接离线状态失败: " + e);
        }
    }

    private static void addReading(PreparedStatement ps, String deviceId, String metric, String value) throws SQLException {
        ps.setString(1, deviceId);
        ps.setString(2, metric);
        ps.setBigDecimal(3, new BigDecimal(value));
        ps.addBatch();
    }

    /** 把共享某条连接的所有设备置在线/离线 */
    private static void setOnlineFor(String key, boolean online) {
        List<String> ids = currentDevices(key);
        try (Connection c = DBUtil.getConnection()) {
            for (String id : ids) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE device SET online = ? WHERE id = ?")) {
                    ps.setInt(1, online ? 1 : 0);
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.out.println("[BoardCollector] 更新在线状态失败: " + e);
        }
    }

    /** 更新某个 <ip:port> 下所有设备的状态快照 */
    private static void putStatusFor(String key, String status) {
        for (String id : currentDevices(key)) putStatus(id, status);
    }

    /** 更新单台设备状态快照（copy-on-write 发布，读取方无锁） */
    private static void putStatus(String deviceId, String status) {
        Map<String, String> copy = new HashMap<>(deviceStatus);
        copy.put(deviceId, status);
        deviceStatus = copy;
    }

    /** 一台板子的常驻连接：后台线程读心跳；外部通过 command() 复用本连接发 on/off 并等确认 */
    private static class DeviceConn {
        final String key;   // "ip:port"
        volatile Socket socket;
        final Object lock = new Object();   // 发指令与等待确认的锁
        final BlockingQueue<CommandRequest> commands;
        volatile String pendingAction;
        volatile boolean confirmed;

        DeviceConn(String key) {
            this.key = key;
            this.commands = commandQueues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());
        }

        boolean hasLiveSocket() {
            Socket s = socket;
            return s != null && !s.isClosed();
        }

        void drainCommands(OutputStream out) throws IOException {
            CommandRequest req;
            while ((req = commands.poll()) != null) {
                try {
                    synchronized (lock) {
                        pendingAction = req.action;
                        confirmed = false;
                        out.write((req.action + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    req.ok = true;
                    System.out.println("[BoardCollector] 长连接命令已发送: " + key + " -> " + req.action);
                } catch (IOException e) {
                    req.ok = false;
                    System.out.println("[BoardCollector] 长连接命令发送异常: " + key + " -> " + req.action + ", " + e.getMessage());
                    throw e;
                } finally {
                    req.done.countDown();
                }
            }
        }

        void closeQuietly() {
            Socket s = socket;
            if (s == null) return;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static class CommandRequest {
        final String action;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean ok;

        CommandRequest(String action) {
            this.action = action;
        }
    }
}
