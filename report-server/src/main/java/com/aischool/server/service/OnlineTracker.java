package com.aischool.server.service;

import com.aischool.server.security.UserPrincipal;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线统计：认证过滤器对每个已登录请求 touch 一次（内存态，单实例足够，重启即清零——只关心当前活跃）。
 * 「当前在线」= 最近 {@value #ONLINE_WINDOW_MIN} 分钟内有请求；过期条目定时清理。
 * （批56增补起「24h 活跃」改走 {@link LoginUsageService} 审计表口径，不再用本表。）
 */
@Component
public class OnlineTracker {

    /** 在线判定窗口（分钟） */
    public static final int ONLINE_WINDOW_MIN = 5;

    public record ActiveUser(Long userId, String username, String realName, long lastActiveMs) {}

    private final Map<Long, ActiveUser> active = new ConcurrentHashMap<>();

    public void touch(UserPrincipal user) {
        active.put(user.userId(), new ActiveUser(
                user.userId(), user.username(), user.realName(), System.currentTimeMillis()));
    }

    /** 当前在线：最近 N 分钟内有请求的用户（按最后活跃倒序） */
    public List<ActiveUser> online() {
        long cut = System.currentTimeMillis() - ONLINE_WINDOW_MIN * 60_000L;
        return active.values().stream()
                .filter(u -> u.lastActiveMs() >= cut)
                .sorted(Comparator.comparingLong(ActiveUser::lastActiveMs).reversed())
                .toList();
    }

    /** 每小时清理：移除超过 24 小时无活动的条目 */
    @Scheduled(fixedRate = 3600_000, initialDelay = 3600_000)
    public void cleanup() {
        long cut = System.currentTimeMillis() - 24 * 3600_000L;
        active.values().removeIf(u -> u.lastActiveMs() < cut);
    }
}
