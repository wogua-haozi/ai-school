package com.aischool.server.service.health;

import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.TeacherProfile;
import com.aischool.server.entity.Teach;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.TeacherProfileMapper;
import com.aischool.server.mapper.TeachMapper;
import com.aischool.server.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据体检（批8.5；批29 抽服务）：一次性扫描常见数据问题，管理端页签与体检周报共用。
 * 全表扫描规模（500 用户/4000 学生）内存一次跑完，毫秒级。
 */
@Service
@RequiredArgsConstructor
public class HealthScanService {

    /** 批63 学号规则（同期末考号）：年份2位+年级1位+校码363+班号2位+座位号2位，如 2773630101 */
    private static final Pattern STUDENT_NO = Pattern.compile("^2[0-9]363[0-9]{4}$");

    private final UserMapper userMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final TeachMapper teachMapper;
    private final TeacherProfileMapper teacherProfileMapper;
    private final ParentBindingMapper bindingMapper;

    @Data
    public static class IssueItem {
        private String label; // 主文案（姓名/班级）
        private String sub;   // 次要信息
        private String kw;    // 直达修复的搜索关键词（可空）
    }

    @Data
    public static class IssueGroup {
        private String key;
        private String title;
        private String level; // danger / warning
        private long count;
        private String note;  // 修复指引
        private String target; // 直达页签：teacher/student/org/parent
        private List<IssueItem> items; // 样例（封顶展示）
    }

    private static IssueGroup group(String key, String title, String level, String note, String target) {
        IssueGroup g = new IssueGroup();
        g.setKey(key);
        g.setTitle(title);
        g.setLevel(level);
        g.setNote(note);
        g.setTarget(target);
        g.setItems(new ArrayList<>());
        return g;
    }

    private static IssueItem item(String label, String sub, String kw) {
        IssueItem i = new IssueItem();
        i.setLabel(label);
        i.setSub(sub);
        i.setKw(kw);
        return i;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public List<IssueGroup> scan() {

        // ── 教师侧：缺手机号 / 无班无课非职员 ──
        List<User> staff = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getRole, List.of("ADMIN", "LEADER", "HEAD_TEACHER", "TEACHER")));
        IssueGroup gPhone = group("teacherPhone", "教师缺手机号", "warning",
                "影响自助换绑与联系；教师在「我的-修改手机号」可自助补，或此处编辑补录", "teacher");
        for (User u : staff) {
            if (blank(u.getPhone())) {
                gPhone.getItems().add(item(u.getRealName(), u.getUsername() + " · " + roleName(u.getRole()), u.getUsername()));
            }
        }
        gPhone.setCount(gPhone.getItems().size());

