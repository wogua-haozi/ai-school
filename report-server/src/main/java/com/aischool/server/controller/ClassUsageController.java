package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.GradeBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClassUsageMapper;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.GradeBindingMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批73c（第4/5条）：班主任/级长（级部主任同权=年级绑定 GRADE_LEADER/GRADE_DIRECTOR）看学生的填写情况。
 * 第4条 GET /teachers = 老师维度×各板块次数，只统计范围学生名下记录（同管理端统计口径，范围=挂名班 ∪ 绑定年级班）；
 * 第5条 GET /students = 本班每生×六项记录数（仅班主任=挂名班；登录=绑定家长登录次数）；
 * month=YYYY-MM 自然月，省略或 all=全时段累计。
 */
@RestController
@RequestMapping("/api/class-usage")
@RequiredArgsConstructor
public class ClassUsageController {

    private static final List<String> TOTAL_KEYS =
            List.of("moments", "evals", "scores", "activities", "talks", "honors");

    private final ClazzMapper clazzMapper;
    private final GradeBindingMapper gradeBindingMapper;
    private final StudentMapper studentMapper;
    private final UserMapper userMapper;
    private final ClassUsageMapper usageMapper;

    /** 入口显隐探测：homeroom=挂名班主任班（第5条）；managed=第4条范围（挂名班 ∪ 级长/级部主任绑定年级班） */
    @GetMapping("/scope")
    public ApiResponse<Map<String, Object>> scope() {
        var user = AuthUtil.current();
        checkTeacherSide();
        return ApiResponse.ok(Map.of(
                "homeroom", classNames(homeroomClasses(user.userId())),
                "managed", classNames(managedClasses(user.userId()))));
    }

    @GetMapping("/teachers")
    public ApiResponse<Map<String, Object>> teachers(@RequestParam(required = false) String month) {
        var user = AuthUtil.current();
        checkTeacherSide();
        List<Clazz> scope = managedClasses(user.userId());
        if (scope.isEmpty()) {
            throw new BizException(403, "仅班主任/级长可查看（您未挂名班主任且未绑定年级）");
        }
        LocalDateTime[] range = rangeOf(month);
        List<Long> ids = scope.stream().map(Clazz::getId).toList();
        // 六板块聚合 → 出现在任一聚合里的老师才成行（=期间确有填写）
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        merge(byUser, usageMapper.momentsByTeacher(ids, range[0], range[1]), "moments");
        merge(byUser, usageMapper.evalsByTeacher(ids, range[0], range[1]), "evals");
        merge(byUser, usageMapper.scoresByTeacher(ids, range[0], range[1]), "scores");
        merge(byUser, usageMapper.activitiesByTeacher(ids, range[0], range[1]), "activities");
        merge(byUser, usageMapper.talksByTeacher(ids, range[0], range[1]), "talks");
        merge(byUser, usageMapper.honorsByTeacher(ids, range[0], range[1]), "honors");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!byUser.isEmpty()) {
            Map<Long, User> users = userMapper.selectBatchIds(byUser.keySet()).stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
            for (var e : byUser.entrySet()) {
                User u = users.get(e.getKey());
                // 家长微光/家长上传不走「老师填写」口径，家长/监护人账号不成行
                if (u != null && ("PARENT".equals(u.getRole()) || "GUARD".equals(u.getRole()))) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>(e.getValue());
                r.put("teacherId", e.getKey());
                r.put("name", u == null ? "?" + e.getKey() : u.getRealName());
                r.put("role", u == null ? "" : u.getRole());
                long total = 0;
                for (String key : TOTAL_KEYS) {
                    total += (Long) r.get(key);
                }
                r.put("total", total);
                rows.add(r);
            }
            rows.sort((a, b) -> Long.compare((Long) b.get("total"), (Long) a.get("total")));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", monthLabel(month));
        data.put("scope", classNames(scope));
        data.put("rows", rows);
        return ApiResponse.ok(data);
    }

