-- 批57①：家长注册改班级码（A 方案）
-- 班级码：class_id 携带、student_id 为空，全班共用不消费（status 恒 UNUSED，重发新作废）
-- 学生级码保留（转学生单发）：student_id 携带，注册消费置 USED
ALTER TABLE t_invite_code
  MODIFY COLUMN student_id BIGINT NULL COMMENT '学生ID（学生级码携带；班级码为空）',
  ADD COLUMN class_id BIGINT DEFAULT NULL COMMENT '班级ID（班级码携带）' AFTER student_id,
  ADD KEY idx_class (class_id);