        List<Long> teachIds = teachMapper.selectList(null).stream()
                .map(Teach::getTeacherId).distinct().toList();
        List<Long> headIds = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                        .isNotNull(Clazz::getHeadTeacherId)).stream()
                .map(Clazz::getHeadTeacherId).toList();
        Map<Long, String> dutyByUser = teacherProfileMapper.selectList(null).stream()
                .filter(p -> !blank(p.getDuty()))
                .collect(Collectors.toMap(TeacherProfile::getUserId, TeacherProfile::getDuty, (a, b) -> a));
        IssueGroup gIdle = group("teacherIdle", "教师无班无课且无职务", "warning",
                "既无任课也不带班、档案也无职务记录；任课关系尚未导入的教师也会列在此——校方拿到任课表后可在「教师与任课」页签『导入任课』批量上传；职工（花工/电工/饭堂等）有职务属正常不算", "teacher");
        for (User u : staff) {
            if (("TEACHER".equals(u.getRole()) || "HEAD_TEACHER".equals(u.getRole()))
                    && !teachIds.contains(u.getId()) && !headIds.contains(u.getId())
                    && blank(dutyByUser.get(u.getId()))) {
                gIdle.getItems().add(item(u.getRealName(), u.getUsername() + " · " + roleName(u.getRole()), u.getUsername()));
            }
        }
        gIdle.setCount(gIdle.getItems().size());

        // ── 学生侧：一次全量读，格式/班内重复/宿舍/家长绑定同趟算 ──
        List<Student> students = studentMapper.selectList(null);
        Map<Long, String> classNames = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName, (a, b) -> a));
        IssueGroup gNoBad = group("studentNoBad", "学号格式异常", "danger",
                "应为学生标识(小1/初2)+入学年2位+班号2位+编号2位共 7 位；在学生页签编辑修正", "student");
        for (Student s : students) {
            if (s.getStudentNo() == null || !STUDENT_NO.matcher(s.getStudentNo()).matches()) {
                gNoBad.getItems().add(item(s.getName(),
                        (s.getStudentNo() == null ? "（空）" : s.getStudentNo()) + " · " + classNames.getOrDefault(s.getClassId(), "?"),
                        s.getStudentNo()));
            }
        }
        gNoBad.setCount(gNoBad.getItems().size());

        IssueGroup gDup = group("studentNoDup", "班内学号重复", "danger",
                "同班同号会撞档案；在学生页签按学号搜索修正其一", "student");
        Map<String, List<Student>> byClassNo = students.stream()
                .filter(s -> !blank(s.getStudentNo()))
                .collect(Collectors.groupingBy(s -> s.getClassId() + "#" + s.getStudentNo()));
        for (List<Student> dup : byClassNo.values().stream()
                .filter(l -> l.size() > 1)
                .sorted((a, b) -> Integer.compare(b.size(), a.size())).toList()) {
            String names = dup.stream().map(Student::getName).collect(Collectors.joining("、"));
            gDup.getItems().add(item(classNames.getOrDefault(dup.get(0).getClassId(), "?") + " 学号 " + dup.get(0).getStudentNo(),
                    names + " · " + dup.size() + " 人", dup.get(0).getStudentNo()));
        }
        gDup.setCount(gDup.getItems().size());

        IssueGroup gDorm = group("dormMiss", "宿舍信息不全", "warning",
                "有楼无房/有房无楼/全空；寄宿生须补齐，非寄宿可忽略（学生页签编辑）", "student");
        for (Student s : students) {
            String b = s.getDormBuilding(), r = s.getDormRoom();
            boolean bad = (!blank(b) && blank(r)) || (blank(b) && !blank(r)) || (blank(b) && !blank(s.getDormBed()));
            if (bad) {
                String dorm = (!blank(b) ? b : "—") + " / " + (!blank(r) ? r : "—");
                gDorm.getItems().add(item(s.getName() + " · " + classNames.getOrDefault(s.getClassId(), "?"),
                        dorm + (blank(s.getDormBed()) ? "" : " / " + s.getDormBed() + "床"), s.getStudentNo()));
            }
        }
        gDorm.setCount(gDorm.getItems().size());

        IssueGroup gUnbound = group("studentNoParent", "在读学生无家长绑定", "warning",
                "家长端上线（自助注册）前属正常；上线后仍未绑定的学生按班生成邀请码补齐", "student");
        List<Long> boundStudentIds = bindingMapper.selectList(null).stream()
                .map(ParentBinding::getStudentId).distinct().toList();
        for (Student s : students) {
            if ("在读".equals(s.getStatus()) && !boundStudentIds.contains(s.getId())) {
                if (gUnbound.getItems().size() < 20) {
                    gUnbound.getItems().add(item(s.getName() + " · " + classNames.getOrDefault(s.getClassId(), "?"),
                            "学号 " + s.getStudentNo(), s.getStudentNo()));
                }
                gUnbound.setCount(gUnbound.getCount() + 1);
            }
        }

        // ── 班级：无班主任 ──
        IssueGroup gNoHead = group("classNoHead", "班级无班主任", "danger",
                "该班数据（评价/报告/家长重置）无人负责；在年级与班级页签指定", "org");
        for (Clazz c : clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().isNull(Clazz::getHeadTeacherId))) {
            gNoHead.getItems().add(item(c.getName(), "head_teacher_id 为空", null));
        }
        gNoHead.setCount(gNoHead.getItems().size());

        // ── 家长账号：有号无绑定（孤儿账号） ──
        IssueGroup gOrphan = group("parentNoBinding", "家长账号未绑定孩子", "warning",
                "账号存在但看不到任何孩子数据；在家长账号页签补绑或删除", "parent");
        List<Long> boundParents = bindingMapper.selectList(null).stream()
                .map(ParentBinding::getParentUserId).distinct().toList();
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getRole, "PARENT"))) {
            if (!boundParents.contains(u.getId())) {
                gOrphan.getItems().add(item(u.getRealName(), u.getUsername(), u.getUsername()));
            }
        }
        gOrphan.setCount(gOrphan.getItems().size());

        // 样例封顶 50（家长未绑定封顶 20 已控）
        for (IssueGroup g : List.of(gPhone, gIdle, gNoBad, gDup, gDorm, gNoHead, gOrphan)) {
            if (g.getItems().size() > 50) {
                g.setItems(g.getItems().subList(0, 50));
            }
        }

        return List.of(gPhone, gIdle, gNoBad, gDup, gDorm, gUnbound, gNoHead, gOrphan);
    }

    private static String roleName(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> "管理员";
            case "LEADER" -> "领导";
            case "HEAD_TEACHER" -> "班主任";
            case "TEACHER" -> "教师";
            default -> Objects.requireNonNullElse(role, "");
        };
    }
}
