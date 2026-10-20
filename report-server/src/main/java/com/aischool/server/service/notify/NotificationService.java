package com.aischool.server.service.notify;

import com.aischool.server.entity.Notification;
import com.aischool.server.entity.Teach;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.NotificationMapper;
import com.aischool.server.mapper.TeachMapper;
import com.aischool.server.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知中心（批29）：App 内点对点通知落 t_notification，待办类同步推企微群。
 * 所有发送入口吞异常——通知失败绝不阻断业务主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final TeachMapper teachMapper;
    private final WecomService wecomService;

    /** 落一条通知（唯一写入口；异常吞掉只记日志） */
    public void send(Long userId, String type, String title, String content, String link) {
        if (userId == null) {
            return;
        }
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(title == null ? "" : title);
            n.setContent(content);
            n.setLink(link);
            notificationMapper.insert(n);
        } catch (Exception e) {
            log.warn("通知写入失败 user={} title={}：{}", userId, title, e.getMessage());
        }
    }

    /** 通知所有启用中的指定角色（账号审批/告警/周报广播用） */
    public void sendToRoles(List<String> roles, String type, String title, String content, String link) {
        userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, roles).eq(User::getStatus, 1))
                .forEach(u -> send(u.getId(), type, title, content, link));
    }

    // ────────────────────────── 事件格式化（批29 接线点专用） ──────────────────────────

    /** OA 提交/流转到下一级：通知当前级审批人（App 内 + 企微群） */
    public void oaTodo(Long approverId, String typeName, String title, Long formId, String applicantName) {
        send(approverId, Notification.OA_TODO, "待审批：" + typeName,
                applicantName + " 提交的「" + title + "」等待您审批", "/oa");
        wecomService.pushApprovals("【新审批】" + applicantName + " 提交了" + typeName
                + "：" + title + "（单号 #" + formId + "）");
    }

    /** OA 或签节点（批43 采购）：每人 App 一条待审，企微群合并一条（防重复刷屏；单人与 oaTodo 等价） */
    public void oaTodoAny(List<Long> approverIds, String typeName, String title, Long formId, String applicantName) {
        for (Long id : approverIds) {
            send(id, Notification.OA_TODO, "待审批：" + typeName,
                    applicantName + " 提交的「" + title + "」等待您审批", "/oa");
        }
        wecomService.pushApprovals("【新审批】" + applicantName + " 提交了" + typeName
                + "：" + title + "（单号 #" + formId + "）");
    }

    /** OA 终态：通知申请人审批结果（不推群，防刷屏） */
    public void oaResult(Long applicantId, String typeName, String title, boolean approved, String note) {
        String verdict = approved ? "已审批通过" : "已被驳回";
        send(applicantId, Notification.OA_RESULT, typeName + verdict,
                "您提交的「" + title + "」" + verdict
                        + (note == null || note.isBlank() ? "" : "，意见：" + note), "/oa");
    }

    /** 物资核销出库（批33 两段式）：通知申请人其物资已由招采核销 */
    public void goodsIssued(Long applicantId, String title, String operatorName) {
        send(applicantId, Notification.OA_RESULT, "物资已核销出库",
                "您申领的「" + title + "」已由 " + operatorName + " 核销出库，请查收", "/oa");
    }

    /**
     * 学生请假待审批（批32 分级流）：通知当前级审批人（App 内 + 企微群）。
     * stepName=级长/学成中心主任；无绑定级长时上级兜底（approverIds=主任+领导+管理员）。
     */
    public void leaveTodo(List<Long> approverIds, String stepName, String studentName, String className,
                          String leaveType, String range) {
        for (Long tid : approverIds) {
            send(tid, Notification.LEAVE_TODO, "待审批：学生请假（" + stepName + "）",
                    className + " " + studentName + " 的" + leaveType + "（" + range + "）等待您审批", "/leave");
        }
        wecomService.pushApprovals("【学生请假】" + className + " " + studentName
                + " 的" + leaveType + "（" + range + "）等待" + stepName + "审批");
    }

    /** 请假登记回执（批32）：教师代录后通知全部绑定家长（家长只收通知、不操作） */
    public void leaveRegister(List<Long> parentIds, String studentName, String className,
                              String leaveType, String range, String creatorName) {
        for (Long pid : parentIds) {
            send(pid, Notification.LEAVE_NOTICE, "请假登记回执",
                    className + " " + studentName + " 的" + leaveType + "（" + range
                            + "）已由 " + creatorName + " 登记请假，请知悉", "/p/leave");
        }
    }

    /** 请假审批结果：通知全部绑定家长 */
    public void leaveResult(List<Long> parentIds, String studentName, boolean approved, String approverName, String note) {
        String verdict = approved ? "已批准" : "已被驳回";
        String extra = note == null || note.isBlank() ? "" : "，老师备注：" + note;
        for (Long pid : parentIds) {
            send(pid, Notification.LEAVE_RESULT, "请假" + verdict,
                    studentName + " 的请假申请" + verdict + "（审批人：" + approverName + "）" + extra, "/p/leave");
        }
    }

    /**
     * 请假信息同步（批32 → 批39④ → 批44②）：批准后自动抄送门卫（出校核验）/生活老师/行政（学成中心主任）/
     * 该班任课教师（知悉即可，通知文本已含全部信息，无 link 不引导进请假列表——批31 收紧口径任课教师不可见）。
     * excludeUserId=录入/审批教师本人（0 级班主任自录自批不再自扰）。
     */
    public void leaveSyncGuard(String studentName, String className, String leaveType, String range,
                               Long classId, Long excludeUserId) {
        sendToRoles(List.of("GUARD"), Notification.LEAVE_NOTICE, "请假批准·出校核验",
                className + " " + studentName + " 的" + leaveType + "（" + range + "）已批准，离校请核验登记", "/g/home");
        sendToRoles(List.of("DORM"), Notification.LEAVE_NOTICE, "请假同步（生活老师）",
                className + " " + studentName + " 的" + leaveType + "（" + range + "）已批准，请知悉", "/leave");
        sendToRoles(List.of("DIRECTOR"), Notification.LEAVE_NOTICE, "请假批准（行政知悉）",
                className + " " + studentName + " 的" + leaveType + "（" + range + "）已批准", "/leave");
        if (classId != null) {
            teachMapper.selectList(new LambdaQueryWrapper<Teach>().eq(Teach::getClassId, classId))
                    .stream().map(Teach::getTeacherId).distinct()
                    .filter(id -> !id.equals(excludeUserId))
                    .forEach(id -> send(id, Notification.LEAVE_NOTICE, "请假同步（任课教师）",
                            className + " " + studentName + " 的" + leaveType + "（" + range
                                    + "）已批准，该时段您的课该生缺席，请知悉", null));
        }
        wecomService.pushApprovals("【学生请假·门卫核验】" + className + " " + studentName
                + " 的" + leaveType + "（" + range + "）已批准");
    }

    /** 撤销同步（批32）：撤单后通知全部绑定家长 */
    public void leaveCancelled(List<Long> parentIds, String studentName, String operatorName) {
        for (Long pid : parentIds) {
            send(pid, Notification.LEAVE_NOTICE, "请假已撤销",
                    studentName + " 的请假单已由 " + operatorName + " 撤销", "/p/leave");
        }
    }

    // ────────────────────────── 报修三层流转（批63） ──────────────────────────

    /** 报修待办：提交→通知后勤中心（LOGISTICS）审批；审批通过→通知维修工（REPAIRER）抢单（按角色广播） */
    public void repairTodo(String role, String title, String content) {
        sendToRoles(List.of(role), Notification.REPAIR_TODO, title, content, "/repair");
    }

    /** 报修结果（批63）：拒绝/接单/完成→报修人；完成抄送 repair_cc_uids 名单 */
    public void repairResult(Long userId, String title, String content) {
        send(userId, Notification.REPAIR_RESULT, title, content, "/repair");
    }

    /** 账号/注册待审批：通知全部启用中的管理员+领导（App 链接按角色分流）；self=教师自助注册 */
    public void registerTodo(String targetName, String targetRoleName, String requesterName, boolean self) {
        String detail = self
                ? targetName + " 的注册申请等待您审批"
                : targetName + "（由 " + requesterName + " 发起）等待您审批";
        wecomService.pushApprovals(self
                ? "【教师注册】" + targetName + " 提交了注册申请，等待审批"
                : "【待审批】" + requesterName + " 申请：" + targetName + "（" + targetRoleName + "）");
        userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, "ADMIN", "LEADER").eq(User::getStatus, 1))
                .forEach(u -> send(u.getId(), Notification.REGISTER_TODO,
                        "待审批：" + targetRoleName + "账号", detail,
                        "ADMIN".equals(u.getRole()) ? "/admin?tab=roleRequest" : "/l/approvals"));
    }
}
