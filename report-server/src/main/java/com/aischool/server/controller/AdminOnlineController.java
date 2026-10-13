package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.service.LoginUsageService;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.OnlineTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实时在线统计（仅管理员）：管理端顶部统计条轮询 */
@RestController
@RequestMapping("/api/admin/online")
@RequiredArgsConstructor
public class AdminOnlineController {

    private final OnlineTracker onlineTracker;
    private final LoginUsageService loginUsage;
    private final PermissionService permissionService;

    @GetMapping
    public ApiResponse<Map<String, Object>> stats() {
        permissionService.checkAdminAccess("只有管理员可查看在线统计");
        var online = onlineTracker.online().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("userId", u.userId());
            m.put("username", u.username());
            m.put("realName", u.realName());
            m.put("lastActiveMs", u.lastActiveMs());
            return m;
        }).toList();
        // 批56增补：24h 活跃=审计表口径（近 24h 登录成功去重，重启不丢）+ 登录名单（管理端可见谁在用）
        List<LoginUsageService.LoginRow> daily = loginUsage.daily();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("onlineCount", online.size());
        data.put("onlineUsers", online);
        data.put("dailyCount", daily.size());
        data.put("dailyUsers", daily);
        data.put("windowMinutes", OnlineTracker.ONLINE_WINDOW_MIN);
        return ApiResponse.ok(data);
    }
}
