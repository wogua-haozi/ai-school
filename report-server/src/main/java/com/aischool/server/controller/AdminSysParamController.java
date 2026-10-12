package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.service.auth.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端「系统参数」（批35 学校自治）：t_sys_config 常用参数表单化。
 * 当前覆盖学生请假分级阈值（leave_level1_days / leave_level2_days / leave_max_days，
 * 消费方 StudentLeaveService 动态读库，改完即时生效）；
 * 批51 生活老师登记时段窗（leave_dorm_windows，"HH:mm-HH:mm" 逗号分隔，未配置=默认 12:00-14:15,21:30-07:30，清空=不限）；
 * 批39⑥ 文明班口径切换日（civility_eval_from，yyyy-MM-dd，空=未切换仍按旧打分口径排名）。
 */
@RestController
@RequestMapping("/api/admin/sys-param")
@RequiredArgsConstructor
public class AdminSysParamController {

    private final SysConfigMapper sysConfigMapper;
    private final PermissionService permissionService;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作系统管理");
    }

    private int cfgInt(String key, int dft) {
        SysConfig c = sysConfigMapper.selectById(key);
        if (c == null || c.getCfgValue() == null) return dft;
        try {
            return Integer.parseInt(c.getCfgValue().trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private void set(String key, int value) {
        int n = sysConfigMapper.update(null, new LambdaUpdateWrapper<SysConfig>()
                .eq(SysConfig::getCfgKey, key).set(SysConfig::getCfgValue, String.valueOf(value)));
        if (n == 0) {
            SysConfig c = new SysConfig();
            c.setCfgKey(key);
            c.setCfgValue(String.valueOf(value));
            sysConfigMapper.insert(c);
        }
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

    /** 未配置返回默认、显式空串保持空（区分「未配置=默认时段」与「清空=不限」） */
    private String cfgStrOr(String key, String dft) {
        SysConfig c = sysConfigMapper.selectById(key);
        return c == null || c.getCfgValue() == null ? dft : c.getCfgValue();
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        checkAdmin();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("leaveLevel1Days", cfgInt("leave_level1_days", 3));
        m.put("leaveLevel2Days", cfgInt("leave_level2_days", 7));
        m.put("leaveMaxDays", cfgInt("leave_max_days", 30));
        m.put("leaveDormWindows", cfgStrOr("leave_dorm_windows", "12:00-14:15,21:30-07:30"));
        m.put("civilityEvalFrom", cfgStr("civility_eval_from"));
        return ApiResponse.ok(m);
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> set(@Validated @RequestBody ParamReq req) {
        checkAdmin();
        int l1 = req.getLeaveLevel1Days() == null ? 3 : req.getLeaveLevel1Days();
        int l2 = req.getLeaveLevel2Days() == null ? 7 : req.getLeaveLevel2Days();
        int max = req.getLeaveMaxDays() == null ? 30 : req.getLeaveMaxDays();
        if (l1 < 1 || l2 <= l1 || max < l2 || max > 365) {
            throw new BizException(400, "阈值须满足 1 ≤ 一级审批天数 < 二级审批天数 ≤ 上限天数 ≤ 365");
        }
        String dorm = req.getLeaveDormWindows() == null ? "" : req.getLeaveDormWindows().trim();
        if (!dorm.isBlank()) {
            for (String seg : dorm.split(",")) {
                String[] p = seg.trim().split("-");
                boolean ok = p.length == 2;
                if (ok) {
                    try {
                        java.time.LocalTime.parse(p[0].trim());
                        java.time.LocalTime.parse(p[1].trim());
                    } catch (DateTimeParseException e) {
                        ok = false;
                    }
                }
                if (!ok) {
                    throw new BizException(400, "生活老师时段格式须为 HH:mm-HH:mm（多段英文逗号分隔，如 12:00-14:15,21:30-07:30；清空=不限时段）");
                }
            }
        }
        LocalDate from = null;
        if (req.getCivilityEvalFrom() != null && !req.getCivilityEvalFrom().isBlank()) {
            try {
                from = LocalDate.parse(req.getCivilityEvalFrom().trim());
            } catch (DateTimeParseException e) {
                throw new BizException(400, "文明班切换日格式须为 yyyy-MM-dd");
            }
            if (from.isAfter(LocalDate.now())) {
                throw new BizException(400, "文明班切换日不能晚于今天");
            }
        }
        set("leave_level1_days", l1);
        set("leave_level2_days", l2);
        set("leave_max_days", max);
        setStr("leave_dorm_windows", dorm);
        setStr("civility_eval_from", from == null ? "" : from.toString());
        return get();
    }

    @Data
    public static class ParamReq {
        private Integer leaveLevel1Days;
        private Integer leaveLevel2Days;
        private Integer leaveMaxDays;
        private String leaveDormWindows;
        private String civilityEvalFrom;
    }
}
