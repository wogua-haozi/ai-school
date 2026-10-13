package com.aischool.server.service;

import com.aischool.server.entity.AuditLog;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.AuditLogMapper;
import com.aischool.server.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批56增补：24h 活跃口径改为「近 24 小时登录成功的去重人数」，查审计表（MySQL，重启不丢）——
 * 原内存 Map 口径在容器重启后清零，管理端数字会从 5 跳回 1。同时供管理端出登录名单。
 */
@Service
@RequiredArgsConstructor
public class LoginUsageService {

    public record LoginRow(String username, String realName, String role,
                           int tries, LocalDateTime lastAt) {}

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    /** 近 24h 登录成功：去重名单（按最后登录倒序），size 即活跃人数 */
    public List<LoginRow> daily() {
        List<AuditLog> logs = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUri, "/api/auth/login")
                .eq(AuditLog::getStatus, 200)
                .isNotNull(AuditLog::getUsername)
                .ge(AuditLog::getCreateTime, LocalDateTime.now().minusHours(24))
                .select(AuditLog::getUsername, AuditLog::getCreateTime));
        Map<String, LocalDateTime> lastAt = new HashMap<>();
        Map<String, Integer> tries = new HashMap<>();
        for (AuditLog l : logs) {
            tries.merge(l.getUsername(), 1, Integer::sum);
            lastAt.merge(l.getUsername(), l.getCreateTime(), (a, b) -> b.isAfter(a) ? b : a);
        }
        if (lastAt.isEmpty()) {
            return List.of();
        }
        Map<String, User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getUsername, lastAt.keySet()))
                .stream().collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));
        return lastAt.entrySet().stream()
                .map(e -> {
                    User u = users.get(e.getKey());
                    return new LoginRow(e.getKey(),
                            u != null ? u.getRealName() : e.getKey(),
                            u != null ? u.getRole() : "",
                            tries.get(e.getKey()), e.getValue());
                })
                .sorted(Comparator.comparing(LoginRow::lastAt).reversed())
                .toList();
    }
}
