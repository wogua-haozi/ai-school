package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Feedback;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.FeedbackMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理端：意见反馈处理（批8.5） */
@RestController
@RequestMapping("/api/admin/feedback")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作系统管理");
    }

    /** 待处理数（管理端徽标用，轻量） */
    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count() {
        checkAdmin();
        return ApiResponse.ok(Map.of("pending",
                feedbackMapper.selectCount(new LambdaQueryWrapper<Feedback>().eq(Feedback::getStatus, 0))));
    }

    /** 反馈列表（status 可筛；附提交人姓名/账号） */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        checkAdmin();
        var p = feedbackMapper.selectPage(Page.of(page, Math.min(size, 100)),
                new LambdaQueryWrapper<Feedback>()
                        .eq(status != null, Feedback::getStatus, status)
                        .orderByAsc(Feedback::getStatus)   // 待处理在前
                        .orderByDesc(Feedback::getId));
        List<Long> userIds = p.getRecords().stream().map(Feedback::getUserId).distinct().toList();
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        List<Map<String, Object>> records = p.getRecords().stream().map(f -> {
            User u = users.get(f.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("content", f.getContent());
            m.put("contact", f.getContact());
            m.put("role", f.getRole());
            m.put("submitterName", u == null ? "(已删除)" : u.getRealName());
            m.put("submitterAccount", u == null ? null : u.getUsername());
            m.put("status", f.getStatus());
            m.put("handleNote", f.getHandleNote());
            m.put("createTime", f.getCreateTime() == null ? null : f.getCreateTime().toString());
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("records", records);
        return ApiResponse.ok(data);
    }

    @Data
    public static class HandleReq {
        @NotNull(message = "status 不能为空")
        private Integer status; // 0=退回待处理 1=已处理
        @Size(max = 1000, message = "处理说明最多 1000 字")
        private String handleNote;
    }

    /** 处理反馈：标记已处理（附说明，对提交人可见）/退回待处理 */
    @PutMapping("/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id, @Validated @RequestBody HandleReq req) {
        checkAdmin();
        if (req.getStatus() != 0 && req.getStatus() != 1) {
            throw new BizException(400, "status 必须是 0/1");
        }
        if (req.getStatus() == 1 && (req.getHandleNote() == null || req.getHandleNote().isBlank())) {
            throw new BizException(400, "处理说明不能为空");
        }
        if (feedbackMapper.selectById(id) == null) {
            throw new BizException(404, "反馈不存在");
        }
        feedbackMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Feedback>()
                .eq(Feedback::getId, id)
                .set(Feedback::getStatus, req.getStatus())
                .set(Feedback::getHandleNote,
                        req.getHandleNote() == null || req.getHandleNote().isBlank() ? null : req.getHandleNote().trim())
                .set(Feedback::getHandlerId, req.getStatus() == 1 ? AuthUtil.current().userId() : null));
        return ApiResponse.ok();
    }

    /** 删除反馈（批67 补口：管理员兜底，任意状态可删） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        checkAdmin();
        if (feedbackMapper.selectById(id) == null) {
            throw new BizException(404, "反馈不存在");
        }
        feedbackMapper.deleteById(id);
        return ApiResponse.ok();
    }
}
