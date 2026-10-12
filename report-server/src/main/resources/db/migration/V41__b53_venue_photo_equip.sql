-- 批53③ 场地板块重构：照片/设备字段 + 按参考图重录 7 个场地。
-- 旧场地整体替换；历史单据 detail 存 venueName 快照，展示不受影响（上线前确认生产场地单量为 0）。
ALTER TABLE t_venue
  ADD COLUMN photo VARCHAR(255) DEFAULT NULL COMMENT '照片 MinIO 对象名（管理端上传）',
  ADD COLUMN equip VARCHAR(255) DEFAULT NULL COMMENT '设备清单（中文顿号分隔）';

DELETE FROM t_venue;

INSERT INTO t_venue (name, location, capacity, status, equip) VALUES
('未来教育学术中心', '弘毅楼五楼', 80,  1, NULL),
('学术报告厅',       '弘毅楼一楼', 250, 1, NULL),
('行政会议室',       '弘毅楼八楼', 8,   1, NULL),
('党员活动室',       '致远楼一楼', 55,  1, NULL),
('体育馆',           '行健楼二楼', 200, 1, NULL),
('书吧',             '',           25,  1, NULL),
('咖啡吧',           '',           30,  1, '饮水机');
