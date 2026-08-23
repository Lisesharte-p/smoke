-- =============================================================
-- 智慧农业 - 补充种子数据（在 03_数据库建表SQL.sql 基础上执行）
-- 说明：
--   1) 03 号脚本只建了静态表 + 少量参考数据，缺少传感器历史/告警/控制日志，
--      且 user 密码是占位哈希，登录无法校验。本脚本补齐这些，让前端有真实数据可看。
--   2) 登录密码统一改为 SHA-256("123456")，即演示账号密码都是 123456。
--   3) 在 03 号脚本之后执行，可重复执行（sensor_data/alarm/control_log 会追加）。
-- =============================================================

USE `farm`;

-- =============================================================
-- 1. 修正用户密码为 SHA-256("123456") 的真实哈希
-- =============================================================
UPDATE `user` SET `password` = '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92';

-- =============================================================
-- 2. 传感器历史数据（近 7 天，每天 4 个采样点，4 台传感器各一份）
--    让「数据总览/数据监测/历史趋势」有真实曲线与最新值
-- =============================================================
INSERT INTO `sensor_data` (`device_id`, `metric`, `value`, `collected_at`)
SELECT d.device_id,
       d.metric,
       ROUND(d.base + (RAND() * 6 - 3), 1),
       DATE_SUB(NOW(), INTERVAL (n.dy * 24 + h.hr) HOUR)
FROM
  (SELECT 0 dy UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
   UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) n
CROSS JOIN
  (SELECT 0 hr UNION ALL SELECT 6 UNION ALL SELECT 12 UNION ALL SELECT 18) h
CROSS JOIN
  (SELECT 'D001' device_id, 'humidity' metric, 62 base
   UNION ALL SELECT 'D002', 'temp',     26
   UNION ALL SELECT 'D004', 'humidity', 55
   UNION ALL SELECT 'D005', 'temp',     24) d;

-- =============================================================
-- 3. 告警样例（让告警管理页有数据）
-- =============================================================
INSERT INTO `alarm` (`plot_id`, `device_id`, `alarm_type`, `value`, `level`, `status`, `created_at`) VALUES
('P001', 'D001', '土壤湿度过低', '38%',    '警告', '未处理', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
('P001', 'D002', '设备离线',     '-',      '严重', '未处理', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
('P002', 'D005', '温度过高',     '36.5℃', '严重', '已处理', DATE_SUB(NOW(), INTERVAL 2 DAY));

-- =============================================================
-- 4. 控制日志样例（让设备控制页的日志表不空）
-- =============================================================
INSERT INTO `control_log` (`device_id`, `action`, `result`, `operator`, `created_at`) VALUES
('D003', '开启', '成功', '农户·张老三', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
('D003', '关闭', '成功', '农户·张老三', DATE_SUB(NOW(), INTERVAL 1 HOUR));