    @GetMapping("/students")
    public ApiResponse<Map<String, Object>> students(@RequestParam(required = false) String month) {
        var user = AuthUtil.current();
        checkTeacherSide();
        List<Clazz> scope = homeroomClasses(user.userId());
        if (scope.isEmpty()) {
            throw new BizException(403, "仅班主任可查看（您未挂名班主任）");
        }
        LocalDateTime[] range = rangeOf(month);
        List<Long> ids = scope.stream().map(Clazz::getId).toList();
        Map<Long, long[]> cnt = new LinkedHashMap<>();   // sid → [微光,评价,扬长,荣誉,谈心,登录]
        List<List<Map<String, Object>>> dims = List.of(
                usageMapper.momentsByStudent(ids, range[0], range[1]),
                usageMapper.evalsByStudent(ids, range[0], range[1]),
                usageMapper.activitiesByStudent(ids, range[0], range[1]),
                usageMapper.honorsByStudent(ids, range[0], range[1]),
                usageMapper.talksByStudent(ids, range[0], range[1]),
                usageMapper.loginsByStudent(ids, range[0], range[1]));
        for (int idx = 0; idx < dims.size(); idx++) {
            for (Map<String, Object> r : dims.get(idx)) {
                cnt.computeIfAbsent(((Number) r.get("sid")).longValue(), sid -> new long[6])[idx]
                        += ((Number) r.get("cnt")).longValue();
            }
        }
        // 全员成行（0 记录也展示，班主任要看谁没被填）
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Student s : studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .in(Student::getClassId, ids).orderByAsc(Student::getStudentNo))) {
            long[] c = cnt.getOrDefault(s.getId(), new long[6]);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("studentNo", s.getStudentNo());
            r.put("name", s.getName());
            r.put("moments", c[0]);
            r.put("evals", c[1]);
            r.put("activities", c[2]);
            r.put("honors", c[3]);
            r.put("talks", c[4]);
            r.put("logins", c[5]);
            rows.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", monthLabel(month));
        data.put("scope", classNames(scope));
        data.put("rows", rows);
        return ApiResponse.ok(data);
    }

    /* ───────────────────────── 内部 ───────────────────────── */

    private void checkTeacherSide() {
        String role = AuthUtil.current().role();
        if ("PARENT".equals(role) || "GUARD".equals(role)) {
            throw new BizException(403, "仅教职工可查询");
        }
    }

    /** 挂名班主任班（批51b：普通教师也可能挂名，按 t_class.head_teacher_id 认） */
    private List<Clazz> homeroomClasses(Long userId) {
        return clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                .eq(Clazz::getHeadTeacherId, userId).orderByAsc(Clazz::getId));
    }

    /** 第4条范围：挂名班 ∪ 级长/级部主任绑定年级班（同权拍板，DUTY_DORM 生活老师不含） */
    private List<Clazz> managedClasses(Long userId) {
        Set<Long> gradeIds = gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                        .eq(GradeBinding::getUserId, userId)
                        .in(GradeBinding::getDuty, GradeBinding.DUTY_GRADE_LEADER, GradeBinding.DUTY_GRADE_DIRECTOR))
                .stream().map(GradeBinding::getGradeId).collect(java.util.stream.Collectors.toSet());
        LinkedHashMap<Long, Clazz> byId = new LinkedHashMap<>();
        homeroomClasses(userId).forEach(c -> byId.put(c.getId(), c));
        if (!gradeIds.isEmpty()) {
            clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                            .in(Clazz::getGradeId, gradeIds).orderByAsc(Clazz::getId))
                    .forEach(c -> byId.putIfAbsent(c.getId(), c));
        }
        return List.copyOf(byId.values());
    }

    private List<Map<String, Object>> classNames(List<Clazz> classes) {
        return classes.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            return m;
        }).toList();
    }

    /** null/all=全时段；YYYY-MM=自然月 [初, 次月初) */
    private LocalDateTime[] rangeOf(String month) {
        if (month == null || month.isBlank() || "all".equals(month)) {
            return new LocalDateTime[]{null, null};
        }
        if (!month.matches("\\d{4}-\\d{2}")) {
            throw new BizException(400, "month 须为 YYYY-MM 或 all");
        }
        LocalDate first = LocalDate.parse(month + "-01");
        return new LocalDateTime[]{first.atStartOfDay(), first.plusMonths(1).atStartOfDay()};
    }

    private String monthLabel(String month) {
        return (month == null || month.isBlank() || "all".equals(month)) ? "全部" : month;
    }

    private void merge(Map<Long, Map<String, Object>> byUser, List<Map<String, Object>> data, String key) {
        for (Map<String, Object> r : data) {
            byUser.computeIfAbsent(((Number) r.get("userId")).longValue(), uid -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : TOTAL_KEYS) {
                    m.put(k, 0L);
                }
                return m;
            }).put(key, ((Number) r.get("cnt")).longValue());
        }
    }
}
