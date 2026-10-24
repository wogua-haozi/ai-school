package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.ConductAccount;
import com.aischool.server.entity.ConductLog;
import com.aischool.server.entity.ConductRule;
import com.aischool.server.mapper.ConductAccountMapper;
import com.aischool.server.mapper.ConductLogMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.conduct.ConductLedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操行分账本（批3）：教师端查询/手动调整；权限口径同评价（checkStudentAccess）。
 */
@RestController
@RequestMapping("/api/conduct")
@RequiredArgsConstructor
public class ConductController {

    private final ConductLedgerService conductLedger;
    private final ConductAccountMapper conductAccountMapper;
    private final ConductLogMapper conductLogMapper;
    private final DataScopeService dataScope;

    /** 学生操行分概览：余额+等级+本学期流水（未初始化=基础分起点、无流水） */
    @GetMapping("/student/{studentId}")
    public ApiResponse<Map<String, Object>> student(@PathVariable Long studentId) {
        dataScope.checkStudentAccess(AuthUtil.current(), studentId);
        ConductRule rule = conductLedger.getRule();
        Long termId = conductLedger.resolveTermId(LocalDate.now());
        ConductAccount account = conductAccountMapper.selectOne(new LambdaQueryWrapper<ConductAccount>()
                .eq(ConductAccount::getStudentId, studentId)
                .eq(ConductAccount::getTermId, termId)
                .last("LIMIT 1"));
        BigDecimal balance = account != null ? account.getBalance() : rule.getBaseScore();
        long me = AuthUtil.current().userId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balance", balance);
        m.put("grade", conductLedger.gradeOf(balance, rule));
        m.put("initialized", account != null);
        m.put("rule", ruleOf(rule));
        // 批67：流水附 mine 标记（手动类撤销入口=录入人本人或管理员）
        m.put("logs", conductLedger.logsOf(studentId, termId).stream().map(l -> {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.getId());
            lm.put("sourceType", l.getSourceType());
            lm.put("delta", l.getDelta());
            lm.put("reason", l.getReason());
            lm.put("operatorId", l.getOperatorId());
            lm.put("createTime", l.getCreateTime() == null ? null : l.getCreateTime().toString());
            lm.put("mine", me == l.getOperatorId());
            return lm;
        }).toList());
        return ApiResponse.ok(m);
    }

    /** 手动调整（奖/惩）：delta≠0、理由必填 */
    @PostMapping("/adjust")
    public ApiResponse<Void> adjust(@Validated @RequestBody AdjustReq req) {
        dataScope.checkStudentAccess(AuthUtil.current(), req.getStudentId());
        if (req.getDelta() == null || req.getDelta().compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException(400, "调整值不能为 0");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException(400, "调整理由不能为空");
        }
        conductLedger.apply(req.getStudentId(), LocalDate.now(), ConductLog.SRC_MANUAL, null,
                req.getDelta(), req.getReason().trim(), AuthUtil.current().userId());
        return ApiResponse.ok();
    }

    /** 撤销手动调整（批67 A1）：仅「手动」流水；录入人或 ADMIN；删流水并余额反向回补 */
    @DeleteMapping("/adjust/{logId}")
    public ApiResponse<Void> revokeAdjust(@PathVariable Long logId) {
        UserPrincipal user = AuthUtil.current();
        ConductLog log = conductLogMapper.selectById(logId);
        if (log == null) {
            throw new BizException(404, "流水不存在");
        }
        if (!ConductLog.SRC_MANUAL.equals(log.getSourceType())) {
            throw new BizException(400, "仅手动调整可撤销（评价类流水请回评价记录处理）");
        }
        if (!"ADMIN".equals(user.role()) && !user.userId().equals(log.getOperatorId())) {
            throw new BizException(403, "仅录入人或管理员可撤销");
        }
        dataScope.checkStudentAccess(user, log.getStudentId());
        conductLedger.revokeManual(log);
        return ApiResponse.ok();
    }

    /** 等级线回显（教师端展示口径）；家长仅经 /api/parent 钱包内嵌获取 */
    @GetMapping("/rule")
    public ApiResponse<Map<String, Object>> rule() {
        if ("PARENT".equals(AuthUtil.current().role())) {
            throw new BizException(403, "仅教师可访问");
        }
        return ApiResponse.ok(ruleOf(conductLedger.getRule()));
    }

    private Map<String, Object> ruleOf(ConductRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseScore", rule.getBaseScore());
        m.put("gradeAMin", rule.getGradeAMin());
        m.put("gradeBMin", rule.getGradeBMin());
        m.put("gradeCMin", rule.getGradeCMin());
        return m;
    }

    @Data
    public static class AdjustReq {
        @NotNull(message = "studentId 不能为空")
        private Long studentId;
        @NotNull(message = "delta 不能为空")
        private BigDecimal delta;
        private String reason;
    }
}
