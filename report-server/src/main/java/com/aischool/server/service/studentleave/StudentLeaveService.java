package com.aischool.server.service.studentleave;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.GradeBinding;
import com.aischool.server.entity.LeaveFlowLog;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.StudentLeave;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.GradeBindingMapper;
import com.aischool.server.mapper.LeaveFlowLogMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.StudentLeaveMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.notify.NotificationService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 学生请假（批32 重构 → 批51 三级审批链）：家长不再 App 提交（微信/电话告知班主任，由教师代录），家长只收通知。
 * 分级审批按时长自动判定（阈值 t_sys_config：leave_level1_days/leave_level2_days/leave_max_days）：
 * ≤L1（默认 3 天）级长或级部主任一级审批；L1~L2（3~7 天）加学成中心二级；L2~max（7~30 天）加书记三级；
 * 超 max 走纸质（系统不受理）。级部主任=GRADE_DIRECTOR 岗位绑定（t_grade_binding，初中各年级），审批权与级长一致；
 * 书记=SECRETARY 角色（教师壳），任一级可审+终审；领导/管理员恒可代批（缺位流程不卡死）。
 * 生活老师（DORM）登记限时段：leave_dorm_windows（默认 12:00-14:15,21:30-07:30，跨午夜段自动回绕）。
 * 数据同步：班主任/生活老师/门卫/级长及以上（任课教师不可见）；批准后门卫+生活老师+行政（学成中心主任）+
 * 该班任课教师（批44② 知悉通知，仅通知不开放列表）自动抄送。
 * 流转轨迹 t_leave_flow_log（详情页时间线）；门卫离校/返校登记沿用。
 */
