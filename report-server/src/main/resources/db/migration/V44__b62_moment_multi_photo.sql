-- 批62 微光多图：photo_url 由单对象名改为逗号分隔多对象名（单图历史数据天然兼容）
ALTER TABLE t_moment MODIFY COLUMN photo_url VARCHAR(510) DEFAULT '';
