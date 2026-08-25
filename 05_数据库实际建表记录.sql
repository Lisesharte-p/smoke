-- =============================================================
-- 智慧农业 - 数据库实际建表记录（as-built，从 MySQL 导出）
-- 数据库: farm    导出时间: 2026-08-22 11:41:58
-- 导出方式: SHOW CREATE DATABASE / SHOW CREATE TABLE
-- =============================================================

-- ---------- 建库 ----------
CREATE DATABASE `farm` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci */ /*!80016 DEFAULT ENCRYPTION='N' */

-- ---------- 表: user ----------
CREATE TABLE `user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '登录账号',
  `password` varchar(100) NOT NULL COMMENT '密码哈希（BCrypt），勿存明文',
  `name` varchar(50) NOT NULL COMMENT '显示名，如"张老三"',
  `role` varchar(20) NOT NULL COMMENT '角色：farmer农户 / admin农场管理员 / sysadmin系统管理员',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表'

-- ---------- 表: plot ----------
CREATE TABLE `plot` (
  `id` varchar(16) NOT NULL COMMENT '地块编号，如 P001（前端 URL 要用，必须稳定）',
  `name` varchar(50) NOT NULL COMMENT '地块名称，如"一号大棚"',
  `crop` varchar(50) NOT NULL COMMENT '种植作物，如"番茄"',
  `area` decimal(8,2) NOT NULL COMMENT '面积（亩），只存数字',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地块表'

-- ---------- 表: device ----------
CREATE TABLE `device` (
  `id` varchar(16) NOT NULL COMMENT '设备编号，如 D001',
  `plot_id` varchar(16) NOT NULL COMMENT '所属地块（关联 plot.id）',
  `name` varchar(50) NOT NULL COMMENT '设备名称，如"土壤湿度传感器-01"',
  `type` varchar(20) NOT NULL COMMENT '类型：土壤湿度传感器 / 温度传感器 / 灌溉设备 / 环境监测板',
  `ip` varchar(45) DEFAULT NULL COMMENT '设备IP地址（配置了则被采集器轮询）',
  `port` int DEFAULT NULL COMMENT '设备端口号',
  `online` tinyint DEFAULT '1' COMMENT '1在线 0离线（心跳超时置离线）',
  `running` tinyint DEFAULT '0' COMMENT '灌溉开关状态，仅灌溉设备用（0关 1开）',
  `last_heartbeat` datetime DEFAULT NULL COMMENT '最后心跳时间（在线判断依据）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_plot` (`plot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备表'

-- ---------- 表: sensor_data ----------
CREATE TABLE `sensor_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `device_id` varchar(16) NOT NULL COMMENT '上报设备（关联 device.id）',
  `metric` varchar(20) NOT NULL COMMENT '指标：temp 温度 / humidity 湿度',
  `value` decimal(8,2) NOT NULL COMMENT '数值',
  `collected_at` datetime(3) NOT NULL COMMENT '采集时间（毫秒精度）',
  PRIMARY KEY (`id`),
  KEY `idx_dev_metric_time` (`device_id`,`metric`,`collected_at`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='传感器数据表'

-- ---------- 表: plot_threshold ----------
CREATE TABLE `plot_threshold` (
  `plot_id` varchar(16) NOT NULL COMMENT '地块编号（关联 plot.id），每地块一行',
  `humidity_min` decimal(5,2) DEFAULT '40.00' COMMENT '土壤湿度下限（%）',
  `temp_max` decimal(5,2) DEFAULT '35.00' COMMENT '温度上限（℃）',
  `lux_min` decimal(8,2) DEFAULT '200.00' COMMENT '亮度下限（lx）',
  `lux_max` decimal(8,2) DEFAULT '800.00' COMMENT '亮度上限（lx）',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`plot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警阈值配置表（每地块一行）'

-- ---------- 表: alarm ----------
CREATE TABLE `alarm` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plot_id` varchar(16) NOT NULL COMMENT '关联 plot.id',
  `device_id` varchar(16) DEFAULT NULL COMMENT '触发设备（设备离线告警时填）',
  `alarm_type` varchar(30) NOT NULL COMMENT '告警类型：土壤湿度过低 / 温度过高 / 亮度过低 / 亮度过高 / 设备离线',
  `value` varchar(20) DEFAULT NULL COMMENT '触发时的值（展示型带单位），如 38%、36.5℃、-',
  `level` varchar(10) NOT NULL COMMENT '级别：严重 / 警告',
  `status` varchar(10) DEFAULT '未处理' COMMENT '状态：未处理 / 已处理',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `handler` varchar(50) DEFAULT NULL COMMENT '处理人',
  PRIMARY KEY (`id`),
  KEY `idx_plot_time` (`plot_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警表'

-- ---------- 表: control_log ----------
CREATE TABLE `control_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `device_id` varchar(16) NOT NULL COMMENT '关联 device.id',
  `action` varchar(10) NOT NULL COMMENT '动作：开启 / 关闭',
  `result` varchar(10) NOT NULL COMMENT '结果：成功 / 失败',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人，如"农户·张老三"',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='灌溉控制日志表'