@Service
@RequiredArgsConstructor
public class StudentLeaveService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StudentLeaveMapper leaveMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final UserMapper userMapper;
    private final ParentBindingMapper bindingMapper;
    private final GradeBindingMapper gradeBindingMapper;
    private final SysConfigMapper sysConfigMapper;
    private final LeaveFlowLogMapper flowLogMapper;
    private final DataScopeService dataScope;
    private final PdfStoreService pdfStore;
    private final NotificationService notificationService;

    // ────────────────────────── 教师侧：录入 ──────────────────────────

    /**
     * 教师代录请假单（班主任本班 / 生活老师全校 / 级长本年级 / 主任·领导·管理员全校）。
     * 时长自动折算到 0.1 天并决定审批级数（批51 起 1/2/3 级，不再有登记即生效）；
     * 生活老师限 leave_dorm_windows 时段登记。
     */
    public Map<String, Object> create(UserPrincipal user, Long studentId, String leaveType,
                                      LocalDateTime start, LocalDateTime end, String reason,
                                      List<MultipartFile> photos) {
        Student s = requireCreator(user, studentId);
        if ("DORM".equals(user.role()) && !inDormWindow(LocalDateTime.now())) {
            throw new BizException(403, "生活老师仅可在 " + dormWindowText() + " 时段登记请假");
        }
        if (!StudentLeave.TYPES.contains(leaveType)) {
            throw new BizException(400, "请假类型只能是 病假/事假/其他");
        }
        if (start == null || end == null) {
            throw new BizException(400, "请选择请假起止时间");
        }
        if (!end.isAfter(start)) {
            throw new BizException(400, "结束时间必须晚于开始时间");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(400, "请填写请假事由");
        }
        if (reason.length() > 300) {
            throw new BizException(400, "请假事由不能超过 300 字");
        }
        int l1 = cfgInt("leave_level1_days", 3);
        int l2 = cfgInt("leave_level2_days", 7);
        int max = cfgInt("leave_max_days", 30);
        BigDecimal dur = durationOf(start, end);
        if (dur.doubleValue() > max) {
            throw new BizException(400, "请假超过 " + max + " 天须走纸质申请流程，系统不受理");
        }
        // 批51 三级链：1=级长/级部主任 / 2=+学成中心 / 3=+书记（历史单 totalStep=2 两级口径不变，step2 语义相同）
        int totalStep = dur.doubleValue() <= l1 ? 1 : (dur.doubleValue() <= l2 ? 2 : 3);

        List<String> objects = uploadPhotos(photos);
        StudentLeave l = new StudentLeave();
        l.setStudentId(studentId);
        l.setCreatorId(user.userId());
        l.setLeaveType(leaveType);
        l.setStartTime(start);
        l.setEndTime(end);
        l.setReason(reason.trim());
        l.setDurationDays(dur);
        l.setPhotos(objects.isEmpty() ? null : toJson(objects));
        l.setTotalStep(totalStep);
        l.setCurrentStep(1);
        l.setStatus(StudentLeave.PENDING);
        leaveMapper.insert(l);
        flow(l.getId(), LeaveFlowLog.SUBMIT, null, user.userId(), null);

        Clazz c = clazzOf(s);
        String range = fmt(start) + " ~ " + fmt(end);
        String className = c == null ? "" : c.getName();
        List<Long> parents = boundParentIds(studentId);
        notificationService.leaveRegister(parents, s.getName(), className, leaveType, range, user.realName());
        notifyStepTodo(l, s, c);
        return Map.of("leaveId", l.getId(), "status", l.getStatus(), "totalStep", totalStep);
    }

    /** 录入权校验：角色门 + 范围门（挂名班主任本班 / 级长本年级），返回学生实体。
     *  批51b 挂名即班主任：t_class 挂名者不问账号角色即可代录本班（普通教师/级长挂名班主任场景） */
    private Student requireCreator(UserPrincipal user, Long studentId) {
        String role = user.role();
        if ("PARENT".equals(role)) {
            throw new BizException(403, "家长请假已改为联系班主任（微信/电话）办理，由老师在系统登记，家长在 App 收通知即可");
        }
        Student s = studentMapper.selectById(studentId);
        if (s == null) {
            throw new BizException(404, "学生不存在");
        }
        Clazz c = clazzOf(s);
        if (c != null && user.userId().equals(c.getHeadTeacherId())) {
            return s;
        }
        if ("TEACHER".equals(role) || "GUARD".equals(role)) {
            throw new BizException(403, "请假登记由班主任/生活老师/级长及以上角色操作");
        }
        if ("HEAD_TEACHER".equals(role)) {
            throw new BizException(403, "班主任只能登记本班学生的请假");
        }
        if ("GRADE_LEADER".equals(role) && !boundGradeIds(user).contains(gradeIdOf(s))) {
            throw new BizException(403, "级长只能登记本年级学生的请假");
        }
        return s;
    }

    /** 当前级审批人待办（缺位兜底转上级，流程不卡死；批51 一级=级长∪级部主任，第 3 级=书记缺位转领导/管理员） */
    private void notifyStepTodo(StudentLeave l, Student s, Clazz c) {
        String className = c == null ? "" : c.getName();
        String range = rangeOf(l);
        if (l.getCurrentStep() == 1) {
            Long gradeId = gradeIdOf(s);
            List<Long> ids = new ArrayList<>(gradeDutyIds(gradeId, GradeBinding.DUTY_GRADE_LEADER));
            gradeDutyIds(gradeId, GradeBinding.DUTY_GRADE_DIRECTOR).forEach(ids::add);
            ids = ids.stream().distinct().toList();
            String stepName = "级长/级部主任";
            if (ids.isEmpty()) {
                ids = roleIds("DIRECTOR", "LEADER", "ADMIN");
                stepName = "级长（该年级未绑定级长/级部主任，已转学成中心主任/领导处理）";
            }
            notificationService.leaveTodo(ids, stepName, s.getName(), className, l.getLeaveType(), range);
        } else if (l.getCurrentStep() == 2) {
            List<Long> ids = roleIds("DIRECTOR");
            if (ids.isEmpty()) {
                ids = roleIds("LEADER", "ADMIN");
            }
            notificationService.leaveTodo(ids, "学成中心主任", s.getName(), className, l.getLeaveType(), range);
        } else {
            List<Long> ids = roleIds("SECRETARY");
            if (ids.isEmpty()) {
                ids = roleIds("LEADER", "ADMIN");
            }
            notificationService.leaveTodo(ids, "书记", s.getName(), className, l.getLeaveType(), range);
        }
    }

    // ────────────────────────── 审批 ──────────────────────────

    /** 通过当前级；末级通过才置 APPROVED 并同步家长结果 + 门卫/生活老师 */
    public void approve(Long id, UserPrincipal user, String note) {
        StudentLeave l = requirePending(id);
        Student s = studentOf(l);
        requireStepApprover(user, l, s);
        if (note != null && note.length() > 200) {
            throw new BizException(400, "审批意见不能超过 200 字");
        }
        note = note == null || note.isBlank() ? null : note.trim();
        int step = l.getCurrentStep();
        flow(l.getId(), LeaveFlowLog.APPROVE, step, user.userId(), note);
        if (step < l.getTotalStep()) {
            l.setCurrentStep(step + 1);
            leaveMapper.updateById(l);
            notifyStepTodo(l, s, clazzOf(s));
            return;
        }
        l.setStatus(StudentLeave.APPROVED);
        l.setCurrentStep(0);
        l.setApproverId(user.userId());
        l.setApproveNote(note);
        l.setApproveTime(LocalDateTime.now());
        leaveMapper.updateById(l);
        Clazz c = clazzOf(s);
        String className = c == null ? "" : c.getName();
        notificationService.leaveResult(boundParentIds(l.getStudentId()), s.getName(),
                true, user.realName(), note);
        notificationService.leaveSyncGuard(s.getName(), className, l.getLeaveType(), rangeOf(l),
                c == null ? null : c.getId(), user.userId());
    }

    /** 驳回（终态；意见必填——家长须知道原因；任意级可驳） */
    public void reject(Long id, UserPrincipal user, String note) {
        StudentLeave l = requirePending(id);
        requireStepApprover(user, l, studentOf(l));
        if (note == null || note.isBlank()) {
            throw new BizException(400, "驳回时请填写原因");
        }
        if (note.length() > 200) {
            throw new BizException(400, "审批意见不能超过 200 字");
        }
        l.setStatus(StudentLeave.REJECTED);
        l.setCurrentStep(0);
        l.setApproverId(user.userId());
        l.setApproveNote(note.trim());
        l.setApproveTime(LocalDateTime.now());
        leaveMapper.updateById(l);
        flow(l.getId(), LeaveFlowLog.REJECT, l.getCurrentStep(), user.userId(), note.trim());
        notificationService.leaveResult(boundParentIds(l.getStudentId()), studentNameOf(l),
                false, user.realName(), note.trim());
    }

    /** 撤销待审批单（发起教师本人，或学成中心主任/领导/管理员代管；家长端已无操作入口） */
    public void cancel(Long id, UserPrincipal user) {
        StudentLeave l = requirePending(id);
        boolean mgr = "DIRECTOR".equals(user.role()) || "LEADER".equals(user.role()) || "ADMIN".equals(user.role());
        if (!mgr && !user.userId().equals(l.getCreatorId())) {
            throw new BizException(403, "仅发起教师或学成中心主任/领导/管理员可撤销");
        }
        l.setStatus(StudentLeave.CANCELLED);
        l.setCurrentStep(0);
        leaveMapper.updateById(l);
        flow(l.getId(), LeaveFlowLog.CANCEL, null, user.userId(), null);
        notificationService.leaveCancelled(boundParentIds(l.getStudentId()), studentNameOf(l), user.realName());
    }

    // ────────────────────────── 列表 / 详情 ──────────────────────────

    /**
     * 教师端列表：任课教师不可见（批32 收紧）。scope=my=管辖范围（班主任本班/级长本年级/
     * 生活老师·主任·领导·管理员全校），scope=all=全校（仅主任/领导/管理员）。
     * 行内 canApprove 标记当前级审批权。
     */
    public List<Map<String, Object>> list(UserPrincipal user, String status, String scope) {
        String role = user.role();
        if ("TEACHER".equals(role) || "GUARD".equals(role) || "PARENT".equals(role)) {
            throw new BizException(403, "请假信息同步对象为班主任/生活老师/门卫/级长及以上，任课教师不可见");
        }
        if ("all".equals(scope) && !"DIRECTOR".equals(role) && !"LEADER".equals(role) && !"ADMIN".equals(role)) {
            throw new BizException(403, "全校视图仅学成中心主任/领导/管理员可用");
        }
        List<Long> visible = "all".equals(scope) ? null : leaveVisible(user);
        List<StudentLeave> rows = leaveMapper.selectList(new LambdaQueryWrapper<StudentLeave>()
                .eq(status != null && !status.isBlank(), StudentLeave::getStatus, status)
                .orderByDesc(StudentLeave::getId)
                .last("LIMIT 500"));
        if (visible != null) {
            rows = visible.isEmpty() ? List.of() : filterByClass(rows, visible);
        }
        List<Map<String, Object>> list = toRows(rows);
        Map<Long, Student> students = studentsOf(rows);
        for (Map<String, Object> m : list) {
            StudentLeave l = byId(rows, ((Number) m.get("id")).longValue());
            Student s = students.get(l.getStudentId());
            m.put("canApprove", StudentLeave.PENDING.equals(l.getStatus()) && isStepApprover(user, l, s));
        }
        return list;
    }

    /** 家长端只读：绑定孩子的全部请假单（不再区分是否本人提交） */
    public List<Map<String, Object>> my(UserPrincipal parent) {
        List<Long> studentIds = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                        .eq(ParentBinding::getParentUserId, parent.userId()))
                .stream().map(ParentBinding::getStudentId).distinct().toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return toRows(leaveMapper.selectList(new LambdaQueryWrapper<StudentLeave>()
                .in(StudentLeave::getStudentId, studentIds)
                .orderByDesc(StudentLeave::getId)));
    }

    /** 请假线走行政口径不分会段（批41）：领导保持全校（兜底代批任一级不受学段限制），其余角色同统一数据范围 */
    private List<Long> leaveVisible(UserPrincipal user) {
        return "LEADER".equals(user.role()) ? null : dataScope.visibleClassIds(user);
    }

    /** 录入表单数据源：管辖范围内班级列表 */
    public List<Map<String, Object>> classes(UserPrincipal user) {
        List<Long> visible = leaveVisible(user);
        List<Clazz> cs = visible == null
                ? clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId))
                : visible.isEmpty() ? List.of() : clazzMapper.selectBatchIds(visible);
        return cs.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName() == null ? "" : c.getName());
            return m;
        }).collect(Collectors.toList());
    }

    /** 录入表单数据源：班级内学生（学号/姓名可搜；范围校验同列表口径） */
    public List<Map<String, Object>> students(UserPrincipal user, Long classId, String q) {
        List<Long> visible = leaveVisible(user);
        if (visible != null && !visible.contains(classId)) {
            throw new BizException(403, "无权查看该班级学生（数据权限隔离）");
        }
        List<Student> rows = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId)
                .orderByAsc(Student::getStudentNo)
                .last("LIMIT 500"));
        return rows.stream()
                .filter(s -> q == null || q.isBlank()
                        || s.getName().contains(q.trim())
                        || (s.getStudentNo() != null && s.getStudentNo().contains(q.trim())))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("studentNo", s.getStudentNo());
                    return m;
                }).collect(Collectors.toList());
    }

    /** 详情（含流转时间线）：绑定家长 / 教师侧各角色 / 门卫可读，任课教师不可见 */
    public Map<String, Object> detail(Long id, UserPrincipal user) {
        StudentLeave l = require(id);
        checkReadable(l, user);
        Student s = studentsOf(List.of(l)).get(l.getStudentId());
        Map<String, Object> m = rowOf(l, namesOf(List.of(l)), s, classNames(s == null ? List.<Student>of() : List.of(s)));
        m.put("reason", l.getReason());
        m.put("approveNote", l.getApproveNote() == null ? "" : l.getApproveNote());
        List<LeaveFlowLog> logs = flowLogMapper.selectList(new LambdaQueryWrapper<LeaveFlowLog>()
                .eq(LeaveFlowLog::getLeaveId, id).orderByAsc(LeaveFlowLog::getId));
        Map<Long, String> names = logs.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(logs.stream().map(LeaveFlowLog::getOperatorId).distinct().toList())
                        .stream().collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (LeaveFlowLog f : logs) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("action", actionLabel(f));
            t.put("operator", names.getOrDefault(f.getOperatorId(), ""));
            t.put("note", f.getNote() == null ? "" : f.getNote());
            t.put("time", f.getCreateTime() == null ? "" : fmt(f.getCreateTime()));
            timeline.add(t);
        }
        m.put("logs", timeline);
        return m;
    }

    // ────────────────────────── 门卫侧 ──────────────────────────

    /** 门卫核验视图：某日有效的已批准请假单（起止时间与该日有交集），可按学号/姓名/班级搜 */
    public List<Map<String, Object>> guardList(UserPrincipal guard, LocalDate date, String q) {
        requireGuard(guard);
        LocalDate d = date == null ? LocalDate.now() : date;
        List<StudentLeave> rows = leaveMapper.selectList(new LambdaQueryWrapper<StudentLeave>()
                .eq(StudentLeave::getStatus, StudentLeave.APPROVED)
                .le(StudentLeave::getStartTime, d.atTime(23, 59, 59))
                .ge(StudentLeave::getEndTime, d.atStartOfDay())
                .orderByDesc(StudentLeave::getId)
                .last("LIMIT 500"));
        return toRows(rows).stream()
                .filter(m -> matchesKeyword(m, q))
                .collect(Collectors.toList());
    }

    /** 门卫登记离校（幂等口径：已登记则 400，避免覆盖首次时间） */
    public void registerLeave(Long id, UserPrincipal guard) {
        StudentLeave l = requireGuardApproved(id);
        if (l.getLeaveTime() != null) {
            throw new BizException(400, "该学生已登记离校 " + fmt(l.getLeaveTime()));
        }
        l.setLeaveTime(LocalDateTime.now());
        l.setLeaveGuardId(guard.userId());
        leaveMapper.updateById(l);
        flow(l.getId(), LeaveFlowLog.REG_LEAVE, null, guard.userId(), null);
    }

    /** 门卫登记返校（须先有离校记录） */
    public void registerReturn(Long id, UserPrincipal guard) {
        StudentLeave l = requireGuardApproved(id);
        if (l.getLeaveTime() == null) {
            throw new BizException(400, "尚未登记离校，请先登记离校");
        }
        if (l.getReturnTime() != null) {
            throw new BizException(400, "该学生已登记返校 " + fmt(l.getReturnTime()));
        }
        l.setReturnTime(LocalDateTime.now());
        l.setReturnGuardId(guard.userId());
        leaveMapper.updateById(l);
        flow(l.getId(), LeaveFlowLog.REG_RETURN, null, guard.userId(), null);
    }

    // ────────────────────────── 通用 ──────────────────────────

    /** 逐张凭证照片回图（病假条等；可见性同详情） */
    public String photoObject(Long id, int idx, UserPrincipal user) {
        StudentLeave l = require(id);
        checkReadable(l, user);
        List<String> objects = parsePhotos(l.getPhotos());
        if (idx < 0 || idx >= objects.size()) {
            throw new BizException(404, "照片不存在");
        }
        return objects.get(idx);
    }

    private void checkReadable(StudentLeave l, UserPrincipal user) {
        String role = user.role();
        if ("PARENT".equals(role)) {
            if (boundParentIds(l.getStudentId()).contains(user.userId())) {
                return;
            }
            throw new BizException(403, "无权查看该请假单");
        }
        if ("HEAD_TEACHER".equals(role) || "DORM".equals(role) || "GRADE_LEADER".equals(role)
                || "DIRECTOR".equals(role) || "LEADER".equals(role) || "ADMIN".equals(role)
                || "SECRETARY".equals(role) || "GUARD".equals(role)) {
            return; // 同步对象全员可读详情（列表仍按管辖范围过滤；批39④ 书记=终审人）
        }
        // 批51b 挂名即班主任：普通教师挂名班主任可读本班请假详情（列表仍按任课班过滤）
        Student s = studentMapper.selectById(l.getStudentId());
        Clazz c = s == null ? null : clazzOf(s);
        if (c != null && user.userId().equals(c.getHeadTeacherId())) {
            return;
        }
        throw new BizException(403, "任课教师不可查看学生请假");
    }

    /** 当前级审批权（批39④ 四级链）：1级=本年级绑定级长；2级=学成中心主任；3级=书记终审。
     * 书记（SECRETARY）级别最高，任一级可审；主任 1/2 级可审（兜底级长缺位）；领导/管理员恒可代批 */
    private boolean isStepApprover(UserPrincipal user, StudentLeave l, Student s) {
        String role = user.role();
        if ("LEADER".equals(role) || "ADMIN".equals(role) || "SECRETARY".equals(role)) {
            return true;
        }
        if ("DIRECTOR".equals(role)) {
            return l.getCurrentStep() != null && l.getCurrentStep() < 3; // 书记终审级主任不代批
        }
        if ("GRADE_LEADER".equals(role) && l.getCurrentStep() == 1) {
            return s != null && boundGradeIds(user).contains(gradeIdOf(s));
        }
        return false;
    }

    private void requireStepApprover(UserPrincipal user, StudentLeave l, Student s) {
        if (isStepApprover(user, l, s)) {
            return;
        }
        if (l.getCurrentStep() == 1) {
            throw new BizException(403, "当前级由级长或级部主任审批（学成中心主任/书记/领导/管理员可代批）");
        }
        if (l.getCurrentStep() == 2) {
            throw new BizException(403, "当前级由学成中心主任审批（书记/领导/管理员可代批）");
        }
        throw new BizException(403, "当前级由书记终审（领导/管理员可代批）");
    }

    private StudentLeave require(Long id) {
        StudentLeave l = leaveMapper.selectById(id);
        if (l == null) {
            throw new BizException(404, "请假单不存在");
        }
        return l;
    }

    private StudentLeave requirePending(Long id) {
        StudentLeave l = require(id);
        if (!StudentLeave.PENDING.equals(l.getStatus())) {
            throw new BizException(400, "该请假单已处理结束");
        }
        // 历史行修复（V33 回填 current_step=0 的批27~31 待审单：单级流程视为待级长审）
        if ((l.getCurrentStep() == null || l.getCurrentStep() == 0)
                && l.getTotalStep() != null && l.getTotalStep() >= 1) {
            l.setCurrentStep(1);
        }
        return l;
    }

    private StudentLeave requireGuardApproved(Long id) {
        StudentLeave l = require(id);
        if (!StudentLeave.APPROVED.equals(l.getStatus())) {
            throw new BizException(400, "仅已批准的请假单可登记离校/返校");
        }
        return l;
    }

    private void requireGuard(UserPrincipal user) {
        if (!"GUARD".equals(user.role())) {
            throw new BizException(403, "仅门卫账号可访问");
        }
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /** 时长折算：秒→天，四舍五入到 0.1 天，下限 0.1（半天式折算，分级依据） */
    private BigDecimal durationOf(LocalDateTime start, LocalDateTime end) {
        long seconds = Duration.between(start, end).getSeconds();
        BigDecimal days = BigDecimal.valueOf(seconds)
                .divide(BigDecimal.valueOf(86400L), 1, RoundingMode.HALF_UP);
        return days.compareTo(new BigDecimal("0.1")) < 0 ? new BigDecimal("0.1") : days;
    }

    private int cfgInt(String key, int def) {
        try {
            SysConfig c = sysConfigMapper.selectById(key);
            return c == null ? def : Integer.parseInt(c.getCfgValue().trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 生活老师时段窗配置原文：未配置=默认（批51 校方要求 12:00-14:15,21:30-07:30）；显式清空=不限 */
    private String dormWindowsCfg() {
        try {
            SysConfig c = sysConfigMapper.selectById("leave_dorm_windows");
            return c == null || c.getCfgValue() == null ? "12:00-14:15,21:30-07:30" : c.getCfgValue().trim();
        } catch (Exception e) {
            return "12:00-14:15,21:30-07:30";
        }
    }

    /** 生活老师登记时段窗（批51）："HH:mm-HH:mm" 逗号分隔多段，start>end 视为跨午夜回绕 */
    private boolean inDormWindow(LocalDateTime now) {
        String cfg = dormWindowsCfg();
        if (cfg.isBlank()) {
            return true;
        }
        LocalTime t = now.toLocalTime();
        for (String seg : cfg.split(",")) {
            String[] parts = seg.trim().split("-");
            if (parts.length != 2) {
                continue;
            }
            try {
                LocalTime s = LocalTime.parse(parts[0].trim());
                LocalTime e = LocalTime.parse(parts[1].trim());
                if (s.isAfter(e) ? (t.isAfter(s) || !t.isAfter(e)) : (!t.isBefore(s) && t.isBefore(e))) {
                    return true;
                }
            } catch (Exception ignore) {
                // 坏段跳过
            }
        }
        return false;
    }

    /** 时段窗展示文案（提示生活老师可登记时段；坏段原样展示） */
    private String dormWindowText() {
        String cfg = dormWindowsCfg();
        if (cfg.isBlank()) {
            return "任意";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : cfg.split(",")) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            String[] parts = s.split("-");
            if (parts.length == 2) {
                try {
                    LocalTime st = LocalTime.parse(parts[0].trim());
                    LocalTime en = LocalTime.parse(parts[1].trim());
                    sb.append(st).append("~").append(st.isAfter(en) ? "次日" : "").append(en);
                    continue;
                } catch (Exception ignore) {
                    // 回退原样展示
                }
            }
            sb.append(s);
        }
        return sb.length() == 0 ? "任意" : sb.toString();
    }

    private List<Long> boundGradeIds(UserPrincipal user) {
        return gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                        .eq(GradeBinding::getUserId, user.userId())
                        .eq(GradeBinding::getDuty, GradeBinding.DUTY_GRADE_LEADER))
                .stream().map(GradeBinding::getGradeId).distinct().toList();
    }

    private List<Long> gradeDutyIds(Long gradeId, String duty) {
        if (gradeId == null) {
            return List.of();
        }
        return gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                        .eq(GradeBinding::getGradeId, gradeId)
                        .eq(GradeBinding::getDuty, duty))
                .stream().map(GradeBinding::getUserId).distinct().toList();
    }

    private List<Long> roleIds(String... roles) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, List.of(roles)).eq(User::getStatus, 1))
                .stream().map(User::getId).toList();
    }

    private List<Long> boundParentIds(Long studentId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                        .eq(ParentBinding::getStudentId, studentId))
                .stream().map(ParentBinding::getParentUserId).distinct().toList();
    }

    private Long gradeIdOf(Student s) {
        if (s == null || s.getClassId() == null) {
            return null;
        }
        Clazz c = clazzMapper.selectById(s.getClassId());
        return c == null ? null : c.getGradeId();
    }

    private Clazz clazzOf(Student s) {
        return s == null || s.getClassId() == null ? null : clazzMapper.selectById(s.getClassId());
    }

    private Student studentOf(StudentLeave l) {
        return studentMapper.selectById(l.getStudentId());
    }

    private String studentNameOf(StudentLeave l) {
        Student s = studentOf(l);
        return s == null ? "(学生已删除)" : s.getName();
    }

    /** 流转日志（旁路：失败只影响时间线，不阻断主流程） */
    private void flow(Long leaveId, String action, Integer step, Long operatorId, String note) {
        try {
            LeaveFlowLog f = new LeaveFlowLog();
            f.setLeaveId(leaveId);
            f.setAction(action);
            f.setStep(step);
            f.setOperatorId(operatorId);
            f.setNote(note);
            flowLogMapper.insert(f);
        } catch (Exception e) {
            // 时间线属旁路
        }
    }

    private String actionLabel(LeaveFlowLog f) {
        return switch (f.getAction()) {
            case LeaveFlowLog.SUBMIT -> "提交登记";
            case LeaveFlowLog.APPROVE -> (f.getStep() != null && f.getStep() == 3 ? "书记"
                    : f.getStep() != null && f.getStep() == 2 ? "学成中心" : "级长/级部主任") + "审批通过";
            case LeaveFlowLog.REJECT -> "驳回";
            case LeaveFlowLog.CANCEL -> "撤销";
            case LeaveFlowLog.REG_LEAVE -> "登记离校";
            case LeaveFlowLog.REG_RETURN -> "登记返校";
            default -> f.getAction();
        };
    }

    /** 按可见班级过滤（行级，单量小；join 查询不值得为 94 班引入） */
    private List<StudentLeave> filterByClass(List<StudentLeave> rows, List<Long> classIds) {
        Map<Long, Student> students = studentsOf(rows);
        return rows.stream().filter(l -> {
            Student s = students.get(l.getStudentId());
            return s != null && s.getClassId() != null && classIds.contains(s.getClassId());
        }).toList();
    }

    private StudentLeave byId(List<StudentLeave> rows, Long id) {
        return rows.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
    }

    private boolean matchesKeyword(Map<String, Object> m, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String kw = q.trim();
        return String.valueOf(m.get("studentName")).contains(kw)
                || String.valueOf(m.get("studentNo")).contains(kw)
                || String.valueOf(m.get("className")).contains(kw);
    }

    private List<Map<String, Object>> toRows(List<StudentLeave> rows) {
        Map<Long, String> names = namesOf(rows);
        Map<Long, Student> students = studentsOf(rows);
        Map<Long, String> classNames = classNames(new ArrayList<>(students.values()));
        return rows.stream().map(l -> rowOf(l, names, students.get(l.getStudentId()), classNames)).collect(Collectors.toList());
    }

    private Map<String, Object> rowOf(StudentLeave l, Map<Long, String> names, Student s, Map<Long, String> classNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("studentId", l.getStudentId());
        m.put("studentName", s == null ? "(学生已删除)" : s.getName());
        m.put("studentNo", s == null ? null : s.getStudentNo());
        m.put("className", s == null || s.getClassId() == null ? null : classNames.get(s.getClassId()));
        m.put("leaveType", l.getLeaveType());
        m.put("startTime", l.getStartTime() == null ? null : fmt(l.getStartTime()));
        m.put("endTime", l.getEndTime() == null ? null : fmt(l.getEndTime()));
        m.put("durationDays", l.getDurationDays());
        m.put("status", l.getStatus());
        m.put("stepLabel", stepLabel(l));
        m.put("totalStep", l.getTotalStep());
        m.put("currentStep", l.getCurrentStep());
        m.put("creatorName", l.getCreatorId() == null ? "" : names.getOrDefault(l.getCreatorId(), ""));
        m.put("approverName", l.getApproverId() == null ? "" : names.getOrDefault(l.getApproverId(), ""));
        m.put("approveNote", l.getApproveNote() == null ? "" : l.getApproveNote());
        m.put("approveTime", l.getApproveTime());
        m.put("leaveTime", l.getLeaveTime());
        m.put("returnTime", l.getReturnTime());
        m.put("createTime", l.getCreateTime());
        m.put("reason", l.getReason());
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < parsePhotos(l.getPhotos()).size(); i++) {
            urls.add("/api/student-leave/file/" + l.getId() + "?idx=" + i); // 前端 fetchBlob 直取（同报修/微光）
        }
        m.put("photoCount", urls.size());
        m.put("photoUrls", urls);
        return m;
    }

    private String stepLabel(StudentLeave l) {
        String st = l.getStatus() == null ? "" : l.getStatus();
        if (StudentLeave.APPROVED.equals(st)) {
            return "已通过";
        }
        if (StudentLeave.REJECTED.equals(st)) {
            return "已驳回";
        }
        if (StudentLeave.CANCELLED.equals(st)) {
            return "已撤销";
        }
        return l.getCurrentStep() != null && l.getCurrentStep() == 3 ? "待书记终审"
                : l.getCurrentStep() != null && l.getCurrentStep() == 2 ? "待学成中心审批" : "待级长/级部主任审批";
    }

    private String rangeOf(StudentLeave l) {
        return (l.getStartTime() == null ? "" : fmt(l.getStartTime()))
                + " ~ " + (l.getEndTime() == null ? "" : fmt(l.getEndTime()));
    }

    /** 家长姓名不外显（行内只出登记人/审批人/门卫名），names 收集 creator/approver/guard id */
    private Map<Long, String> namesOf(List<StudentLeave> rows) {
        List<Long> ids = rows.stream()
                .flatMap(l -> java.util.stream.Stream.of(l.getCreatorId(), l.getApproverId(),
                        l.getLeaveGuardId(), l.getReturnGuardId()))
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
    }

    private Map<Long, Student> studentsOf(List<StudentLeave> rows) {
        List<Long> ids = rows.stream().map(StudentLeave::getStudentId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : studentMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Student::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, String> classNames(List<Student> students) {
        List<Long> ids = students.stream().map(Student::getClassId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : clazzMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Clazz::getId, Clazz::getName, (a, b) -> a));
    }

    private List<String> uploadPhotos(List<MultipartFile> photos) {
        List<String> objects = new ArrayList<>();
        if (photos == null) {
            return objects;
        }
        if (photos.size() > 3) {
            throw new BizException(400, "凭证照片最多 3 张");
        }
        for (MultipartFile p : photos) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (p.getSize() > MAX_SIZE) {
                throw new BizException(400, "照片不能超过 10MB");
            }
            String original = p.getOriginalFilename() == null ? "" : p.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
                throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
            }
            byte[] bytes;
            try {
                bytes = p.getBytes();
            } catch (Exception e) {
                throw new BizException(400, "读取照片失败");
            }
            String objectName = "student_leave/" + UUID.randomUUID() + "." + ext;
            pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, p.getContentType());
            objects.add(objectName);
        }
        return objects;
    }

    private List<String> parsePhotos(String photos) {
        if (photos == null || photos.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(photos, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(500, "照片清单序列化失败");
        }
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : DT.format(t);
    }
}
