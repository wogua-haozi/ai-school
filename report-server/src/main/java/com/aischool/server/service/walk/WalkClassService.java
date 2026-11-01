package com.aischool.server.service.walk;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Subject;
import com.aischool.server.entity.User;
import com.aischool.server.entity.WalkClass;
import com.aischool.server.entity.WalkClassStudent;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.SubjectMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.mapper.WalkClassMapper;
import com.aischool.server.mapper.WalkClassStudentMapper;
import com.aischool.server.service.excel.ExcelWalkHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 走班（教学班，批75）：行政班不动，某年级某学科的学生重排。
 * 维护通道=管理端 Excel 全量替换（换班=传新分班表，先预览差异再确认）——
 * 文件里的 (年级,学科) 对替换库中该对的全部走班与映射，其他学科的走班不动。
 */
@Service
@RequiredArgsConstructor
public class WalkClassService {

    private final WalkClassMapper walkClassMapper;
    private final WalkClassStudentMapper walkStudentMapper;
    private final GradeMapper gradeMapper;
    private final SubjectMapper subjectMapper;
    private final UserMapper userMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final ExcelWalkHelper excelWalk;

    /** 校验通过的一行走班定义（老师分工 sheet 一行 = 一个走班） */
    public record NewWalk(int rowNum, Long gradeId, Long subjectId, String name,
                          Long teacherId, String teacherName, List<Long> studentIds) {}

