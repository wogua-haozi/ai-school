-- 批56② 场地第八家：七楼会议室（原软件之外新增，共 8 个场地）。
-- V41 已上生产不可改，走本增量；位置/容量/设备留空——校方在管理端「总务后勤→场地管理」可自助补全；
-- 照片（含既有 7 场地实拍）走 POST /api/admin/venue/{id}/photo 上传（参考/场地预约图片/）。
INSERT INTO t_venue (name, location, capacity, status, equip)
VALUES ('七楼会议室', '', NULL, 1, NULL);
