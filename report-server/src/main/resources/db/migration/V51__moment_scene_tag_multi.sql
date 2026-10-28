-- V51（2026-10-28 批71）：微光时刻场景标签多选——scene_tag 扩列存 / 分隔多标签（如「课堂专注/助人为乐」）
-- 存量单标签无分隔符，读取端 split('/') 天然兼容；上限 5 个×32 字+4 分隔=164，列取 200。
ALTER TABLE t_moment MODIFY COLUMN scene_tag VARCHAR(200) NOT NULL
  COMMENT '场景标签（多选，/ 分隔；EVAL_SYNC 联动生成仍为单指标名）';
