package com.aischool.server.service.auth;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.RoleRequest;
import com.aischool.server.entity.User;
import com.aischool.server.entity.UserPermission;
import com.aischool.server.mapper.RoleRequestMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.mapper.UserPermissionMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.notify.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员/领导账号双人审批（批2-5，t_role_request）。
 * 新建 ADMIN/LEADER 或教师原地升级为 ADMIN/LEADER 须另一名管理员/领导审批：
 * 发起人不能自批——全校仅剩发起人一个可用审批人时例外放行（防死锁）。
 * 拒绝=新号删除/升级不动（自动还原）；审批动作经 AuditFilter 留痕。
 */
@Service
@RequiredArgsConstructor
public class RoleApprovalService {

    private final RoleRequestMapper requestMapper;
    private final UserMapper userMapper;
    private final UserPermissionMapper userPermissionMapper;
    private final PermissionService permissionService;
    private final NotificationService notificationService;

    /** 触发审批的「高权角色」：升入此集合（新建或升级）即走双人审批 */
    public static boolean needsApproval(String targetRole) {
        return "ADMIN".equals(targetRole) || "LEADER".equals(targetRole);
    }

    /** 新建高权账号后登记（账号已插入且 status=0，通过后才启用） */
    public void submitCreate(Long userId, String targetRole) {
        submitCreate(userId, targetRole, AuthUtil.current().userId());
    }

    /** 批28 教师自助注册：无登录态，发起人=账号本人（通过后启用/拒绝删号，语义同 CREATE） */
    public void submitCreate(Long userId, String targetRole, Long requestedBy) {
        RoleRequest r = new RoleRequest();
        r.setUserId(userId);
        r.setReqType(RoleRequest.TYPE_CREATE);
        r.setTargetRole(targetRole);
        r.setRequestedBy(requestedBy);
        r.setStatus(RoleRequest.ST_PENDING);
        requestMapper.insert(r);
        // 批29：通知管理员/领导审批（含企微群）
        User target = userMapper.selectById(userId);
        if (target != null) {
            boolean self = userId.equals(requestedBy);
            notificationService.registerTodo(target.getRealName(), roleName(targetRole),
                    self ? target.getRealName() : requesterName(requestedBy), self);
        }
    }

    /** 教师原地升级登记（不动 t_user，通过后才改角色）；同账号新请求覆盖旧 PENDING */
    public void submitUpgrade(Long userId, String fromRole, String targetRole) {
        requestMapper.delete(pendingOf(userId));
        RoleRequest r = new RoleRequest();
        r.setUserId(userId);
        r.setReqType(RoleRequest.TYPE_UPGRADE);
        r.setTargetRole(targetRole);
        r.setFromRole(fromRole);
        r.setRequestedBy(AuthUtil.current().userId());
        r.setStatus(RoleRequest.ST_PENDING);
        requestMapper.insert(r);
    }

