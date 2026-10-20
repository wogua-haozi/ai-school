-- 批63c 等级改上传制：校方/级长上传等级（A+ A A- B+ B B- C+ C C-，校验 A~D 可带 +/- 留 D 档余量），
-- 系统不再现算（ScoreLevelService/阈值 score_grade_* 保留为自治窗口，不驱动显示）。
-- t_score.grade 存单科等级；总分等级=subject_id NULL 且 score NULL 的行（recompute 已按 score 非空过滤，天然不进排名）。
ALTER TABLE t_score
  ADD COLUMN grade VARCHAR(4) DEFAULT NULL COMMENT '校方上传等级（A~D 可带 +/-，如 A+；批63c）' AFTER score,
  MODIFY COLUMN subject_id BIGINT DEFAULT NULL COMMENT '科目 id；NULL=总分等级行（批63c）',
  MODIFY COLUMN score DECIMAL(10,2) DEFAULT NULL COMMENT '分数；总分等级行为 NULL';
