package com.aischool.server.service.auth;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.GradeBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Teach;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.GradeBindingMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.TeachMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据权限（角色隔离）：
 * - ADMIN：全校
 * - LEADER（领导）：全校可见；教师侧功能与 ADMIN 同口径放行（批3.5 领导教师化），成绩录入除外（批4）；
 *   设分管学段（t_user.stage_scope，批41 全数据面）→ 教学数据范围收缩为该学段班级（年级未标学段=不限，安全默认不锁人）
 * - DIRECTOR（学成中心主任）：全校（批32；请假终审等走模块内校验）
 * - SECRETARY（书记，批39④）：全校（教师壳，请假终审；成绩学段门走 ScoreService）
 * - DORM（生活老师）：全校（批32 先全校；甲方分年级方案到位后改按 t_grade_binding 收紧）
 * - GRADE_LEADER / HEAD_TEACHER / TEACHER / PROCUREMENT：批51b 一人多岗权限并集——
 *   任课班级（t_teach）∪ 挂名班主任班级（t_class.head_teacher_id）∪ 绑定年级班级（t_grade_binding）。
 *   级长也教书、班主任兼他班任课、普通教师挂名班主任（组织架构可选）等叠加身份全部生效；
 *   各身份由校方管理端自助叠加（角色/挂名/任课/年级绑定四个入口），无需开发介入。
 * - PARENT（家长）：刻意不走本服务——落到 default 即 403，家长一律使用 /api/parent/* 白名单接口
 */
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private final ClazzMapper clazzMapper;
    private final StudentMapper studentMapper;
    private final TeachMapper teachMapper;
    private final GradeBindingMapper gradeBindingMapper;
    private final GradeMapper gradeMapper;
    private final UserMapper userMapper;

    /** 当前用户可见的班级 id 列表；null 表示不受限（管理员） */
    public List<Long> visibleClassIds(UserPrincipal user) {
        return switch (user.role()) {
            case "ADMIN", "DIRECTOR", "DORM", "SECRETARY" -> null; // 书记（批39④）教师壳：全校
            case "LEADER" -> {
                // 批41：分管学段全数据面——教学类功能（评价/报告/微光/谈心/银行/荣誉等）收缩为该学段班级；
                // 成绩线另有 ScoreService 学段门（口径一致）；请假线行政豁免走 leaveVisible
                String scope = stageScopeOf(user);
                yield scope == null ? null : stageClassIds(scope);
            }
            case "GRADE_LEADER", "HEAD_TEACHER", "TEACHER", "PROCUREMENT" -> multiDutyClassIds(user);
            case "LOGISTICS", "REPAIRER" -> List.of(); // 批63 报修流转角色：无班级数据面（工作台走 /api/repair/workbench）
            default -> throw new BizException(403, "未知角色: " + user.role());
        };
    }

    /** 批51b 一人多岗：任课班 ∪ 挂名班主任班 ∪ 绑定年级班（并集，详见类注释） */
    private List<Long> multiDutyClassIds(UserPrincipal user) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        teachMapper.selectList(new LambdaQueryWrapper<Teach>()
                .eq(Teach::getTeacherId, user.userId())).forEach(t -> ids.add(t.getClassId()));
        clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                .eq(Clazz::getHeadTeacherId, user.userId())).forEach(c -> ids.add(c.getId()));
        List<Long> gradeIds = gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                        .eq(GradeBinding::getUserId, user.userId())
                        .eq(GradeBinding::getDuty, GradeBinding.DUTY_GRADE_LEADER))
                .stream().map(GradeBinding::getGradeId).distinct().toList();
        if (!gradeIds.isEmpty()) {
            clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().in(Clazz::getGradeId, gradeIds))
                    .forEach(c -> ids.add(c.getId()));
        }
        ids.remove(null);
        return List.copyOf(ids);
    }

    /** 校验当前用户可访问指定学生，返回学生实体 */
    public Student checkStudentAccess(UserPrincipal user, Long studentId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(404, "学生不存在");
        }
        List<Long> visible = visibleClassIds(user);
        if (visible != null && !visible.contains(student.getClassId())) {
            throw new BizException(403, "无权访问该学生（数据权限隔离）");
        }
        return student;
    }

    /** 校验当前用户可对指定班级发起操作（管理员/领导全校放行，或该班班主任；综合素质/荣誉/微光/报告等写路径统一收口于此）。
     *  批51b 挂名即班主任：t_class.head_teacher_id 挂到谁名下谁即可操作，不问账号角色（级长/普通教师挂名班主任等一人多岗场景） */
    public void checkClassOperable(UserPrincipal user, Long classId) {
        if ("ADMIN".equals(user.role())) {
            return;
        }
        if ("LEADER".equals(user.role()) || "SECRETARY".equals(user.role())) { // 批64 书记与领导平权（书记无学段配置=全校）
            String scope = stageScopeOf(user);
            if (scope == null || stageMatch(scope, classId)) { // 批41 学段门（与成绩线同口径）
                return;
            }
            throw new BizException(403, "该领导分管" + stageName(scope) + "，无权操作另一学段的班级");
        }
        Clazz clazz = clazzMapper.selectById(classId);
        if (clazz != null && user.userId().equals(clazz.getHeadTeacherId())) {
            return;
        }
        throw new BizException(403, "只有管理员或该班班主任可执行此操作");
    }

    /** 批54① 微光时刻口径：全体老师可给自己「可见」班级拍微光——任课班∪挂名班主任班∪年级绑定班
        （与 /api/meta/my-classes 下拉同源 visibleClassIds，前后端口径一致）；管理员/学成/书记/生活老师
        全校、领导限分管学段。不再要求班主任身份（原走 checkClassOperable，任课老师提交 403） */
    public void checkClassTeachable(UserPrincipal user, Long classId) {
        List<Long> visible = visibleClassIds(user);
        if (visible == null || visible.contains(classId)) {
            return;
        }
        throw new BizException(403, "仅可为自己任课或负责的班级记录微光");
    }

    // ───────── 批41：分管学段（教学数据面）─────────

    /** 领导分管学段（t_user.stage_scope；非领导/未设置=null 即不受限） */
    public String stageScopeOf(UserPrincipal user) {
        if (!"LEADER".equals(user.role())) {
            return null;
        }
        User u = userMapper.selectById(user.userId());
        return u != null && u.getStageScope() != null && !u.getStageScope().isBlank() ? u.getStageScope() : null;
    }

    /** 学段匹配（scope 须覆盖班级学段；年级未标 stage=null 视为不受限） */
    public boolean stageMatch(String scope, Long classId) {
        if (scope == null) {
            return true;
        }
        Clazz c = clazzMapper.selectById(classId);
        if (c == null || c.getGradeId() == null) {
            return true;
        }
        Grade g = gradeMapper.selectById(c.getGradeId());
        return g == null || g.getStage() == null || scope.equals(g.getStage());
    }

    /** 某学段的班级 id 列表（年级未标学段的班级计入，安全默认不锁人） */
    public List<Long> stageClassIds(String scope) {
        List<Long> gradeIds = gradeMapper.selectList(null).stream()
                .filter(g -> g.getStage() == null || scope.equals(g.getStage()))
                .map(Grade::getId).toList();
        return gradeIds.isEmpty() ? List.of()
                : clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().in(Clazz::getGradeId, gradeIds))
                        .stream().map(Clazz::getId).toList();
    }

    public String stageName(String scope) {
        return "PRIMARY".equals(scope) ? "小学部" : "初中部";
    }
}