    /** 待审批列表（附目标账号/发起人姓名与角色中文名） */
    public List<Map<String, Object>> pendingList() {
        List<RoleRequest> rows = requestMapper.selectList(new LambdaQueryWrapper<RoleRequest>()
                .eq(RoleRequest::getStatus, RoleRequest.ST_PENDING)
                .orderByDesc(RoleRequest::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userMapper.selectBatchIds(rows.stream()
                .flatMap(r -> List.of(r.getUserId(), r.getRequestedBy()).stream()).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream().map(r -> {
            User target = users.get(r.getUserId());
            User requester = users.get(r.getRequestedBy());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("userId", r.getUserId());
            m.put("username", target == null ? null : target.getUsername());
            m.put("realName", target == null ? "(账号已删除)" : target.getRealName());
            m.put("reqType", r.getReqType());
            m.put("targetRole", r.getTargetRole());
            m.put("targetRoleName", roleName(r.getTargetRole()));
            m.put("fromRole", r.getFromRole());
            m.put("fromRoleName", r.getFromRole() == null ? null : roleName(r.getFromRole()));
            m.put("requestedBy", r.getRequestedBy());
            m.put("requesterName", requester == null ? "(已删除)" : requester.getRealName());
            m.put("createTime", r.getCreateTime() == null ? null : r.getCreateTime().toString());
            return m;
        }).toList();
    }

    /** 通过：CREATE=启用账号；UPGRADE=改角色并清权限点（口径同 AdminUserController 角色变更） */
    public Map<String, Object> approve(Long requestId, Long approverId) {
        RoleRequest r = lockPending(requestId);
        checkApprover(r, approverId);
        if (userMapper.selectById(r.getUserId()) == null) {
            throw new BizException(404, "目标账号已不存在，请直接拒绝该申请");
        }
        if (RoleRequest.TYPE_CREATE.equals(r.getReqType())) {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, r.getUserId()).set(User::getStatus, 1));
            // 批29：教师自助注册通过后给本人一条欢迎通知（通知中心首条消息）
            if ("TEACHER".equals(r.getTargetRole())) {
                User target = userMapper.selectById(r.getUserId());
                if (target != null) {
                    notificationService.send(r.getUserId(), com.aischool.server.entity.Notification.SYSTEM,
                            "注册已通过", "您的教师账号已开通，欢迎使用石实SHINE", null);
                }
            }
        } else {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, r.getUserId())
                    .set(User::getRole, r.getTargetRole()));
            userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, r.getUserId()));
        }
        requestMapper.update(null, new LambdaUpdateWrapper<RoleRequest>()
                .eq(RoleRequest::getId, requestId)
                .set(RoleRequest::getStatus, RoleRequest.ST_APPROVED)
                .set(RoleRequest::getHandledBy, approverId)
                .set(RoleRequest::getHandleTime, LocalDateTime.now()));
        return Map.of("reqType", r.getReqType(), "targetRoleName", roleName(r.getTargetRole()));
    }

    /** 拒绝：CREATE=删号（连带权限点）；UPGRADE=不动（角色从未变更即还原） */
    public Map<String, Object> reject(Long requestId, Long approverId, String note) {
        RoleRequest r = lockPending(requestId);
        checkApprover(r, approverId);
        if (RoleRequest.TYPE_CREATE.equals(r.getReqType())) {
            userMapper.deleteById(r.getUserId());
            userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, r.getUserId()));
        }
        requestMapper.update(null, new LambdaUpdateWrapper<RoleRequest>()
                .eq(RoleRequest::getId, requestId)
                .set(RoleRequest::getStatus, RoleRequest.ST_REJECTED)
                .set(RoleRequest::getHandledBy, approverId)
                .set(RoleRequest::getHandleTime, LocalDateTime.now())
                .set(RoleRequest::getHandleNote, note == null ? null : note.trim()));
        return Map.of("reqType", r.getReqType(), "targetRoleName", roleName(r.getTargetRole()));
    }

    /** 账号删除时连带撤回其待审批请求（AdminUserController.deleteUser 调用） */
    public void withdrawByUser(Long userId) {
        requestMapper.delete(pendingOf(userId));
    }

    /** 教师列表行内嵌的待审批标记：userId → PENDING 请求（CREATE/UPGRADE） */
    public Map<Long, RoleRequest> pendingByUsers(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return requestMapper.selectList(new LambdaQueryWrapper<RoleRequest>()
                        .eq(RoleRequest::getStatus, RoleRequest.ST_PENDING)
                        .in(RoleRequest::getUserId, userIds))
                .stream().collect(Collectors.toMap(RoleRequest::getUserId, r -> r, (a, b) -> a));
    }

    /** 该账号是否有待审批请求（AuthService 登录提示用） */
    public boolean hasPending(Long userId) {
        return requestMapper.selectCount(new LambdaQueryWrapper<RoleRequest>()
                .eq(RoleRequest::getUserId, userId)
                .eq(RoleRequest::getStatus, RoleRequest.ST_PENDING)) > 0;
    }

    /** 待审批数（领导端 overview / 管理端徽标） */
    public long pendingCount() {
        return requestMapper.selectCount(new LambdaQueryWrapper<RoleRequest>()
                .eq(RoleRequest::getStatus, RoleRequest.ST_PENDING));
    }

    /** 处理前检查 PENDING 状态（两审批人并发时后到者收到「已被处理」） */
    private RoleRequest lockPending(Long requestId) {
        RoleRequest r = requestMapper.selectById(requestId);
        if (r == null) {
            throw new BizException(404, "审批申请不存在");
        }
        if (!RoleRequest.ST_PENDING.equals(r.getStatus())) {
            throw new BizException(400, "该申请已被处理");
        }
        return r;
    }

    /** 审批人资格：ADMIN/LEADER（或 ADMIN_ACCESS）；发起人不能自批，除非全校仅剩发起人一个可用审批人（防死锁） */
    private void checkApprover(RoleRequest r, Long approverId) {
        String role = AuthUtil.current().role();
        if (!"ADMIN".equals(role) && !"LEADER".equals(role) && !permissionService.hasAdminAccess(AuthUtil.current())) {
            throw new BizException(403, "仅管理员或领导可审批");
        }
        if (r.getRequestedBy().equals(approverId) && countOtherApprovers(approverId) > 0) {
            throw new BizException(403, "发起人不能自批，请由另一名管理员/领导审批");
        }
        // 例外：发起人=全校唯一可用审批人时放行自批（防死锁），由 countOtherApprovers==0 兜底
    }

    /** 除指定人外的可用审批人数：启用中的 ADMIN/LEADER（任何领导都是合法审批人，ADMIN_ACCESS 无需单列） */
    private long countOtherApprovers(Long excludeUserId) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .in(User::getRole, "ADMIN", "LEADER")
                .eq(User::getStatus, 1)
                .ne(User::getId, excludeUserId));
    }

    private LambdaQueryWrapper<RoleRequest> pendingOf(Long userId) {
        return new LambdaQueryWrapper<RoleRequest>()
                .eq(RoleRequest::getUserId, userId)
                .eq(RoleRequest::getStatus, RoleRequest.ST_PENDING);
    }

    /** 发起人姓名（批29 通知文案用；账号已删则回退工号场景的占位） */
    private String requesterName(Long userId) {
        User u = userMapper.selectById(userId);
        return u == null ? "(已删除)" : u.getRealName();
    }

    private String roleName(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> "管理员";
            case "LEADER" -> "领导";
            case "HEAD_TEACHER" -> "班主任";
            case "TEACHER" -> "教师";
            case "GRADE_LEADER" -> "级长";
            case "DIRECTOR" -> "学成中心主任";
            case "DORM" -> "生活老师";
            case "PROCUREMENT" -> "招采";
            case "LOGISTICS" -> "后勤中心"; // 批63 报修审批
            case "REPAIRER" -> "水电维修工"; // 批63 报修接单
            default -> role;
        };
    }
}
