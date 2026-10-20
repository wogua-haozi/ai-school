-- 批63：报修三层流转（后勤中心审批 → 水电维修工抢单 → 完成抄送）+ 成绩等级阈值配置
-- 状态机扩展：PENDING → APPROVED（后勤通过）→ CLAIMED（维修工接单）→ DONE；
--             PENDING/APPROVED → REJECTED（后勤拒绝）/ CANCELED（报修人撤回）

ALTER TABLE t_repair
  ADD COLUMN approver_id BIGINT DEFAULT NULL COMMENT '审批人（后勤中心）' AFTER handle_time,
  ADD COLUMN approve_time DATETIME DEFAULT NULL COMMENT '审批时间' AFTER approver_id,
  ADD COLUMN approve_note VARCHAR(300) DEFAULT NULL COMMENT '审批意见' AFTER approve_time,
  ADD COLUMN claimer_id BIGINT DEFAULT NULL COMMENT '接单维修工' AFTER approve_note,
  ADD COLUMN claim_time DATETIME DEFAULT NULL COMMENT '接单时间' AFTER claimer_id;

ALTER TABLE t_repair MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
  COMMENT 'PENDING 待审批 / APPROVED 已通过待接单 / CLAIMED 维修中 / DONE 已完成 / REJECTED 已拒绝 / CANCELED 已撤回';

-- 成绩等级折算线（百分比，不入库现算）：A≥85% B≥70% C≥60% 其余 D；repair_cc_uids=完成后站内信抄送用户 id（逗号分隔，生产填 4 后勤+冯荣森+杜伟滨）
INSERT INTO t_sys_config (cfg_key, cfg_value) VALUES
  ('score_grade_a', '85'), ('score_grade_b', '70'), ('score_grade_c', '60'), ('repair_cc_uids', '')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;
