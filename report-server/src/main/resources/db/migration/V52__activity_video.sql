-- V52（2026-10-28 批72d）：扬长课程视频——课程内容以视频承载（设计本意，此前未实现）。
-- 甲方拍板不做在线播放（服务器压力），存外部平台链接（腾讯视频/B站等），前端点击跳转观看；≤500 字，存量 NULL=未填。
ALTER TABLE t_activity ADD COLUMN video_url VARCHAR(500) NULL COMMENT '课程视频外链（http/https，点击跳转）';
