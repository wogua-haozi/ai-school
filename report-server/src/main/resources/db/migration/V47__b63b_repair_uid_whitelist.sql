-- 批63b 报修 uid 白名单：9 位报修人员均为已有教职工号（手机号注册），不另建新号——
-- 报修权限在角色之外按 uid 授权（同 oa_purchase_cc 模式），原有教师/主任角色与功能不受影响。
-- repair_logistics_uids=竺柱初347/谭泽明400/刘青琳159/黄志滔490；repairer_uids=李文海273/李新辉274/邹艺文421/黄庆伟488/李志强270
INSERT INTO t_sys_config (cfg_key, cfg_value) VALUES
  ('repair_logistics_uids', '347,400,159,490'), ('repairer_uids', '273,274,421,488,270')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;

-- 抄送名单修正为 6 个老号（4 后勤白名单 + 冯荣森141 + 杜伟滨539）
UPDATE t_sys_config SET cfg_value = '141,159,347,400,490,539' WHERE cfg_key = 'repair_cc_uids';

-- 清理批63 误建的 9 个拼音重号（用户名全局唯一；本地库无此用户名，零影响）
DELETE FROM t_user WHERE username IN ('zhuzhuchu','tanzeming','liuqinglin','huangzhitao',
  'liwenhai','lixinhui','zouyiwen','huangqingwei','lizhiqiang');
