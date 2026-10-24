package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.repair.RepairService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端报修处理（批10）：全量列表+详情+处理（完成/不予受理）；批65 增授权白名单自助维护 */
@RestController
@RequestMapping("/api/admin/repair")
@RequiredArgsConstructor
public class AdminRepairController {

    private final RepairService repairService;
    private final PermissionService permissionService;
    private final SysConfigMapper sysConfigMapper;
    private final UserMapper userMapper;

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        permissionService.checkAdminAccess("只有管理员可处理报修");
        return ApiResponse.ok(repairService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        permissionService.checkAdminAccess("只有管理员可处理报修");
        return ApiResponse.ok(repairService.detail(id, AuthUtil.current()));
    }

    @PutMapping("/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id, @RequestBody HandleReq req) {
        permissionService.checkAdminAccess("只有管理员可处理报修");
        repairService.handle(id, AuthUtil.current(), req.getStatus(), req.getNote());
        return ApiResponse.ok();
    }
    // 批63 三层流转上线后，日常处理走教师端 /api/repair/{id}/approve|claim|complete；
    // 本端点保留为管理员兜底（非终态可直办 DONE/REJECTED）

    /** 批67 B4：删除已撤回的报修单（照片兜底清理；在办/办结单据留痕不可删） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        permissionService.checkAdminAccess("只有管理员可处理报修");
        repairService.adminDelete(id);
        return ApiResponse.ok();
    }

    /** 批65：授权白名单（批63b 上线时是 SQL 直配，现开放管理端编辑；uid 逗号串与 repairRoleOf 同口径） */
    @GetMapping("/whitelist")
    public ApiResponse<Map<String, String>> whitelist() {
        permissionService.checkAdminAccess("只有管理员可维护报修授权");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("logisticsUids", cfgStr("repair_logistics_uids"));
        m.put("repairerUids", cfgStr("repairer_uids"));
        m.put("ccUids", cfgStr("repair_cc_uids"));
        return ApiResponse.ok(m);
    }

    /** 批65：保存白名单（校验 uid 均为在职教职工，防手滑错号） */
    @PutMapping("/whitelist")
    public ApiResponse<Map<String, String>> setWhitelist(@RequestBody WhitelistReq req) {
        permissionService.checkAdminAccess("只有管理员可维护报修授权");
        setStr("repair_logistics_uids", checkUids(req.getLogisticsUids(), "后勤审批"));
        setStr("repairer_uids", checkUids(req.getRepairerUids(), "维修工"));
        setStr("repair_cc_uids", checkUids(req.getCcUids(), "抄送人"));
        return whitelist();
    }

    /** uid 逗号串清洗：去空白剔空段；全段必须是存在的教职工 id（空串=清空该组，放行） */
    private String checkUids(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.replace("，", ",").split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            long uid;
            try {
                uid = Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new BizException(400, label + "名单含非数字编号: " + s);
            }
            if (userMapper.selectById(uid) == null) {
                throw new BizException(400, label + "名单编号 " + s + " 不是教职工账号");
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(uid);
        }
        return sb.toString();
    }

    private String cfgStr(String key) {
        SysConfig c = sysConfigMapper.selectById(key);
        return c == null || c.getCfgValue() == null ? "" : c.getCfgValue().trim();
    }

    private void setStr(String key, String value) {
        int n = sysConfigMapper.update(null, new LambdaUpdateWrapper<SysConfig>()
                .eq(SysConfig::getCfgKey, key).set(SysConfig::getCfgValue, value));
        if (n == 0) {
            SysConfig c = new SysConfig();
            c.setCfgKey(key);
            c.setCfgValue(value);
            sysConfigMapper.insert(c);
        }
    }

    @Data
    public static class HandleReq {
        private String status; // DONE / REJECTED
        private String note;
    }

    @Data
    public static class WhitelistReq {
        private String logisticsUids; // 后勤审批白名单（uid 逗号串）
        private String repairerUids;  // 维修工白名单
        private String ccUids;        // 完成抄送人
    }
}
