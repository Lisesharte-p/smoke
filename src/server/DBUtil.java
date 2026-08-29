package server;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库连接工具：所有数据库访问统一从这里拿 Connection。
 * 连接信息支持环境变量覆盖（不同人/不同机器连不同库时不用改代码、不会互相冲突）：
 *   DB_HOST 数据库地址（默认 127.0.0.1 本地）
 *   DB_PORT 端口（默认 3306）
 *   DB_NAME 库名（默认 farm）
 *   DB_USER 用户名（默认 root）
 *   DB_PASS 密码（默认 123456）
 * 例：连队友远程库 -> set DB_HOST=192.168.12.235 && set DB_USER=newuser
 */
public class DBUtil {

    // ================== 连接配置（默认连共享库，可用环境变量覆盖） ==================
    public static final String HOST = env("DB_HOST", "192.168.70.189");
    public static final int    PORT = Integer.parseInt(env("DB_PORT", "3306"));
    public static final String DB   = env("DB_NAME", "farm");
    public static final String USER = env("DB_USER", "newuser");
    public static final String PASS = env("DB_PASS", "123456");
    // =========================================================================

    private static final String URL_TEMPLATE =
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
            + "&connectTimeout=5000&socketTimeout=10000"; // MySQL 存的是中国本地时间，别按 UTC 读（否则显示会 +8 小时）

