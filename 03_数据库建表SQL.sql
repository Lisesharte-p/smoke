-- =============================================================
-- 智慧农业 - 数据库建表脚本（DDL）
-- 版本：v1.0
-- 配套：02_数据类型与ER设计.md（概念层）、04_数据库设计说明文档.md（说明）
-- 说明：
--   1) 本脚本建库 + 建 8 张表 + 种子数据。
--   2) 库名：当前写 `farm`。若与 DBUtil.java 的 DB 常量（现在是 suiyuanhao）
--      不一致，请二选一统一（改这里，或改 server/DBUtil.java 的 DB）。
--   3) 不加物理外键，只建索引 + 应用层保证一致性（原因见说明文档）。
-- =============================================================

-- =============================================================
-- 0. 建库
-- =============================================================
CREATE DATABASE IF NOT EXISTS `farm`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `farm`;

-- =============================================================
-- 1. 用户表（登录 + 角色权限）
-- =============================================================
CREATE TABLE IF NOT EXISTS `user` (
    `id`         INT          AUTO_INCREMENT PRIMARY KEY,
    `username`   VARCHAR(50)  NOT NULL UNIQUE COMMENT '登录账号',
    `password`   VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt），勿存明文',
    `name`       VARCHAR(50)  NOT NULL COMMENT '显示名，如"张老三"',
    `role`       VARCHAR(20)  NOT NULL COMMENT '角色：farmer农户 / admin农场管理员 / sysadmin系统管理员',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- =============================================================
-- 2. 地块表
-- =============================================================
CREATE TABLE IF NOT EXISTS `plot` (
    `id`         VARCHAR(16)  PRIMARY KEY COMMENT '地块编号，如 P001（前端 URL 要用，必须稳定）',
    `name`       VARCHAR(50)  NOT NULL COMMENT '地块名称，如"一号大棚"',
    `crop`       VARCHAR(50)  NOT NULL COMMENT '种植作物，如"番茄"',
    `area`       DECIMAL(8,2) NOT NULL COMMENT '面积（亩），只存数字',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '地块表';

-- =============================================================
-- 3. 设备表
-- =============================================================
CREATE TABLE IF NOT EXISTS `device` (
    `id`             VARCHAR(16)  PRIMARY KEY COMMENT '设备编号，如 D001',
    `plot_id`        VARCHAR(16)  NOT NULL COMMENT '所属地块（关联 plot.id）',
    `name`           VARCHAR(50)  NOT NULL COMMENT '设备名称，如"土壤湿度传感器-01"',
    `type`           VARCHAR(20)  NOT NULL COMMENT '类型：土壤湿度传感器 / 温度传感器 / 灌溉设备 / 环境监测板',
    `ip`             VARCHAR(45)  DEFAULT NULL COMMENT '设备IP地址（配置了则被采集器轮询）',
    `port`           INT          DEFAULT NULL COMMENT '设备端口号',
    `online`         TINYINT      DEFAULT 1 COMMENT '1在线 0离线（心跳超时置离线）',
    `running`        TINYINT      DEFAULT 0 COMMENT '灌溉开关状态，仅灌溉设备用（0关 1开）',
    `last_heartbeat` DATETIME     DEFAULT NULL COMMENT '最后心跳时间（在线判断依据）',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY `idx_plot` (`plot_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '设备表';

-- =============================================================
-- 4. 传感器数据表（时间序列，写入最频繁）
-- =============================================================
CREATE TABLE IF NOT EXISTS `sensor_data` (
    `id`           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    `device_id`    VARCHAR(16)   NOT NULL COMMENT '上报设备（关联 device.id）',
    `metric`       VARCHAR(20)   NOT NULL COMMENT '指标：temp 温度 / humidity 湿度',
    `value`        DECIMAL(8,2)  NOT NULL COMMENT '数值',
    `collected_at` DATETIME(3)   NOT NULL COMMENT '采集时间（毫秒精度）',
    KEY `idx_dev_metric_time` (`device_id`, `metric`, `collected_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '传感器数据表';

-- =============================================================
-- 5. 告警阈值配置表（每地块一行）
-- =============================================================
CREATE TABLE IF NOT EXISTS `plot_threshold` (
    `plot_id`      VARCHAR(16)  PRIMARY KEY COMMENT '地块编号（关联 plot.id），每地块一行',
    `humidity_min` DECIMAL(5,2) DEFAULT 40 COMMENT '土壤湿度下限（%）',
    `humidity_max` DECIMAL(5,2) DEFAULT 70 COMMENT '土壤湿度上限（%）',
    `temp_min`     DECIMAL(5,2) DEFAULT 10 COMMENT '温度下限（℃）',
    `temp_max`     DECIMAL(5,2) DEFAULT 35 COMMENT '温度上限（℃）',
    `lux_min`      DECIMAL(8,2) DEFAULT 200 COMMENT '亮度下限（lx）',
    `lux_max`      DECIMAL(8,2) DEFAULT 800 COMMENT '亮度上限（lx）',
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '告警阈值配置表（每地块一行）';

-- =============================================================
-- 6. 告警表
-- =============================================================
CREATE TABLE IF NOT EXISTS `alarm` (
    `id`         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `plot_id`    VARCHAR(16)  NOT NULL COMMENT '关联 plot.id',
    `device_id`  VARCHAR(16)  DEFAULT NULL COMMENT '触发设备（设备离线告警时填）',
    `alarm_type` VARCHAR(30)  NOT NULL COMMENT '告警类型：土壤湿度过低 / 土壤湿度过高 / 温度过低 / 温度过高 / 亮度过低 / 亮度过高 / 设备离线',
    `value`      VARCHAR(20)  DEFAULT NULL COMMENT '触发时的值（展示型带单位），如 38%、36.5℃、-',
    `level`      VARCHAR(10)  NOT NULL COMMENT '级别：严重 / 警告',
    `status`     VARCHAR(10)  DEFAULT '未处理' COMMENT '状态：未处理 / 已处理',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
    `handled_at` DATETIME     DEFAULT NULL COMMENT '处理时间',
    `handler`    VARCHAR(50)  DEFAULT NULL COMMENT '处理人',
    KEY `idx_plot_time` (`plot_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '告警表';

-- =============================================================
-- 7. 灌溉控制日志表
-- =============================================================
CREATE TABLE IF NOT EXISTS `control_log` (
    `id`         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    `device_id`  VARCHAR(16) NOT NULL COMMENT '关联 device.id',
    `action`     VARCHAR(10) NOT NULL COMMENT '动作：开启 / 关闭',
    `result`     VARCHAR(10) NOT NULL COMMENT '结果：成功 / 失败',
    `operator`   VARCHAR(50) DEFAULT NULL COMMENT '操作人，如"农户·张老三"',
    `created_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    KEY `idx_device_time` (`device_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '灌溉控制日志表';

-- =============================================================
-- 8. 注册申请审核表
-- =============================================================
CREATE TABLE IF NOT EXISTS `register_request` (
    `id`            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `username`      VARCHAR(50)  NOT NULL COMMENT '申请注册的账号',
    `password`      VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt），审核通过后写入 user 表',
    `role`          VARCHAR(20)  NOT NULL COMMENT '申请角色（用户自选）：farmer农户 / admin农场管理员 / sysadmin系统管理员',
    `status`        VARCHAR(10)  NOT NULL DEFAULT '待审核' COMMENT '状态：待审核 / 已通过 / 已拒绝',
    `reject_reason` VARCHAR(200) DEFAULT NULL COMMENT '拒绝原因',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `reviewed_at`   DATETIME     DEFAULT NULL COMMENT '审核时间',
    `reviewer`      VARCHAR(50)  DEFAULT NULL COMMENT '审核人（用户名/姓名）',
    `user_id`       BIGINT       DEFAULT NULL COMMENT '审核通过后关联生成的 user.id',
    KEY `idx_status` (`status`),
    KEY `idx_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '注册申请审核表';

-- =============================================================
-- 9. 种子数据（静态参考数据；sensor_data / alarm / control_log 运行时生成，register_request 预置一条演示）
-- =============================================================

-- 9.1 阈值：每个地块一行（湿度下限/上限、温度下限/上限、亮度下限/上限）
INSERT INTO `plot_threshold` (`plot_id`, `humidity_min`, `humidity_max`, `temp_min`, `temp_max`, `lux_min`, `lux_max`) VALUES
('P001', 40, 70, 10, 35, 200, 800),
('P002', 45, 75, 10, 32, 200, 800)
ON DUPLICATE KEY UPDATE `humidity_min` = VALUES(`humidity_min`), `humidity_max` = VALUES(`humidity_max`),
                        `temp_min` = VALUES(`temp_min`), `temp_max` = VALUES(`temp_max`),
                        `lux_min` = VALUES(`lux_min`), `lux_max` = VALUES(`lux_max`);

-- 9.2 用户：三种角色各一个（密码均为占位哈希，正式用 BCrypt 生成后替换）
INSERT INTO `user` (`username`, `password`, `name`, `role`) VALUES
('farmer01',  '$2a$10$placeholder', '张老三', 'farmer'),
('admin01',   '$2a$10$placeholder', '李场长', 'admin'),
('sysadmin01','$2a$10$placeholder', '王运维', 'sysadmin');

-- 9.3 地块
INSERT INTO `plot` (`id`, `name`, `crop`, `area`) VALUES
('P001', '一号大棚', '番茄', 2.50),
('P002', '二号大棚', '黄瓜', 3.00);

-- 9.4 设备（P001 一套：湿度+温度+灌溉；P002 一套：湿度+温度）
INSERT INTO `device` (`id`, `plot_id`, `name`, `type`) VALUES
('D001', 'P001', '土壤湿度传感器-01', '土壤湿度传感器'),
('D002', 'P001', '温度传感器-01',     '温度传感器'),
('D003', 'P001', '灌溉设备-01',       '灌溉设备'),
('D004', 'P002', '土壤湿度传感器-02', '土壤湿度传感器'),
('D005', 'P002', '温度传感器-02',     '温度传感器');

-- 9.5 注册申请：一条待审核示例（演示管理员审核用）
INSERT INTO `register_request` (`username`, `password`, `role`, `status`) VALUES
('newfarmer01', '$2a$10$placeholder', 'farmer', '待审核');

-- =============================================================
-- 10. 常用查询（示例，供后端开发参考）
-- =============================================================

-- 地块列表聚合（设备数 / 在线数）：
--   SELECT p.*,
--          (SELECT COUNT(*) FROM device d WHERE d.plot_id = p.id)                    AS deviceCount,
--          (SELECT COUNT(*) FROM device d WHERE d.plot_id = p.id AND d.online = 1)  AS onlineCount
--   FROM plot p;

-- 地块最新温湿度：
--   SELECT s.metric, s.value, s.collected_at
--   FROM sensor_data s
--   JOIN device d ON d.id = s.device_id
--   WHERE d.plot_id = 'P001'
--     AND s.collected_at = (
--       SELECT MAX(s2.collected_at) FROM sensor_data s2
--       JOIN device d2 ON d2.id = s2.device_id
--       WHERE d2.plot_id = 'P001' AND s2.metric = s.metric
--     );

-- 历史趋势（过去 7 天）：
--   SELECT metric, value, collected_at
--   FROM sensor_data
--   WHERE device_id = 'D001'
--     AND metric = 'humidity'
--     AND collected_at BETWEEN DATE_SUB(NOW(), INTERVAL 7 DAY) AND NOW()
--   ORDER BY collected_at;
