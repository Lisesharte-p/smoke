package server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
    public static final String HOST = env("DB_HOST", "192.168.70.188");
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
}
