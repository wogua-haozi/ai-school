-- 批75① 走班（教学班）：行政班(t_student.class_id)不动，走班=某年级某学科的学生重排（如七年级数学·阳光701班）。
-- 任课 t_teach 保持行政班粒度不变（继续承担数据权限口径），走班表只存「教学事实」：
-- 成绩按走班名册录入/排名（数学），微光对走班学生放开，App 端走班分组。
-- 每生同一学科同刻只属一个走班（导入全量替换时应用层校验）；换班=管理端传新分班表整体替换。

CREATE TABLE IF NOT EXISTS t_walk_class (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  grade_id    BIGINT NOT NULL COMMENT '年级 id（t_grade）',
  subject_id  BIGINT NOT NULL COMMENT '学科 id（t_subject）',
  name        VARCHAR(32) NOT NULL COMMENT '走班名称，如 阳光701班',
  teacher_id  BIGINT NOT NULL COMMENT '走班授课教师（t_user）',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_grade_subject_name (grade_id, subject_id, name),
  KEY idx_teacher (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='走班（教学班）';

CREATE TABLE IF NOT EXISTS t_walk_class_student (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  walk_class_id BIGINT NOT NULL,
  student_id    BIGINT NOT NULL,
  UNIQUE KEY uk_walk_student (walk_class_id, student_id),
  KEY idx_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='走班-学生映射（每生每学科一个走班，同学科唯一由导入应用层校验）';
