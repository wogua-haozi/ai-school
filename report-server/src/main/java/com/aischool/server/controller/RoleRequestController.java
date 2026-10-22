package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.RoleApprovalService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理员/领导账号双人审批（批2-5）。
 * 管理员在网页端「账号审批」页签处理；领导在 App 端「待我审批」处理（/l/approvals）。
 * 通过/拒绝动作经 AuditFilter 自动留痕（uri=/api/role-request/*）。
 */
@RestController
@RequestMapping("/api/role-request")
@RequiredArgsConstructor
public class RoleRequestController {

    private final RoleApprovalService approvalService;

    private void checkApprover() {
        // 批64 领导层（书记/领导/主任）均可审批
        String role = AuthUtil.current().role();
        if (!"ADMIN".equals(role) && !"LEADER".equals(role) && !"SECRETARY".equals(role)
                && !"DIRECTOR".equals(role)) {
            throw new BizException(403, "仅管理员或领导层（书记/领导/主任）可审批");
        }
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        checkApprover();
        return ApiResponse.ok(approvalService.pendingList());
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count() {
        checkApprover();
        return ApiResponse.ok(Map.of("pending", approvalService.pendingCount()));
    }

    @PutMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable Long id) {
        checkApprover();
        return ApiResponse.ok(approvalService.approve(id, AuthUtil.current().userId()));
    }

    @Data
    public static class RejectReq {
        private String note;
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable Long id, @RequestBody(required = false) RejectReq req) {
        checkApprover();
        return ApiResponse.ok(approvalService.reject(id, AuthUtil.current().userId(),
                req == null ? null : req.getNote()));
    }
}
