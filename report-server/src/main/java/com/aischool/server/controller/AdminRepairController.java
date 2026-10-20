package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.repair.RepairService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理端报修处理（批10）：全量列表+详情+处理（完成/不予受理） */
@RestController
@RequestMapping("/api/admin/repair")
@RequiredArgsConstructor
public class AdminRepairController {

    private final RepairService repairService;
    private final PermissionService permissionService;

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

    @Data
    public static class HandleReq {
        private String status; // DONE / REJECTED
        private String note;
    }
}
