package com.aischool.server.service.auth;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.UserPermission;
import com.aischool.server.mapper.UserPermissionMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.security.UserPrincipal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限点（角色之外按人叠加授权，V10 t_user_permission）。
 * 批1 码表：ADMIN_ACCESS = 管理员级配置权（授予领导即开放管理端操作）。
 * 批49 码表：SCORE_ALL = 全校成绩查看权（教学处主任/副主任按人授权；只读，录入权限另由 ScoreService 收紧）。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    public static final String PERM_ADMIN_ACCESS = "ADMIN_ACCESS";
    public static final String PERM_SCORE_ALL = "SCORE_ALL";

    private final UserPermissionMapper userPermissionMapper;

    /** 是否具有管理端配置权：ADMIN 角色，或被授予 ADMIN_ACCESS 权限点（如需管系统的领导） */
    public boolean hasAdminAccess(UserPrincipal user) {
        if ("ADMIN".equals(user.role())) {
            return true;
        }
        return userPermissionMapper.selectCount(new LambdaQueryWrapper<UserPermission>()
                .eq(UserPermission::getUserId, user.userId())
                .eq(UserPermission::getPermCode, PERM_ADMIN_ACCESS)) > 0;
    }

    /** 管理端操作统一入口（替代各控制器手写的 checkAdmin 字面量判断） */
    public void checkAdminAccess() {
        checkAdminAccess("只有管理员可操作系统管理");
    }

    public void checkAdminAccess(String message) {
        if (!hasAdminAccess(AuthUtil.current())) {
            throw new BizException(403, message);
        }
    }

    /** 全校成绩查看权（批49）：教学处主任/副主任等按人授权，不占角色以免污染其他审批流 */
    public boolean hasScoreAll(UserPrincipal user) {
        return userPermissionMapper.selectCount(new LambdaQueryWrapper<UserPermission>()
                .eq(UserPermission::getUserId, user.userId())
                .eq(UserPermission::getPermCode, PERM_SCORE_ALL)) > 0;
    }

    /** 用户被授予的权限点码表（登录响应携带，前端门控用；ADMIN 角色天生全权不落表） */
    public List<String> permsOf(Long userId) {
        return userPermissionMapper.selectList(new LambdaQueryWrapper<UserPermission>()
                        .eq(UserPermission::getUserId, userId))
                .stream().map(UserPermission::getPermCode).toList();
    }

    /**
     * 招采工作台操作（批33 物资两段式）：招采角色，或管理员级（ADMIN/ADMIN_ACCESS）。
     * 覆盖物资字典维护、入库、出入库流水与核销出库。
     */
    public void checkProcurementAccess(String message) {
        var user = AuthUtil.current();
        if (!"PROCUREMENT".equals(user.role()) && !hasAdminAccess(user)) {
            throw new BizException(403, message);
        }
    }
}