    // 注册驱动（JDBC 4+ 会自动发现，这里显式加载更稳）
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到 MySQL 驱动，请确认 lib/mysql-connector-j-8.0.33.jar 已在 classpath", e);
        }
    }

    /** 读取环境变量，为空时用默认值 */
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 连到应用库 DB */
    public static Connection getConnection() throws SQLException {
        return getConnection(DB);
    }

    /**
     * 连到指定库；db 传 "" 表示不选库，直接连服务器。
     * 例：DBUtil.getConnection("")   -> 只连服务器，不选库
     *     DBUtil.getConnection("farm") -> 连 farm 库
     */
    public static Connection getConnection(String db) throws SQLException {
        String url = String.format(URL_TEMPLATE, HOST, PORT, db);
        return DriverManager.getConnection(url, USER, PASS);
    }

    /**
     * 建对话历史相关表（CREATE TABLE IF NOT EXISTS，幂等），服务器启动时调用一次。
     * conversation 按 username 隔离，chat_message 存每轮 user/assistant 消息。
     */
    public static void ensureChatTables() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS conversation (" +
            "  id BIGINT NOT NULL AUTO_INCREMENT," +
            "  username VARCHAR(50) NOT NULL COMMENT '所属用户（登录账号），按用户隔离'," +
            "  title VARCHAR(100) NOT NULL COMMENT '会话标题（取首个用户问题）'," +
            "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (id)," +
            "  KEY idx_user (username, updated_at)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能问答对话记录'",
            "CREATE TABLE IF NOT EXISTS chat_message (" +
            "  id BIGINT NOT NULL AUTO_INCREMENT," +
            "  conversation_id BIGINT NOT NULL COMMENT '所属会话'," +
            "  role VARCHAR(20) NOT NULL COMMENT 'user/assistant'," +
            "  content TEXT NOT NULL COMMENT '消息内容'," +
            "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (id)," +
            "  KEY idx_conv (conversation_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话消息'"
        };
        try (Connection conn = getConnection()) {
            for (String sql : ddl) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
            System.out.println("[DBUtil] 对话历史表已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 建对话历史表失败: " + e.getMessage());
        }
    }

    /**
     * 建智能问答知识库表（CREATE TABLE IF NOT EXISTS，幂等），服务器启动时调用一次。
     * knowledge_chunk 存 RAG 检索用的知识块（标题+正文+关键词），种子数据见 08_知识库种子数据.sql。
     */
    public static void ensureKnowledgeTables() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS knowledge_chunk (" +
            "  id BIGINT NOT NULL AUTO_INCREMENT," +
            "  title VARCHAR(200) NOT NULL COMMENT '知识块标题'," +
            "  category VARCHAR(50) NOT NULL COMMENT '分类：浇水灌溉/土壤湿度/温度光照/告警阈值/作物种植/病虫害防治/施肥/平台使用等'," +
            "  content TEXT NOT NULL COMMENT '知识正文（供大模型参考的完整描述）'," +
            "  keywords VARCHAR(500) DEFAULT NULL COMMENT '检索关键词，逗号分隔（辅助词法检索）'," +
            "  source VARCHAR(100) DEFAULT NULL COMMENT '来源说明（文档/整理人）'," +
            "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (id)," +
            "  UNIQUE KEY uk_title (title)," +
            "  KEY idx_category (category)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能问答知识库（RAG 检索）'"
        };
        try (Connection conn = getConnection()) {
            for (String sql : ddl) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
            System.out.println("[DBUtil] 知识库表已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 建知识库表失败: " + e.getMessage());
        }
    }

    /** 确保告警阈值表包含温湿度和亮度上下限字段（旧库自动补列）。 */
    public static void ensureThresholdColumns() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            if (!columnExists(conn, "plot_threshold", "humidity_max")) {
                st.execute("ALTER TABLE plot_threshold ADD COLUMN humidity_max DECIMAL(5,2) DEFAULT 70 COMMENT '土壤湿度上限（%）' AFTER humidity_min");
            }
            if (!columnExists(conn, "plot_threshold", "temp_min")) {
                st.execute("ALTER TABLE plot_threshold ADD COLUMN temp_min DECIMAL(5,2) DEFAULT 10 COMMENT '温度下限（℃）' AFTER humidity_max");
            }
            if (!columnExists(conn, "plot_threshold", "lux_min")) {
                st.execute("ALTER TABLE plot_threshold ADD COLUMN lux_min DECIMAL(8,2) DEFAULT 200 COMMENT '亮度下限（lx）' AFTER temp_max");
            }
            if (!columnExists(conn, "plot_threshold", "lux_max")) {
                st.execute("ALTER TABLE plot_threshold ADD COLUMN lux_max DECIMAL(8,2) DEFAULT 800 COMMENT '亮度上限（lx）' AFTER lux_min");
            }
            System.out.println("[DBUtil] 告警阈值上下限字段已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 检查告警阈值上下限字段失败: " + e.getMessage());
        }
    }

    /** 确保设备表包含摄像头接入配置字段（旧库自动补列）。 */
    public static void ensureDeviceCameraColumns() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            if (!columnExists(conn, "device", "protocol")) {
                st.execute("ALTER TABLE device ADD COLUMN protocol VARCHAR(20) DEFAULT NULL COMMENT '设备协议：tcp/rtsp/mjpeg/http' AFTER port");
            }
            if (!columnExists(conn, "device", "username")) {
                st.execute("ALTER TABLE device ADD COLUMN username VARCHAR(50) DEFAULT NULL COMMENT '设备访问用户名' AFTER protocol");
            }
            if (!columnExists(conn, "device", "password")) {
                st.execute("ALTER TABLE device ADD COLUMN password VARCHAR(100) DEFAULT NULL COMMENT '设备访问密码' AFTER username");
            }
            System.out.println("[DBUtil] 设备摄像头接入字段已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 检查设备摄像头接入字段失败: " + e.getMessage());
        }
    }

    /** 确保地块表包含巡田地图轮廓与作物样式字段（旧库自动补列）。 */
    public static void ensurePlotMapColumns() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            if (!columnExists(conn, "plot", "map_shape")) {
                st.execute("ALTER TABLE plot ADD COLUMN map_shape TEXT DEFAULT NULL COMMENT '重庆巡田地图轮廓 JSON' AFTER area");
            }
            if (!columnExists(conn, "plot", "crop_style")) {
                st.execute("ALTER TABLE plot ADD COLUMN crop_style VARCHAR(20) DEFAULT NULL COMMENT '地图作物形态' AFTER map_shape");
            }
            System.out.println("[DBUtil] 地块巡田地图字段已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 检查地块巡田地图字段失败: " + e.getMessage());
        }
    }

    /** 确保告警表包含处理日志字段（旧库自动补列）。 */
    public static void ensureAlarmHandleColumns() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            if (columnExists(conn, "alarm", "value")) {
                st.execute("ALTER TABLE alarm MODIFY COLUMN value VARCHAR(255) DEFAULT NULL COMMENT '触发时的值或综合告警描述'");
            }
            if (!columnExists(conn, "alarm", "handled_at")) {
                st.execute("ALTER TABLE alarm ADD COLUMN handled_at DATETIME DEFAULT NULL COMMENT '处理时间' AFTER status");
            }
            if (!columnExists(conn, "alarm", "handler")) {
                st.execute("ALTER TABLE alarm ADD COLUMN handler VARCHAR(50) DEFAULT NULL COMMENT '处理人' AFTER handled_at");
            }
            if (!columnExists(conn, "alarm", "handle_log")) {
                st.execute("ALTER TABLE alarm ADD COLUMN handle_log TEXT DEFAULT NULL COMMENT '处理日志' AFTER handler");
            }
            System.out.println("[DBUtil] 告警处理字段已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 检查告警处理字段失败: " + e.getMessage());
        }
    }

    /** 确保人体识别记录和识别设置表存在。 */
    public static void ensureDetectionTables() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS human_detection_record (" +
            "  id BIGINT NOT NULL AUTO_INCREMENT," +
            "  device_id VARCHAR(16) NOT NULL COMMENT '摄像头设备编号'," +
            "  plot_id VARCHAR(16) DEFAULT NULL COMMENT '摄像头所属地块'," +
            "  started_at DATETIME NOT NULL COMMENT '片段开始时间'," +
            "  ended_at DATETIME NOT NULL COMMENT '片段结束时间'," +
            "  confidence DECIMAL(6,4) DEFAULT NULL COMMENT '最高人体识别置信度'," +
            "  snapshot_path VARCHAR(500) DEFAULT NULL COMMENT '截图相对路径'," +
            "  video_path VARCHAR(500) NOT NULL COMMENT '回放视频相对路径'," +
            "  alarm_id BIGINT DEFAULT NULL COMMENT '关联告警编号'," +
            "  status VARCHAR(10) DEFAULT '未处理' COMMENT '处理状态：未处理/已处理'," +
            "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (id)," +
            "  KEY idx_device_time (device_id, started_at)," +
            "  KEY idx_alarm (alarm_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='YOLO人体识别记录'",
            "CREATE TABLE IF NOT EXISTS camera_detection_setting (" +
            "  device_id VARCHAR(16) NOT NULL," +
            "  enabled TINYINT DEFAULT 1 COMMENT '是否启用人体识别'," +
            "  confidence_threshold DECIMAL(4,2) DEFAULT 0.50 COMMENT '置信度阈值'," +
            "  cooldown_seconds INT DEFAULT 30 COMMENT '同摄像头事件冷却秒数'," +
            "  pre_seconds INT DEFAULT 5 COMMENT '检测前录像秒数'," +
            "  post_seconds INT DEFAULT 10 COMMENT '检测后录像秒数'," +
            "  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (device_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='摄像头人体识别设置'",
            "CREATE TABLE IF NOT EXISTS camera_detection_runtime (" +
            "  device_id VARCHAR(16) NOT NULL," +
            "  worker_status VARCHAR(20) DEFAULT 'unknown' COMMENT 'worker状态：running/disabled/error'," +
            "  message VARCHAR(255) DEFAULT NULL COMMENT '状态说明'," +
            "  last_seen DATETIME DEFAULT NULL COMMENT '最近心跳时间'," +
            "  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (device_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='摄像头人体识别运行状态'"
        };
        try (Connection conn = getConnection()) {
            for (String sql : ddl) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
            System.out.println("[DBUtil] 人体识别表已就绪");
        } catch (SQLException e) {
            System.out.println("[DBUtil] 建人体识别表失败: " + e.getMessage());
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }
}
