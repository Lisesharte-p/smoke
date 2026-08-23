-- =============================================================
-- 智慧农业 - 板子环境数据采集（在 03/05 号脚本基础上执行）
-- 说明：
--   1) 新增一台环境监测板设备 D006（物理板子 192.168.70.167:8888），
--      挂在一号大棚 P001 下，作为实时温湿度/光照的数据源。
--      ip/port 入库后，BoardCollector 会遍历所有配置了 ip/port 的设备动态采集。
--   2) 板子通过 TCP 收到 "query\n" 后回：DATA/HEARTBEAT TEMP:xx HUMI:xx LUX:xx，
--      后端 BoardCollector 周期读取并把 temp/humidity/lux 写入 sensor_data（device_id=D006）。
--   3) 可重复执行（INSERT ... ON DUPLICATE KEY UPDATE 幂等）。
-- =============================================================

USE `farm`;

INSERT INTO `device` (`id`, `plot_id`, `name`, `type`, `ip`, `port`, `online`, `running`, `last_heartbeat`, `created_at`)
VALUES ('D006', 'P001', '环境监测板-01', '环境监测板', '192.168.70.167', 8888, 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `type` = VALUES(`type`),
  `ip` = VALUES(`ip`),
  `port` = VALUES(`port`),
  `last_heartbeat` = NOW();
