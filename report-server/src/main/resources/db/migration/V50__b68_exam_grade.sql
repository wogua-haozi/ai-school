-- 批68：考试绑定年级。级长建考试曾全局生效（所有年级成绩页都出现），改为创建时必选适用年级。
-- grade_id NULL=存量考试（兼容期对所有人可见，不回改历史数据；生产回填按实际成绩分布另定）。
ALTER TABLE t_exam ADD COLUMN grade_id BIGINT NULL COMMENT '适用年级（NULL=存量全年级）' AFTER exam_date,
  ADD KEY idx_grade (grade_id);
