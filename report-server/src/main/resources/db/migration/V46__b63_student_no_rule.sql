-- 批63④ 学号改期末考号规则（10 位）：年份2位(27=2026-2027学年)+年级1位+校码363+班号2位+座位号2位
-- 与期末考试编排一致，方便成绩统计上传；后四位(班号+座位号)承袭原学号不变。
-- 年级映射：一~六年级=1~6，初一/七年级=7，初二/八年级=8，初三/九年级=9。
-- 前缀同年级一致 + 原学号全局唯一 ⇒ 新学号全局唯一；未映射年级名会置 NULL 触发 NOT NULL 报错拦下迁移（年级命名超出预期时人工介入）。
-- 纯 DML 单事务：撞唯一键即整体回滚。上线前先在库执行对撞预检（见 docs/上线预演.md 批63 节）。
UPDATE t_student s
JOIN t_class c ON s.class_id = c.id
JOIN t_grade g ON c.grade_id = g.id
SET s.student_no = CONCAT('27',
  CASE
    WHEN g.name LIKE '初%' THEN CASE SUBSTRING(g.name, 2, 1)
      WHEN '一' THEN '7' WHEN '二' THEN '8' WHEN '三' THEN '9' END
    WHEN g.name LIKE '七%' THEN '7'
    WHEN g.name LIKE '八%' THEN '8'
    WHEN g.name LIKE '九%' THEN '9'
    ELSE CASE SUBSTRING(g.name, 1, 1)
      WHEN '一' THEN '1' WHEN '二' THEN '2' WHEN '三' THEN '3'
      WHEN '四' THEN '4' WHEN '五' THEN '5' WHEN '六' THEN '6' END
  END,
  '363', RIGHT(s.student_no, 4));
