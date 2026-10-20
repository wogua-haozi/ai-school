package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.entity.Notification;
import com.aischool.server.mapper.NotificationMapper;
import com.aischool.server.security.AuthUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知中心（批29）：教师/家长/领导通用（GUARD 被 JwtAuthFilter 白名单挡在前面）。
 * 行内 link 为前端路由，点击直达处理页；待办类消息由业务事件落库（见 NotificationService）。
 */
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationMapper notificationMapper;

    /** 列表：filter=unread 只看未读；返回 unread 数供角标 */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        long uid = AuthUtil.current().userId();
        List<Notification> rows = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid)
                .isNull("unread".equals(filter), Notification::getReadTime)
                .orderByDesc(Notification::getId)
                .last("LIMIT " + ((Math.max(page, 1) - 1) * Math.min(size, 100)) + ", " + Math.min(size, 100)));
        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid)
                .isNull(Notification::getReadTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows.stream().map(NotificationController::rowOf).toList());
        out.put("unread", unread);
        return ApiResponse.ok(out);
    }

    /** 未读数（角标轮询：一条 count 走 idx_user_read） */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, AuthUtil.current().userId())
                .isNull(Notification::getReadTime));
        return ApiResponse.ok(Map.of("unread", unread));
    }

    /** 单条已读（幂等） */
    @PutMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, AuthUtil.current().userId())
                .isNull(Notification::getReadTime)
                .set(Notification::getReadTime, LocalDateTime.now()));
        return ApiResponse.ok();
    }

    /** 全部已读 */
    @PutMapping("/read-all")
    public ApiResponse<Void> readAll() {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, AuthUtil.current().userId())
                .isNull(Notification::getReadTime)
                .set(Notification::getReadTime, LocalDateTime.now()));
        return ApiResponse.ok();
    }

    private static Map<String, Object> rowOf(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("type", n.getType());
        m.put("typeLabel", typeLabel(n.getType()));
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("link", n.getLink());
        m.put("read", n.getReadTime() != null);
        m.put("createTime", n.getCreateTime());
        return m;
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "OA_TODO" -> "审批待办";
            case "OA_RESULT" -> "审批结果";
            case "LEAVE_TODO" -> "请假待批";
            case "LEAVE_RESULT" -> "请假结果";
            case "REPAIR_TODO" -> "报修待办";
            case "REPAIR_RESULT" -> "报修结果";
            case "REGISTER_TODO" -> "账号待审";
            case "ALERT" -> "系统告警";
            default -> "系统消息";
        };
    }
}