    /** 解析+全量校验；errors 非空时不可预览/导入（逐行列出原因） */
    public Validated parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择 Excel 文件");
        }
        ExcelWalkHelper.Parsed p;
        try (InputStream in = file.getInputStream()) {
            p = excelWalk.read(in);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (p.teachers().isEmpty()) {
            throw new BizException(400, "「老师分工」表没有数据行（每个走班须一行：年级/学科/走班名称/教师）");
        }
        Map<String, Long> gradeIds = gradeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grade::getName, Grade::getId, (a, b) -> a));
        Map<String, Long> subjectIds = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getName, Subject::getId, (a, b) -> a));
        // 教师按账号或姓名匹配（仅教工；姓名重名须改填账号，同任课导入口径）
        List<User> staff = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getRole, List.of("ADMIN", "LEADER", "HEAD_TEACHER", "TEACHER", "GRADE_LEADER",
                        "DIRECTOR", "DORM", "SECRETARY")).eq(User::getStatus, 1));
        Map<String, User> byUsername = staff.stream()
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));
        Map<String, List<User>> byName = staff.stream()
                .collect(Collectors.groupingBy(User::getRealName));

        List<Map<String, Object>> errors = new ArrayList<>();
        // 走班定义：按 (grade,subject,name) 唯一；同名走班只许一个老师
        Map<String, NewWalk> walks = new LinkedHashMap<>();
        for (ExcelWalkHelper.TeacherRow r : p.teachers()) {
            String reason = null;
            Long gradeId = null;
            Long subjectId = null;
            User teacher = byUsername.get(r.teacher());
            if (r.walkName().isBlank()) {
                reason = "走班名称为空";
            }
            if (reason == null && !gradeIds.containsKey(r.gradeName())) {
                reason = "年级不存在（须与系统年级名一致，如「初一」）: " + r.gradeName();
            } else {
                gradeId = gradeIds.get(r.gradeName());
            }
            if (reason == null && !subjectIds.containsKey(r.subjectName())) {
                reason = "学科不存在（须与系统学科名完全一致）: " + r.subjectName();
            } else {
                subjectId = subjectIds.get(r.subjectName());
            }
            if (reason == null) {
                if (teacher == null) {
                    List<User> named = byName.get(r.teacher());
                    if (named == null) {
                        reason = "教师不存在（账号或姓名都未匹配到）: " + r.teacher();
                    } else if (named.size() > 1) {
                        reason = "教师姓名重名，请改填登录账号: " + r.teacher();
                    } else {
                        teacher = named.get(0);
                    }
                }
            }
            if (reason != null) {
                errors.add(Map.of("sheet", "老师分工", "row", r.rowNum(), "reason", reason));
                continue;
            }
            String key = gradeId + "#" + subjectId + "#" + r.walkName();
            NewWalk dup = walks.get(key);
            if (dup != null && !dup.teacherId().equals(teacher.getId())) {
                errors.add(Map.of("sheet", "老师分工", "row", r.rowNum(),
                        "reason", "走班「" + r.walkName() + "」在表中出现多次且教师不同"));
                continue;
            }
            if (dup == null) {
                walks.put(key, new NewWalk(r.rowNum(), gradeId, subjectId, r.walkName(),
                        teacher.getId(), teacher.getRealName(), new ArrayList<>()));
            }
        }
        // 学生分班：按学号匹配；须属该走班年级；每生每学科只出现一次
        Map<String, Student> byNo = studentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Student::getStudentNo, Function.identity(), (a, b) -> a));
        Map<Long, String> classGrade = clazzMapper.selectList(null).stream()
                .filter(c -> c.getGradeId() != null)
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getGradeId().toString(), (a, b) -> a));
        Map<Long, String> seenStudent = new LinkedHashMap<>(); // studentId → 走班名（同学科重复即报错）
        for (ExcelWalkHelper.StudentRow r : p.students()) {
            String reason = null;
            NewWalk target = null;
            if (r.studentNo().isBlank()) {
                reason = "学号为空";
            }
            if (reason == null) {
                Map<String, NewWalk> hits = walks.entrySet().stream()
                        .filter(e -> e.getValue().name().equals(r.walkName()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                if (hits.isEmpty()) {
                    reason = "走班「" + r.walkName() + "」未在「老师分工」表中定义";
                } else if (hits.size() > 1) {
                    reason = "走班名称「" + r.walkName() + "」在多个年级/学科下重名，请联系管理员改名";
                } else {
                    target = hits.values().iterator().next();
                }
            }
            Student st = reason == null && !r.studentNo().isBlank() ? byNo.get(r.studentNo()) : null;
            if (reason == null && st == null) {
                reason = "学号不存在: " + r.studentNo() + "（学生未入库则无法跟踪，请先在「学生管理」导入该生）";
            }
            if (reason == null && !st.getName().equals(r.name())) {
                reason = "学号与姓名不符: 学号 " + r.studentNo() + " 是「" + st.getName() + "」，表里填的是「" + r.name() + "」";
            }
            if (reason == null) {
                String gOfStudent = classGrade.get(st.getClassId() == null ? -1L : st.getClassId());
                if (!target.gradeId().toString().equals(gOfStudent)) {
                    reason = "学生「" + st.getName() + "」（" + r.studentNo() + "）不在走班所属年级，请核对该行走班名称";
                }
            }
            if (reason == null) {
                String prev = seenStudent.put(st.getId(), target.name());
                if (prev != null) {
                    reason = "学生「" + st.getName() + "」（" + r.studentNo() + "）在表中出现多次（" + prev + "、" + target.name() + "），每生只能分一个走班";
                    seenStudent.put(st.getId(), prev);
                }
            }
            if (reason != null) {
                errors.add(Map.of("sheet", "学生分班", "row", r.rowNum(), "reason", reason));
                continue;
            }
            target.studentIds().add(st.getId());
        }
        return new Validated(new ArrayList<>(walks.values()), errors);
    }

    public record Validated(List<NewWalk> walks, List<Map<String, Object>> errors) {}

    /** 预览：errors 空 → 给出与库中现状的差异数字与抽样明细（不落库） */
    public Map<String, Object> preview(MultipartFile file) {
        Validated v = parse(file);
        if (!v.errors().isEmpty()) {
            return Map.of("ok", false, "errors", v.errors());
        }
        return diffOf(v);
    }

    /** 差异（按文件覆盖的 年级+学科 对逐对比较） */
    private Map<String, Object> diffOf(Validated v) {
        record Pair(Long gradeId, Long subjectId) {}
        Map<Pair, List<NewWalk>> byPair = v.walks().stream()
                .collect(Collectors.groupingBy(w -> new Pair(w.gradeId(), w.subjectId()), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> scopes = new ArrayList<>();
        for (var en : byPair.entrySet()) {
            Pair pair = en.getKey();
            List<NewWalk> news = en.getValue();
            Map<String, WalkClass> oldByName = walkClassMapper.selectList(new LambdaQueryWrapper<WalkClass>()
                            .eq(WalkClass::getGradeId, pair.gradeId()).eq(WalkClass::getSubjectId, pair.subjectId()))
                    .stream().collect(Collectors.toMap(WalkClass::getName, Function.identity(), (a, b) -> a));
            // 现状 sid→走班名
            Map<Long, String> oldStudent = new LinkedHashMap<>();
            if (!oldByName.isEmpty()) {
                for (WalkClass w : oldByName.values()) {
                    for (WalkClassStudent s : walkStudentMapper.selectList(new LambdaQueryWrapper<WalkClassStudent>()
                            .eq(WalkClassStudent::getWalkClassId, w.getId()))) {
                        oldStudent.put(s.getStudentId(), w.getName());
                    }
                }
            }
            int teacherChanged = 0;
            List<String> removed = new ArrayList<>();
            for (WalkClass old : oldByName.values()) {
                NewWalk n = news.stream().filter(x -> x.name().equals(old.getName())).findFirst().orElse(null);
                if (n == null) {
                    removed.add(old.getName());
                } else if (!Objects.equals(old.getTeacherId(), n.teacherId())) {
                    teacherChanged++;
                }
            }
            Map<Long, String> newStudent = new LinkedHashMap<>();
            for (NewWalk n : news) {
                for (Long sid : n.studentIds()) {
                    newStudent.put(sid, n.name());
                }
            }
            long moved = newStudent.keySet().stream()
                    .filter(sid -> oldStudent.containsKey(sid) && !oldStudent.get(sid).equals(newStudent.get(sid))).count();
            long newly = newStudent.keySet().stream().filter(sid -> !oldStudent.containsKey(sid)).count();
            long unassigned = oldStudent.keySet().stream().filter(sid -> !newStudent.containsKey(sid)).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gradeId", pair.gradeId());
            m.put("subjectId", pair.subjectId());
            m.put("walkCount", news.size());
            m.put("studentCount", newStudent.size());
            m.put("newWalks", news.stream().map(NewWalk::name)
                    .filter(n -> !oldByName.containsKey(n)).toList());
            m.put("removedWalks", removed);
            m.put("teacherChanged", teacherChanged);
            m.put("studentMoved", moved);
            m.put("studentNewlyAssigned", newly);
            m.put("studentUnassigned", unassigned);
            scopes.add(m);
        }
        return Map.of("ok", true, "scopes", scopes);
    }

    /** 确认导入：按 (年级,学科) 对整体替换（先清旧走班与映射再全量重插，事务内） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> commit(MultipartFile file) {
        Validated v = parse(file);
        if (!v.errors().isEmpty()) {
            Map<String, Object> first = v.errors().get(0);
            throw new BizException(400, "表内有 " + v.errors().size() + " 行问题，整体未导入（第 "
                    + first.get("row") + " 行[" + first.get("sheet") + "]：" + first.get("reason") + "）");
        }
        Map<String, Object> diff = diffOf(v);
        int removedWalks = 0;
        for (NewWalk n : v.walks()) {
            List<WalkClass> olds = walkClassMapper.selectList(new LambdaQueryWrapper<WalkClass>()
                    .eq(WalkClass::getGradeId, n.gradeId()).eq(WalkClass::getSubjectId, n.subjectId()));
            for (WalkClass old : olds) {
                walkStudentMapper.delete(new LambdaQueryWrapper<WalkClassStudent>()
                        .eq(WalkClassStudent::getWalkClassId, old.getId()));
                walkClassMapper.deleteById(old.getId());
                removedWalks++;
            }
        }
        int students = 0;
        for (NewWalk n : v.walks()) {
            WalkClass w = new WalkClass();
            w.setGradeId(n.gradeId());
            w.setSubjectId(n.subjectId());
            w.setName(n.name());
            w.setTeacherId(n.teacherId());
            walkClassMapper.insert(w);
            for (Long sid : n.studentIds()) {
                WalkClassStudent s = new WalkClassStudent();
                s.setWalkClassId(w.getId());
                s.setStudentId(sid);
                walkStudentMapper.insert(s);
                students++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(diff);
        out.put("removedOldWalks", removedWalks);
        out.put("insertedWalks", v.walks().size());
        out.put("insertedStudents", students);
        return out;
    }

    // ───────────────── 查询小件（成绩/微光/Meta 用） ─────────────────

    public WalkClass requireWalk(Long id) {
        WalkClass w = walkClassMapper.selectById(id);
        if (w == null) {
            throw new BizException(404, "走班不存在");
        }
        return w;
    }

    /** 我教的走班（App 下拉/成绩入口用） */
    public List<WalkClass> byTeacher(Long userId) {
        return walkClassMapper.selectList(new LambdaQueryWrapper<WalkClass>()
                .eq(WalkClass::getTeacherId, userId).orderByAsc(WalkClass::getId));
    }

    /** 走班名册（按学号序，含行政班 id 供分组/展示） */
    public List<Student> walkRoster(Long walkClassId) {
        List<Long> sids = walkStudentMapper.selectList(new LambdaQueryWrapper<WalkClassStudent>()
                        .eq(WalkClassStudent::getWalkClassId, walkClassId))
                .stream().map(WalkClassStudent::getStudentId).toList();
        if (sids.isEmpty()) {
            return List.of();
        }
        return studentMapper.selectBatchIds(sids).stream()
                .sorted(java.util.Comparator.comparing(Student::getStudentNo))
                .toList();
    }

    /** 某学科的学生→走班映射（成绩排名按走班分组用；无走班=空表） */
    public Map<Long, Long> studentWalkOf(Long subjectId) {
        List<WalkClass> walks = walkClassMapper.selectList(new LambdaQueryWrapper<WalkClass>()
                .eq(WalkClass::getSubjectId, subjectId));
        if (walks.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> of = new LinkedHashMap<>();
        for (WalkClass w : walks) {
            for (WalkClassStudent s : walkStudentMapper.selectList(new LambdaQueryWrapper<WalkClassStudent>()
                    .eq(WalkClassStudent::getWalkClassId, w.getId()))) {
                of.put(s.getStudentId(), w.getId());
            }
        }
        return of;
    }

    /** 管理端列表：全部走班（附年级/学科/教师名与人数） */
    public List<Map<String, Object>> listAll() {
        List<WalkClass> walks = walkClassMapper.selectList(
                new LambdaQueryWrapper<WalkClass>().orderByAsc(WalkClass::getGradeId)
                        .orderByAsc(WalkClass::getSubjectId).orderByAsc(WalkClass::getId));
        if (walks.isEmpty()) {
            return List.of();
        }
        Map<Long, String> gradeNames = gradeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName));
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName));
        Map<Long, String> teacherNames = userMapper.selectBatchIds(
                        walks.stream().map(WalkClass::getTeacherId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        Map<Long, Long> counts = walkStudentMapper.selectList(new LambdaQueryWrapper<WalkClassStudent>()
                        .in(WalkClassStudent::getWalkClassId, walks.stream().map(WalkClass::getId).toList()))
                .stream().collect(Collectors.groupingBy(WalkClassStudent::getWalkClassId, Collectors.counting()));
        return walks.stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            m.put("gradeId", w.getGradeId());
            m.put("gradeName", gradeNames.get(w.getGradeId()));
            m.put("subjectId", w.getSubjectId());
            m.put("subjectName", subjectNames.get(w.getSubjectId()));
            m.put("teacherId", w.getTeacherId());
            m.put("teacherName", teacherNames.get(w.getTeacherId()));
            m.put("studentCount", counts.getOrDefault(w.getId(), 0L));
            return m;
        }).toList();
    }

    /** 管理端：某走班名册（学号/姓名/行政班名） */
    public List<Map<String, Object>> rosterView(Long walkClassId) {
        WalkClass w = requireWalk(walkClassId);
        List<Student> roster = walkRoster(walkClassId);
        if (roster.isEmpty()) {
            return List.of();
        }
        Map<Long, String> classNames = clazzMapper.selectBatchIds(roster.stream()
                        .map(Student::getClassId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getName(), (a, b) -> a));
        return roster.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getId());
            m.put("studentNo", s.getStudentNo());
            m.put("name", s.getName());
            m.put("className", classNames.getOrDefault(s.getClassId(), "—"));
            return m;
        }).toList();
    }
}
