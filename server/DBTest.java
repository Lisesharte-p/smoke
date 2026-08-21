package server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库连接测试：验证 DBUtil 能否连通 MySQL。
 * 运行后打印：服务器版本、所有数据库、应用库里的表（若已建）。
 */
public class DBTest {

    public static void main(String[] args) throws Exception {
        // ===== 第一步：连服务器，看有哪些库 =====
        try (Connection conn = DBUtil.getConnection("");
             Statement st = conn.createStatement()) {
            System.out.println("连接成功!");
            System.out.println("服务器版本: " + conn.getMetaData().getDatabaseProductVersion());

            System.out.println("\n服务器上的数据库:");
            try (ResultSet rs = st.executeQuery("SHOW DATABASES")) {
                while (rs.next()) System.out.println("  - " + rs.getString(1));
            }
        }

        // ===== 第二步：尝试连应用库，列出表 =====
        try (Connection conn = DBUtil.getConnection();
             Statement st = conn.createStatement()) {
            System.out.println("\n应用库 [" + DBUtil.DB + "] 连接成功");
            try (ResultSet rs = st.executeQuery("SHOW TABLES")) {
                System.out.println("库 [" + DBUtil.DB + "] 中的表:");
                while (rs.next()) System.out.println("  - " + rs.getString(1));
            }
        } catch (SQLException e) {
            System.out.println("\n应用库 [" + DBUtil.DB + "] 连接失败（可能还没建库）:");
            System.out.println("  " + e.getMessage());
        }
    }
}
