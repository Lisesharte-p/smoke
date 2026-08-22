package server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据库连接工具：所有数据库访问统一从这里拿 Connection。
 * 改配置只需要改下面这几个常量。
 */
public class DBUtil {

    // ================== 连接配置（按实际改） ==================
    public static final String HOST = "192.168.12.235";
    public static final int PORT = 3306;
    public static final String DB = "farm"; // 应用库名（数据表已建好）
    public static final String USER = "newuser";
    public static final String PASS = "123456";
    // =======================================================

    private static final String URL_TEMPLATE =
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
            + "&connectTimeout=5000&socketTimeout=10000"; // 连不上/读不到时不至于让 HTTP 请求无限挂起

    // 注册驱动（JDBC 4+ 会自动发现，这里显式加载更稳）
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到 MySQL 驱动，请确认 lib/mysql-connector-j-8.0.33.jar 已在 classpath", e);
        }
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
