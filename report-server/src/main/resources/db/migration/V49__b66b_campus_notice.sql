-- 批66② 校园通知：scope 扩展 GRADE（指定年级教师可见），目标年级落 grade_id
-- scope 全集：ALL=全校(家长+教师) / CLASS=指定班级(家长) / STAFF=全体教师 / GRADE=指定年级教师
ALTER TABLE t_content_item
    ADD COLUMN grade_id BIGINT NULL COMMENT 'GRADE 范围的目标年级(t_grade.id)' AFTER class_id;
