package com.aischool.server.service.score;

import com.aischool.server.common.BizException;
import com.aischool.server.common.Exported;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Exam;
import com.aischool.server.entity.ExamSubject;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.User;
import com.aischool.server.entity.Score;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Subject;
import com.aischool.server.entity.Term;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ExamMapper;
import com.aischool.server.mapper.ExamSubjectMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.ScoreMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.SubjectMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.excel.ExcelScoreHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成绩管理：考试（含科目满分）→ 按任课关系录入 → 全体竞争排名（同分同名次）→ 回填单科/总分最高分。
 * 排名纯 Java 内存计算，零自定义 SQL；报告契约不读排名列，重算零契约风险。
 * 批49 甲方口径：教师/班主任全端点关闭；可传可改=ADMIN/书记（全校）+级长（绑定年级，受 entry_open 约束）；
 * 查看=ADMIN/书记（全校）+级长（绑定年级）+校级领导（分管学段，只读）+SCORE_ALL 权限点（全校，只读）。
 * t_exam.entry_open 仅约束级长录入；查看不受窗口影响。
 */
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final ExamMapper examMapper;
    private final ExamSubjectMapper examSubjectMapper;
    private final ScoreMapper scoreMapper;
    private final StudentMapper studentMapper;
    private final SubjectMapper subjectMapper;
    private final TermMapper termMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final UserMapper userMapper;
    private final DataScopeService dataScope;
    private final PermissionService permissionService;
    private final ExcelScoreHelper excel;

    // ───────────────── 考试 ─────────────────

    /** 建考试（批49 随上传权限收紧：ADMIN/书记/级长）：一次带齐科目与满分 */
    public Long createExam(UserPrincipal user, Long termId, String name, LocalDate examDate,
                           List<SubjectReq> subjects) {
        requireScoreUpload(user);
        Term term = termMapper.selectById(termId);
        if (term == null) {
            throw new BizException(404, "学期不存在");
        }
        if (examDate == null || examDate.isBefore(term.getStartDate()) || examDate.isAfter(term.getEndDate())) {
            throw new BizException(400, "考试日期必须落在学期起止日期内");
        }
        if (subjects == null || subjects.isEmpty()) {
            throw new BizException(400, "至少设置一个考试科目");
        }
        Exam exam = new Exam();
        exam.setTermId(termId);
        exam.setName(name);
        exam.setExamDate(examDate);
        examMapper.insert(exam);
        for (SubjectReq s : subjects) {
            if (subjectMapper.selectById(s.getSubjectId()) == null) {
                throw new BizException(404, "学科不存在: " + s.getSubjectId());
            }
            if (s.getFullScore() == null || s.getFullScore().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "满分必须大于 0");
            }
            if (examSubjectMapper.selectCount(new LambdaQueryWrapper<ExamSubject>()
                    .eq(ExamSubject::getExamId, exam.getId())
                    .eq(ExamSubject::getSubjectId, s.getSubjectId())) > 0) {
                throw new BizException(400, "考试科目重复: " + s.getSubjectId());
            }
            ExamSubject es = new ExamSubject();
            es.setExamId(exam.getId());
            es.setSubjectId(s.getSubjectId());
            es.setFullScore(s.getFullScore());
            examSubjectMapper.insert(es);
        }
        return exam.getId();
    }

    /** 批40e 撤回普适：删除整场考试及其全部成绩/科目关联（误建考试撤回；报告按考试读成绩，重新生成即对齐） */
    @Transactional
    public void deleteExam(Long examId) {
        if (examMapper.selectById(examId) == null) {
            throw new BizException(404, "考试不存在");
        }
        scoreMapper.delete(new LambdaQueryWrapper<Score>().eq(Score::getExamId, examId));
        examSubjectMapper.delete(new LambdaQueryWrapper<ExamSubject>().eq(ExamSubject::getExamId, examId));
        examMapper.deleteById(examId);
    }

    /** 考试列表（附学期名与科目数；批49 限成绩可见角色，教师端零入口） */
    public List<Map<String, Object>> examList(UserPrincipal user) {
        requireScoreAccess(user);
        List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                .orderByDesc(Exam::getExamDate).orderByDesc(Exam::getId));
        Map<Long, String> termNames = termMapper.selectList(null).stream()
                .collect(Collectors.toMap(Term::getId, Term::getName));
        Map<Long, Long> subjectCounts = examSubjectMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(ExamSubject::getExamId, Collectors.counting()));
        return exams.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("termId", e.getTermId());
            m.put("termName", termNames.get(e.getTermId()));
            m.put("name", e.getName());
            m.put("examDate", e.getExamDate());
            m.put("classMaxTotal", e.getClassMaxTotal());
            m.put("gradeMaxTotal", e.getGradeMaxTotal());
            m.put("entryOpen", examOpen(e));
            m.put("subjectCount", subjectCounts.getOrDefault(e.getId(), 0L));
            return m;
        }).toList();
    }

    // ───────────────── 录入上下文 ─────────────────

    /** 某班在某考试下的科目上下文（批49：查看者见全科目；editable=可传可改者） */
    public List<Map<String, Object>> subjectContext(UserPrincipal user, Long examId, Long classId) {
        requireScoreAccess(user);
        checkClassVisible(user, classId);
        requireExam(examId);
        boolean editable = canUpload(user, examId, classId);
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName));
        return examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                        .eq(ExamSubject::getExamId, examId).orderByAsc(ExamSubject::getSubjectId))
                .stream().map(es -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subjectId", es.getSubjectId());
                    m.put("name", subjectNames.get(es.getSubjectId()));
                    m.put("fullScore", es.getFullScore());
                    m.put("classMax", es.getClassMax());
                    m.put("gradeMax", es.getGradeMax());
                    m.put("editable", editable);
                    return m;
                }).toList();
    }

    /** 某班某科成绩单（名册 + 分数/排名；缺分为 null）。批49：查看者均为管理/领导面，排名可见 */
    public Map<String, Object> listScores(UserPrincipal user, Long examId, Long subjectId, Long classId) {
        requireScoreAccess(user);
        checkClassVisible(user, classId);
        ExamSubject es = requireExamSubject(examId, subjectId);
        List<Student> roster = roster(classId);
        Map<Long, Score> scores = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, examId).eq(Score::getSubjectId, subjectId))
                .stream().collect(Collectors.toMap(Score::getStudentId, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> rows = roster.stream().map(st -> {
            Score sc = scores.get(st.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", st.getId());
            m.put("studentNo", st.getStudentNo());
            m.put("name", st.getName());
            m.put("score", sc != null ? sc.getScore() : null);
            m.put("classRank", sc != null ? sc.getClassRank() : null);
            m.put("gradeRank", sc != null ? sc.getGradeRank() : null);
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fullScore", es.getFullScore());
        data.put("editable", canUpload(user, examId, classId));
        data.put("rows", rows);
        return data;
    }

    /** 成绩导出（权限同查看 listScores）：当前表格 → xlsx（批49 查看者均含排名列） */
    public Exported exportScores(UserPrincipal user, Long examId, Long subjectId, Long classId) {
        Map<String, Object> data = listScores(user, examId, subjectId, classId); // 内含数据域校验
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        List<Object[]> table = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            table.add(new Object[]{r.get("studentNo"), r.get("name"), r.get("score"),
                    r.get("classRank"), r.get("gradeRank")});
        }
        Exam exam = examMapper.selectById(examId);
        Subject subject = subjectMapper.selectById(subjectId);
        Clazz clazz = clazzMapper.selectById(classId);
        String name = "成绩_" + (exam != null ? exam.getName() : examId) + "_"
                + (subject != null ? subject.getName() : subjectId) + "_"
                + (clazz != null ? clazz.getName() : classId) + ".xlsx";
        return new Exported(name, excel.export("成绩",
                new String[]{"学号", "姓名", "成绩", "班级排名", "年级排名"}, table));
    }

    // ───────────────── 录入 / 导入 ─────────────────

    /** 批量录入：rows 中 score=null 表示清除该生该科成绩（批49：ADMIN/书记全校；级长限绑定年级且录入窗口开放） */
    public Map<String, Object> entry(UserPrincipal user, Long examId, Long subjectId, Long classId,
                                     List<RowReq> rows) {
        requireScoreUpload(user);
        checkClassVisible(user, classId);
        if ("GRADE_LEADER".equals(user.role()) && !examOpen(requireExam(examId))) {
            throw new BizException(403, "该考试录入已关闭");
        }
        ExamSubject es = requireExamSubject(examId, subjectId);
        Map<Long, Student> roster = roster(classId).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Score> existing = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, examId).eq(Score::getSubjectId, subjectId))
                .stream().collect(Collectors.toMap(Score::getStudentId, Function.identity(), (a, b) -> a));
        if (rows == null) {
            rows = List.of();
        }
        int saved = 0;
        for (RowReq r : rows) {
            if (r.getStudentId() == null || !roster.containsKey(r.getStudentId())) {
                throw new BizException(400, "学生不在该班级: " + r.getStudentId());
            }
            Score old = existing.get(r.getStudentId());
            if (r.getScore() == null) {
                if (old != null) {
                    scoreMapper.deleteById(old.getId());
                }
                continue;
            }
            if (r.getScore().compareTo(BigDecimal.ZERO) < 0 || r.getScore().compareTo(es.getFullScore()) > 0) {
                throw new BizException(400, roster.get(r.getStudentId()).getName()
                        + " 的成绩超出 [0, " + es.getFullScore() + "]");
            }
            if (old != null) {
                old.setScore(r.getScore());
                old.setCreatedBy(user.userId());
                scoreMapper.updateById(old);
            } else {
                Score sc = new Score();
                sc.setExamId(examId);
                sc.setSubjectId(subjectId);
                sc.setStudentId(r.getStudentId());
                sc.setScore(r.getScore());
                sc.setCreatedBy(user.userId());
                scoreMapper.insert(sc);
            }
            saved++;
        }
        recompute(examId);
        return Map.of("saved", saved);
    }

    /** Excel 导入：按班内学号匹配，逐行 skip 原因（权限随 entry 收口） */
    public Map<String, Object> importExcel(UserPrincipal user, Long examId, Long subjectId, Long classId,
                                           MultipartFile file) {
        ExamSubject es = requireExamSubject(examId, subjectId);
        List<ExcelScoreHelper.ScoreRow> parsed;
        try (var in = file.getInputStream()) {
            parsed = excel.read(in);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取文件失败: " + e.getMessage());
        }
        Map<String, Student> byNo = roster(classId).stream()
                .collect(Collectors.toMap(Student::getStudentNo, Function.identity(), (a, b) -> a));
        List<RowReq> rows = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (ExcelScoreHelper.ScoreRow r : parsed) {
            Student st = byNo.get(r.studentNo());
            if (st == null) {
                skipped.add(Map.of("studentNo", r.studentNo(), "name", r.name(), "reason", "学号不在该班级"));
                continue;
            }
            if (r.score() == null) {
                continue; // 成绩留空 = 不动该生
            }
            if (r.score().compareTo(BigDecimal.ZERO) < 0 || r.score().compareTo(es.getFullScore()) > 0) {
                skipped.add(Map.of("studentNo", r.studentNo(), "name", r.name(),
                        "reason", "超出 [0, " + es.getFullScore() + "]"));
                continue;
            }
            rows.add(new RowReq(st.getId(), r.score()));
        }
        Map<String, Object> result = new LinkedHashMap<>(entry(user, examId, subjectId, classId, rows));
        result.put("skipped", skipped);
        return result;
    }

    /** 下载导入模板（该班名册预填；权限同查看） */
    public byte[] template(UserPrincipal user, Long classId) {
        requireScoreAccess(user);
        checkClassVisible(user, classId);
        return excel.template(roster(classId));
    }

    // ───────────────── 排名与最高分 ─────────────────

    /**
     * 重算一次考试的全体排名与最高分（每次录入后调用，幂等）。
     * 竞争排名：同分同名次（1,2,2,4）；class_rank 限班内、grade_rank 全体。
     * 单列结构：class_max/grade_max（单科）、class_max_total/grade_max_total（总分）均取全体最高，口径见架构 M7 节。
     */
    void recompute(Long examId) {
        List<ExamSubject> subjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, examId));
        List<Score> all = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                .eq(Score::getExamId, examId).isNotNull(Score::getScore));
        Map<Long, Long> classOf = studentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Student::getId, Student::getClassId, (a, b) -> a));

        for (ExamSubject es : subjects) {
            List<Score> rows = all.stream().filter(s -> s.getSubjectId().equals(es.getSubjectId())).toList();
            assignRanks(rows, classOf);
            rows.forEach(scoreMapper::updateById);
            BigDecimal max = rows.stream().map(Score::getScore).max(Comparator.naturalOrder()).orElse(null);
            if (!eq(max, es.getClassMax()) || !eq(max, es.getGradeMax())) {
                es.setClassMax(max);
                es.setGradeMax(max);
                examSubjectMapper.updateById(es);
            }
        }

        // 总分：每生在该考试全部科目上的得分之和（未录科目按缺分计），最高分回填考试
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        List<Long> subjectIds = subjects.stream().map(ExamSubject::getSubjectId).toList();
        for (Score s : all) {
            if (subjectIds.contains(s.getSubjectId())) {
                totals.merge(s.getStudentId(), s.getScore(), BigDecimal::add);
            }
        }
        BigDecimal maxTotal = totals.values().stream().max(Comparator.naturalOrder()).orElse(null);
        Exam exam = examMapper.selectById(examId);
        if (exam != null && (!eq(maxTotal, exam.getClassMaxTotal()) || !eq(maxTotal, exam.getGradeMaxTotal()))) {
            exam.setClassMaxTotal(maxTotal);
            exam.setGradeMaxTotal(maxTotal);
            examMapper.updateById(exam);
        }
    }

    /** 竞争排名：先全体后班内；同分同名次 */
    private void assignRanks(List<Score> rows, Map<Long, Long> classOf) {
        List<Score> sorted = rows.stream()
                .sorted(Comparator.comparing(Score::getScore).reversed()
                        .thenComparing(Score::getStudentId))
                .toList();
        assign(sorted, Score::setGradeRank);
        Map<Long, List<Score>> byClass = sorted.stream()
                .collect(Collectors.groupingBy(s -> classOf.getOrDefault(s.getStudentId(), -1L),
                        LinkedHashMap::new, Collectors.toList()));
        byClass.values().forEach(classRows -> assign(classRows, Score::setClassRank));
    }

    private void assign(List<Score> sorted, java.util.function.BiConsumer<Score, Integer> setter) {
        int rank = 0;
        BigDecimal prev = null;
        for (int i = 0; i < sorted.size(); i++) {
            Score s = sorted.get(i);
            if (prev == null || s.getScore().compareTo(prev) != 0) {
                rank = i + 1;
                prev = s.getScore();
            }
            setter.accept(s, rank);
        }
    }

    // ───────────────── 权限与查询小件 ─────────────────

    /**
     * 批49 查看门（甲方口径）：ADMIN/书记（全校）、级长（绑定年级，班级面由 checkClassVisible 收口）、
     * 校级领导（分管学段，只读）；其余角色按 SCORE_ALL 权限点只读（教学处主任/副主任按人授权）。
     * 教师/班主任/生活老师/招采/门卫等一律 403（前端已零入口，此处兜底防直连）。
     */
    private void requireScoreAccess(UserPrincipal user) {
        String role = user.role();
        if ("ADMIN".equals(role) || "SECRETARY".equals(role) || "LEADER".equals(role)
                || "GRADE_LEADER".equals(role)) {
            return;
        }
        if (permissionService.hasScoreAll(user)) {
            return;
        }
        throw new BizException(403, "成绩功能仅限管理员、书记、级长与校级领导使用");
    }

    /** 批49 上传门（可传可改）：ADMIN/书记（全校）+级长（绑定年级）；校级领导与 SCORE_ALL 只读 */
    private void requireScoreUpload(UserPrincipal user) {
        String role = user.role();
        if (!"ADMIN".equals(role) && !"SECRETARY".equals(role) && !"GRADE_LEADER".equals(role)) {
            throw new BizException(403, "成绩录入仅管理员、书记或级长可操作");
        }
    }

    /** 批49 可传改判定：ADMIN/书记=可；级长=班级在绑定年级内且录入窗口开放；其余只读 */
    private boolean canUpload(UserPrincipal user, Long examId, Long classId) {
        String role = user.role();
        if ("ADMIN".equals(role) || "SECRETARY".equals(role)) {
            return true;
        }
        if (!"GRADE_LEADER".equals(role)) {
            return false;
        }
        if (!examOpen(examId == null ? null : examMapper.selectById(examId))) {
            return false;
        }
        List<Long> visible = dataScope.visibleClassIds(user);
        return visible != null && visible.contains(classId);
    }

    /** 批39③ 领导分管学段（t_user.stage_scope；非领导/未设置=null 即不受限） */
    private String stageScopeOf(UserPrincipal user) {
        if (!"LEADER".equals(user.role())) {
            return null;
        }
        User u = userMapper.selectById(user.userId());
        return u != null && u.getStageScope() != null && !u.getStageScope().isBlank() ? u.getStageScope() : null;
    }

    /** 班级学段（t_class→t_grade.stage；年级未配 stage=null 视为不受限） */
    private String stageOfClass(Long classId) {
        Clazz c = clazzMapper.selectById(classId);
        if (c == null || c.getGradeId() == null) {
            return null;
        }
        Grade g = gradeMapper.selectById(c.getGradeId());
        return g == null ? null : g.getStage();
    }

    /** 学段匹配（领导分管学段须覆盖班级学段；scope 空=不限） */
    private boolean stageMatch(String scope, Long classId) {
        if (scope == null) {
            return true;
        }
        String stage = stageOfClass(classId);
        return stage == null || scope.equals(stage);
    }

    private void checkClassVisible(UserPrincipal user, Long classId) {
        if (clazzMapper.selectById(classId) == null) {
            throw new BizException(404, "班级不存在");
        }
        List<Long> visible = dataScope.visibleClassIds(user);
        if (visible != null && !visible.contains(classId)) {
            throw new BizException(403, "无权访问该班级（数据权限隔离）");
        }
        String scope = stageScopeOf(user); // 批39③ 学段门（领导分管学段）
        if (!stageMatch(scope, classId)) {
            throw new BizException(403, "该领导分管" + ("PRIMARY".equals(scope) ? "小学部" : "初中部") + "，无权访问另一学段的成绩");
        }
    }

    // ───────────────── 录入窗口 + 全校汇总 ─────────────────

    /** 考试录入窗口是否开放（旧数据无值=开；批49 仅约束级长录入） */
    private static boolean examOpen(Exam exam) {
        return exam == null || exam.getEntryOpen() == null || exam.getEntryOpen() == 1;
    }

    /** 开关录入窗口（管理端考试页签；ADMIN/有 ADMIN_ACCESS 的领导） */
    public void setEntryOpen(Long examId, boolean open) {
        Exam exam = requireExam(examId);
        exam.setEntryOpen(open ? 1 : 0);
        examMapper.updateById(exam);
    }

    /**
     * 全校成绩汇总（批4 领导端/管理端）：subjectId 空=总分模式（每生全部科目得分之和），否则单科模式。
     * 年级排名同分同名次；各班统计=参考人数（有分）/平均分/最高分。rows 分页（防几千学生全量下发）。
     * 批39③：领导有分管学段（t_user.stage_scope）时仅统计该学段班级学生，排名为学段内排名。
     * 批49：级长仅统计绑定年级班级学生（排名即绑定范围内名次）；角色门=requireScoreAccess。
     */
    public Map<String, Object> scoreSummary(UserPrincipal user, Long examId, Long subjectId, int page, int size) {
        requireScoreAccess(user);
        Exam exam = requireExam(examId);
        List<ExamSubject> subjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, examId).orderByAsc(ExamSubject::getSubjectId));
        boolean bySubject = subjectId != null;
        if (bySubject && subjects.stream().noneMatch(s -> s.getSubjectId().equals(subjectId))) {
            throw new BizException(404, "该考试未设置此科目");
        }
        Map<Long, Student> students = studentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity(), (a, b) -> a));
        Map<Long, Clazz> clazzMap = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName));
        Map<Long, Long> classGrade = clazzMap.values().stream()
                .filter(c -> c.getGradeId() != null)
                .collect(Collectors.toMap(Clazz::getId, Clazz::getGradeId, (a, b) -> a));
        Map<Long, String> gradeStage = gradeMapper.selectList(null).stream()
                .filter(g -> g.getStage() != null)
                .collect(Collectors.toMap(Grade::getId, Grade::getStage, (a, b) -> a));
        String stageScope = stageScopeOf(user);
        // 批49 级长：仅绑定年级班级学生进排名/统计（未绑年级=空集，全不可见）
        List<Long> gradeLeaderClassIds = "GRADE_LEADER".equals(user.role())
                ? dataScope.visibleClassIds(user) : null;
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (Score s : scoreMapper.selectList(new LambdaQueryWrapper<Score>().eq(Score::getExamId, examId))) {
            if (bySubject) {
                if (s.getSubjectId().equals(subjectId)) {
                    totals.put(s.getStudentId(), s.getScore());
                }
            } else {
                totals.merge(s.getStudentId(), s.getScore(), BigDecimal::add);
            }
        }
        if (stageScope != null || gradeLeaderClassIds != null) { // 批39③ 学段过滤 / 批49 年级过滤
            totals.keySet().removeIf(sid -> {
                Student st = students.get(sid);
                Long cid = st == null ? null : st.getClassId();
                if (gradeLeaderClassIds != null) {
                    return cid == null || !gradeLeaderClassIds.contains(cid);
                }
                Long gid = cid == null ? null : classGrade.get(cid);
                return gid != null && !stageScope.equals(gradeStage.get(gid));
            });
        }
        // 全体竞争排名（同分同名次），按分数降序、学号升序稳定排序
        List<Map.Entry<Long, BigDecimal>> sorted = totals.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 0;
        BigDecimal prev = null;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Long, BigDecimal> e = sorted.get(i);
            if (prev == null || e.getValue().compareTo(prev) != 0) {
                rank = i + 1;
                prev = e.getValue();
            }
            Student st = students.get(e.getKey());
            if (st == null) {
                continue;
            }
            Clazz cz = st.getClassId() == null ? null : clazzMap.get(st.getClassId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", st.getId());
            m.put("studentNo", st.getStudentNo());
            m.put("name", st.getName());
            m.put("className", cz == null ? "—" : cz.getName());
            m.put("score", e.getValue());
            m.put("gradeRank", rank);
            rows.add(m);
        }
        // 各班统计：参考人数（有分）/平均分/最高分（按排名序稳定取最高）
        Map<Long, List<Map<String, Object>>> byClass = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Student st = students.get(r.get("studentId"));
            if (st != null && st.getClassId() != null) {
                byClass.computeIfAbsent(st.getClassId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Map<String, Object>> classStats = byClass.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> {
                    List<BigDecimal> vals = en.getValue().stream().map(r -> (BigDecimal) r.get("score")).toList();
                    Clazz cz = clazzMap.get(en.getKey());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("classId", en.getKey());
                    m.put("className", cz == null ? String.valueOf(en.getKey()) : cz.getName());
                    m.put("count", vals.size());
                    m.put("avg", vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(vals.size()), 1, java.math.RoundingMode.HALF_UP));
                    m.put("max", vals.stream().max(Comparator.naturalOrder()).orElse(null));
                    return m;
                }).toList();
        // 分页
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> examInfo = new LinkedHashMap<>();
        examInfo.put("id", exam.getId());
        examInfo.put("name", exam.getName());
        examInfo.put("termName", termName(exam.getTermId()));
        examInfo.put("examDate", exam.getExamDate());
        examInfo.put("entryOpen", examOpen(exam));
        data.put("exam", examInfo);
        data.put("mode", bySubject ? "subject" : "total");
        data.put("subjectId", subjectId);
        data.put("subjectName", bySubject ? subjectNames.get(subjectId) : null);
        data.put("subjects", subjects.stream().map(s -> Map.of(
                "subjectId", s.getSubjectId(), "name", subjectNames.getOrDefault(s.getSubjectId(), String.valueOf(s.getSubjectId())),
                "fullScore", s.getFullScore())).toList());
        data.put("fullScore", bySubject
                ? subjects.stream().filter(s -> s.getSubjectId().equals(subjectId)).findFirst().map(ExamSubject::getFullScore).orElse(null)
                : subjects.stream().map(ExamSubject::getFullScore).filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        data.put("total", rows.size());
        data.put("classStats", classStats);
        data.put("rows", from >= rows.size() ? List.of() : rows.subList(from, to));
        return data;
    }

    /** 全校汇总导出（权限同查看；批39③ 同学段口径）：全量年级排名 → xlsx */
    public Exported exportSummary(UserPrincipal user, Long examId, Long subjectId) {
        Map<String, Object> data = scoreSummary(user, examId, subjectId, 1, Integer.MAX_VALUE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        List<Object[]> table = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            table.add(new Object[]{r.get("studentNo"), r.get("name"), r.get("className"),
                    r.get("score"), r.get("gradeRank")});
        }
        Exam exam = examMapper.selectById(examId);
        String subjectName = (String) data.get("subjectName");
        String name = "成绩汇总_" + (exam != null ? exam.getName() : examId)
                + (subjectName != null ? "_" + subjectName : "_总分") + ".xlsx";
        return new Exported(name, excel.export("成绩汇总",
                new String[]{"学号", "姓名", "班级", subjectName != null ? "成绩" : "总分", "年级名次"}, table));
    }

    private String termName(Long termId) {
        Term t = termMapper.selectById(termId);
        return t == null ? null : t.getName();
    }

    private List<Student> roster(Long classId) {
        return studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId).orderByAsc(Student::getStudentNo));
    }

    private Exam requireExam(Long examId) {
        Exam e = examMapper.selectById(examId);
        if (e == null) {
            throw new BizException(404, "考试不存在");
        }
        return e;
    }

    private ExamSubject requireExamSubject(Long examId, Long subjectId) {
        ExamSubject es = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, examId).eq(ExamSubject::getSubjectId, subjectId))
                .stream().findFirst().orElse(null);
        if (es == null) {
            throw new BizException(404, "该考试未设置此科目");
        }
        return es;
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }

    // ───────────────── DTO ─────────────────

    @lombok.Data
    public static class SubjectReq {
        private Long subjectId;
        private BigDecimal fullScore;
    }

    @lombok.Data
    public static class RowReq {
        private Long studentId;
        private BigDecimal score;

        public RowReq() {}

        public RowReq(Long studentId, BigDecimal score) {
            this.studentId = studentId;
            this.score = score;
        }
    }
}
