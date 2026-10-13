package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.Grid;
import com.aischool.server.entity.Indicator;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Subject;
import com.aischool.server.entity.Term;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.GridMapper;
import com.aischool.server.mapper.IndicatorMapper;
import com.aischool.server.mapper.SubjectMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.DataScopeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 下拉数据：学期 / 我的班级（按数据权限过滤）/ 年级 / 九维 / 二级指标 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    private final TermMapper termMapper;
    private final ClazzMapper clazzMapper;
    private final StudentMapper studentMapper;
    private final GradeMapper gradeMapper;
    private final GridMapper gridMapper;
    private final IndicatorMapper indicatorMapper;
    private final SubjectMapper subjectMapper;
    private final UserMapper userMapper;
    private final DataScopeService dataScopeService;

    @GetMapping("/terms")
    public ApiResponse<List<Term>> terms() {
        return ApiResponse.ok(termMapper.selectList(
                new LambdaQueryWrapper<Term>().orderByDesc(Term::getIsCurrent).orderByDesc(Term::getId)));
    }

    @GetMapping("/grades")
    public ApiResponse<List<Grade>> grades() {
        return ApiResponse.ok(gradeMapper.selectList(
                new LambdaQueryWrapper<Grade>().orderByAsc(Grade::getId)));
    }

    @GetMapping("/grids")
    public ApiResponse<List<Grid>> grids() {
        return ApiResponse.ok(gridMapper.selectList(
                new LambdaQueryWrapper<Grid>().orderByAsc(Grid::getSort)));
    }

    /** 评价录入用二级指标（过滤种子占位的空名指标） */
    @GetMapping("/indicators")
    public ApiResponse<List<Indicator>> indicators(@RequestParam(required = false) Long gridId) {
        return ApiResponse.ok(indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(gridId != null, Indicator::getGridId, gridId)
                .ne(Indicator::getName, "")
                .orderByAsc(Indicator::getId)));
    }

    /** 科目（建考试 / 任课关系选择用） */
    @GetMapping("/subjects")
    public ApiResponse<List<Subject>> subjects() {
        return ApiResponse.ok(subjectMapper.selectList(
                new LambdaQueryWrapper<Subject>().orderByAsc(Subject::getSort)));
    }

    /** 在职教职员下拉（批49① 采购验收人选择用）：非家长启用账号，仅 id+姓名，无敏感列 */
    @GetMapping("/teachers")
    public ApiResponse<List<Map<String, Object>>> teachers() {
        return ApiResponse.ok(userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 1).ne(User::getRole, "PARENT")
                        .orderByAsc(User::getRealName))
                .stream().map(u -> Map.<String, Object>of("id", u.getId(), "realName", u.getRealName()))
                .toList());
    }

    @GetMapping("/my-classes")
    public ApiResponse<List<Map<String, Object>>> myClasses() {
        var user = AuthUtil.current();
        List<Long> visible = dataScopeService.visibleClassIds(user);
        List<Clazz> list = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                // 空列表须转哨兵：MyBatis-Plus .in(空集合) 生成 IN () 非法 SQL（无班教师 500）
                .in(visible != null, Clazz::getId, visible == null || visible.isEmpty() ? List.of(-1L) : visible)
                .orderByAsc(Clazz::getId));
        return ApiResponse.ok(list.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("gradeId", c.getGradeId());
            return m;
        }).toList());
    }

    /** 批56③ 素养评价全体教职工：全校班级下拉（不按任课/数据面过滤；仅拒家长/门卫） */
    @GetMapping("/all-classes")
    public ApiResponse<List<Map<String, Object>>> allClasses() {
        checkTeacherSide();
        return ApiResponse.ok(clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId))
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("gradeId", c.getGradeId());
                    return m;
                }).toList());
    }

    /** 批56③ 素养评价选学生：按班拉在读学生（仅 id+姓名，按学号序；教师侧全校班） */
    @GetMapping("/class-students")
    public ApiResponse<List<Map<String, Object>>> classStudents(@RequestParam Long classId) {
        checkTeacherSide();
        if (clazzMapper.selectById(classId) == null) {
            throw new BizException(404, "班级不存在");
        }
        return ApiResponse.ok(studentMapper.selectList(new LambdaQueryWrapper<Student>()
                        .eq(Student::getClassId, classId)
                        .eq(Student::getStatus, "在读")
                        .orderByAsc(Student::getStudentNo))
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    return m;
                }).toList());
    }

    /** 教师侧守卫：仅拒家长/门卫（批56③ 素养评价口径，同 StudentController） */
    private void checkTeacherSide() {
        String role = AuthUtil.current().role();
        if ("PARENT".equals(role) || "GUARD".equals(role)) {
            throw new BizException(403, "仅教职工可使用该功能");
        }
    }
}
