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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
 * 批49 甲方口径（批63 放宽查看面）：可传可改=ADMIN/书记（全校）+级长（绑定年级，受 entry_open 约束）；
 * 查看=ADMIN/书记（全校）+级长（绑定年级）+校级领导（分管学段，只读）+SCORE_ALL 权限点（全校，只读）
 * +班主任/科任（挂名班/任课班，只读，批63）。
 * 批63 排名只对管理面可见（canSeeRank）：班主任/科任只读入口名次一律服务端置 null，
 * 家长线走 /api/parent/* 白名单天然无 rank；汇总/导出含名次，非管理面 403。
 * 批63c 等级改上传制：grade 由校方/级长上传（A~D 可带 +/-，validateGrade 收口），单科行内、总分=subject_id NULL 行；
 * 系统不再现算——ScoreLevelService/score_grade_* 保留为自治窗口不驱动显示。
 * 批63 增：考试科目/满分可改（updateExam）；listScores subjectId 空=总分模式（内存现算排名）；
 * 模板/导入按考试设定科目多列（ExcelScoreHelper 单班多科一表）。
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

    /** 批63 修改考试科目与各科满分：可增删科目、改满分；降满分不得低于该科已录最高分；
     *  删科连带删除该科已录成绩并重算排名（前端须二次确认）。名称/日期/学期不动 */
    @Transactional
    public void updateExam(UserPrincipal user, Long examId, List<SubjectReq> subjects) {
        requireScoreUpload(user);
        Exam exam = requireExam(examId);
        if (subjects == null || subjects.isEmpty()) {
            throw new BizException(400, "至少设置一个考试科目");
        }
        Map<Long, ExamSubject> existing = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                        .eq(ExamSubject::getExamId, examId)).stream()
                .collect(Collectors.toMap(ExamSubject::getSubjectId, Function.identity(), (a, b) -> a));
        java.util.Set<Long> keep = new java.util.LinkedHashSet<>();
        for (SubjectReq s : subjects) {
            if (s.getSubjectId() == null || subjectMapper.selectById(s.getSubjectId()) == null) {
                throw new BizException(404, "学科不存在: " + s.getSubjectId());
            }
            if (s.getFullScore() == null || s.getFullScore().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "满分必须大于 0");
            }
            if (!keep.add(s.getSubjectId())) {
                throw new BizException(400, "考试科目重复: " + s.getSubjectId());
            }
            ExamSubject es = existing.get(s.getSubjectId());
            if (es == null) {
                ExamSubject n = new ExamSubject();
                n.setExamId(exam.getId());
                n.setSubjectId(s.getSubjectId());
                n.setFullScore(s.getFullScore());
                examSubjectMapper.insert(n);
            } else if (!eq(es.getFullScore(), s.getFullScore())) {
                BigDecimal max = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                                .eq(Score::getExamId, examId).eq(Score::getSubjectId, s.getSubjectId()))
                        .stream().map(Score::getScore).max(Comparator.naturalOrder()).orElse(null);
                if (max != null && s.getFullScore().compareTo(max) < 0) {
                    throw new BizException(400, "科目已录最高分 " + max + "，满分不能低于已录成绩");
                }
                es.setFullScore(s.getFullScore());
                examSubjectMapper.updateById(es);
            }
        }
        boolean removed = false;
        for (ExamSubject es : existing.values()) {
            if (!keep.contains(es.getSubjectId())) {
                scoreMapper.delete(new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, examId).eq(Score::getSubjectId, es.getSubjectId()));
                examSubjectMapper.deleteById(es.getId());
                removed = true;
            }
        }
        if (removed) {
            recompute(examId);
        }
    }

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

    /** 考试列表（附学期名与科目数；批49 限成绩可见角色；批63 班主任/科任只读入口同样可见考试） */
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

    /** 某班/某年级在某考试下的科目上下文（批63 classId/gradeId 二选一；查看者见全科目，editable=可传可改者） */
    public List<Map<String, Object>> subjectContext(UserPrincipal user, Long examId, Long classId, Long gradeId) {
        requireScoreAccess(user);
        targetClassIds(user, classId, gradeId); // 班级存在性/数据域校验
        requireExam(examId);
        boolean editable = classId != null ? canUpload(user, examId, classId) : canUploadRole(user, examId);
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

    /** 成绩单（名册 + 分数 + 等级；缺分为 null/等级随分为 null）。批63：classId 单班 / gradeId 年级跨班合并（带 classId/className）；
     *  subjectId 空=总分模式（各科得分之和，不可录入，排名内存现算同分同名次）。
     *  排名仅管理面可见（canSeeRank）：班主任/科任只读入口 classRank/gradeRank 服务端一律置 null */
    public Map<String, Object> listScores(UserPrincipal user, Long examId, Long subjectId, Long classId, Long gradeId) {
        requireScoreAccess(user);
        List<Long> classIds = targetClassIds(user, classId, gradeId);
        boolean totalMode = subjectId == null;
        List<ExamSubject> examSubjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, examId).orderByAsc(ExamSubject::getSubjectId));
        if (examSubjects.isEmpty()) {
            throw new BizException(404, "该考试未设置科目");
        }
        BigDecimal fullScore = totalMode
                ? examSubjects.stream().map(ExamSubject::getFullScore).filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : examSubjects.stream().filter(s -> s.getSubjectId().equals(subjectId)).findFirst()
                        .orElseThrow(() -> new BizException(404, "该考试未设置此科目")).getFullScore();
        List<Student> roster = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .in(Student::getClassId, classIds)
                .orderByAsc(Student::getClassId).orderByAsc(Student::getStudentNo));
        Map<Long, String> classNames = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                        .in(Clazz::getId, classIds)).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName, (a, b) -> a));
        boolean rankVisible = canSeeRank(user);
        Map<Long, Score> scores = null;
        Map<Long, String> totalGrades = null; // 批63c 总分等级行（subject_id NULL）
        Map<Long, BigDecimal> totals = null;
        Map<Long, Integer> classRankOf = null;
        Map<Long, Integer> gradeRankOf = null;
        if (totalMode) {
            totals = new LinkedHashMap<>();
            totalGrades = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                            .eq(Score::getExamId, examId).isNull(Score::getSubjectId))
                    .stream().collect(Collectors.toMap(Score::getStudentId, Score::getGrade, (a, b) -> a));
            List<Long> subjectIds = examSubjects.stream().map(ExamSubject::getSubjectId).toList();
            for (Score s : scoreMapper.selectList(new LambdaQueryWrapper<Score>().eq(Score::getExamId, examId))) {
                if (s.getScore() != null && subjectIds.contains(s.getSubjectId())) { // 批63c 清分留级行不计入总分
                    totals.merge(s.getStudentId(), s.getScore(), BigDecimal::add);
                }
            }
            if (rankVisible) { // 总分排名内存现算（全体名次+班内名次，同分同名次）；只读入口不耗这一步
                List<Map.Entry<Long, BigDecimal>> sorted = totals.entrySet().stream()
                        .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed()
                                .thenComparing(Map.Entry::getKey)).toList();
                gradeRankOf = new LinkedHashMap<>();
                classRankOf = new LinkedHashMap<>();
                assignTotalRanks(sorted, gradeRankOf);
                Map<Long, Long> classOf = studentMapper.selectList(null).stream()
                        .collect(Collectors.toMap(Student::getId, Student::getClassId, (a, b) -> a));
                for (List<Map.Entry<Long, BigDecimal>> classRows : sorted.stream().collect(Collectors.groupingBy(
                                e -> classOf.getOrDefault(e.getKey(), -1L), LinkedHashMap::new, Collectors.toList()))
                        .values()) {
                    assignTotalRanks(classRows, classRankOf);
                }
            }
        } else {
            scores = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                            .eq(Score::getExamId, examId).eq(Score::getSubjectId, subjectId))
                    .stream().collect(Collectors.toMap(Score::getStudentId, Function.identity(), (a, b) -> a));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Student st : roster) {
            Score sc = scores != null ? scores.get(st.getId()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", st.getId());
            m.put("studentNo", st.getStudentNo());
            m.put("name", st.getName());
            m.put("classId", st.getClassId());
            m.put("className", classNames.getOrDefault(st.getClassId(), "—"));
            m.put("score", totalMode ? totals.get(st.getId()) : sc != null ? sc.getScore() : null);
            // 批63c 等级=校方上传（单科行内 grade；总分 NULL 行），不再现算（ScoreLevelService 留作自治窗口）
            m.put("grade", totalMode ? totalGrades.get(st.getId()) : sc != null ? sc.getGrade() : null);
            m.put("classRank", !rankVisible ? null
                    : totalMode ? classRankOf.get(st.getId()) : sc != null ? sc.getClassRank() : null);
            m.put("gradeRank", !rankVisible ? null
                    : totalMode ? gradeRankOf.get(st.getId()) : sc != null ? sc.getGradeRank() : null);
            rows.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fullScore", fullScore);
        data.put("editable", !totalMode
                && (classId != null ? canUpload(user, examId, classId) : canUploadRole(user, examId)));
        data.put("gradeEditable", canUploadRole(user, examId)); // 批63c 等级上传通道（单科+总分等级都走 entry-grade）
        data.put("canSeeRank", rankVisible);
        data.put("rows", rows);
        return data;
    }

    /** 总分竞争排名：同分同名次写入 rankOf */
    private void assignTotalRanks(List<Map.Entry<Long, BigDecimal>> sorted, Map<Long, Integer> rankOf) {
        int rank = 0;
        BigDecimal prev = null;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Long, BigDecimal> e = sorted.get(i);
            if (prev == null || e.getValue().compareTo(prev) != 0) {
                rank = i + 1;
                prev = e.getValue();
            }
            rankOf.put(e.getKey(), rank);
        }
    }

    /** 成绩导出（含名次列，批63 限排名可见的管理面角色；班主任/科任只读入口 403）：当前表格 → xlsx */
    public Exported exportScores(UserPrincipal user, Long examId, Long subjectId, Long classId, Long gradeId) {
        if (!canSeeRank(user)) {
            throw new BizException(403, "成绩导出仅限管理面角色使用");
        }
        Map<String, Object> data = listScores(user, examId, subjectId, classId, gradeId); // 内含数据域校验
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        boolean singleClass = classId != null;
        List<Object[]> table = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            table.add(singleClass
                    ? new Object[]{r.get("studentNo"), r.get("name"), r.get("score"), r.get("grade"),
                            r.get("classRank"), r.get("gradeRank")}
                    : new Object[]{r.get("studentNo"), r.get("name"), r.get("className"), r.get("score"), r.get("grade"),
                            r.get("classRank"), r.get("gradeRank")});
        }
        Exam exam = examMapper.selectById(examId);
        Subject subject = subjectId == null ? null : subjectMapper.selectById(subjectId);
        String subjectName = subjectId == null ? "总分" : subject != null ? subject.getName() : String.valueOf(subjectId);
        String scopeName;
        if (singleClass) {
            Clazz clazz = clazzMapper.selectById(classId);
            scopeName = clazz != null ? clazz.getName() : String.valueOf(classId);
        } else {
            Grade grade = gradeMapper.selectById(gradeId);
            scopeName = grade != null ? grade.getName() : String.valueOf(gradeId);
        }
        String name = "成绩_" + (exam != null ? exam.getName() : examId) + "_" + subjectName + "_" + scopeName + ".xlsx";
        return new Exported(name, excel.export("成绩", singleClass
                ? new String[]{"学号", "姓名", subjectName, "等级", "班级排名", "年级排名"}
                : new String[]{"学号", "姓名", "班级", subjectName, "等级", "班级排名", "年级排名"}, table));
    }

    // ───────────────── 录入 / 导入 ─────────────────

    /** 批量录入：rows 中 score=null 表示清除该生该科成绩（批49：ADMIN/书记全校；级长限绑定年级且录入窗口开放）。
     *  批63：classId 可空=年级模式，按 studentId 逐行校验（学生存在+属可见班级），跨班一次 recompute */
    public Map<String, Object> entry(UserPrincipal user, Long examId, Long subjectId, Long classId,
                                     List<RowReq> rows) {
        requireScoreUpload(user);
        if (classId != null) {
            checkClassVisible(user, classId);
        }
        if ("GRADE_LEADER".equals(user.role()) && !examOpen(requireExam(examId))) {
            throw new BizException(403, "该考试录入已关闭");
        }
        int saved = writeRows(user, examId, subjectId, classId, rows);
        recompute(examId);
        return Map.of("saved", saved);
    }

    /** 单科写（classId 空=按 studentId 跨班校验）；返回写入条数，不触 recompute（调用方统一收尾） */
    private int writeRows(UserPrincipal user, Long examId, Long subjectId, Long classId, List<RowReq> rows) {
        ExamSubject es = requireExamSubject(examId, subjectId);
        Map<Long, Student> roster = classId != null
                ? roster(classId).stream().collect(Collectors.toMap(Student::getId, Function.identity()))
                : Map.of();
        List<Long> visible = classId == null ? dataScope.visibleClassIds(user) : null;
        Map<Long, Score> existing = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, examId).eq(Score::getSubjectId, subjectId))
                .stream().collect(Collectors.toMap(Score::getStudentId, Function.identity(), (a, b) -> a));
        if (rows == null) {
            rows = List.of();
        }
        int saved = 0;
        for (RowReq r : rows) {
            Student st = classId != null ? roster.get(r.getStudentId())
                    : r.getStudentId() == null ? null : studentMapper.selectById(r.getStudentId());
            if (st == null) {
                throw new BizException(400, classId != null
                        ? "学生不在该班级: " + r.getStudentId() : "学生不存在: " + r.getStudentId());
            }
            if (classId == null && visible != null && !visible.contains(st.getClassId())) {
                throw new BizException(403, "无权录入该学生所在班级: " + st.getName());
            }
            Score old = existing.get(r.getStudentId());
            String grade = normalizeGrade(r.getGrade());
            validateGrade(grade, st.getName());
            if (r.getScore() == null && grade == null) { // 批63c：分数与等级都空=清除整行
                if (old != null) {
                    scoreMapper.deleteById(old.getId());
                }
                continue;
            }
            if (r.getScore() != null
                    && (r.getScore().compareTo(BigDecimal.ZERO) < 0 || r.getScore().compareTo(es.getFullScore()) > 0)) {
                throw new BizException(400, st.getName()
                        + " 的成绩超出 [0, " + es.getFullScore() + "]");
            }
            if (old != null) { // updateById 忽略 null 字段，置空分数/等级须显式 set
                scoreMapper.update(null, new LambdaUpdateWrapper<Score>()
                        .eq(Score::getId, old.getId())
                        .set(Score::getScore, r.getScore())
                        .set(Score::getGrade, grade)
                        .set(Score::getCreatedBy, user.userId()));
            } else {
                Score sc = new Score();
                sc.setExamId(examId);
                sc.setSubjectId(subjectId);
                sc.setStudentId(r.getStudentId());
                sc.setScore(r.getScore());
                sc.setGrade(grade);
                sc.setCreatedBy(user.userId());
                scoreMapper.insert(sc);
            }
            saved++;
        }
        return saved;
    }

    /** 批63c 等级上传（不动分数）：subjectId 空=总分等级（subject_id NULL、score NULL 行）；rows.grade 空=清除 */
    public Map<String, Object> entryGrade(UserPrincipal user, Long examId, Long subjectId, List<RowReq> rows) {
        requireScoreUpload(user);
        if ("GRADE_LEADER".equals(user.role()) && !examOpen(requireExam(examId))) {
            throw new BizException(403, "该考试录入已关闭");
        }
        List<Long> visible = dataScope.visibleClassIds(user);
        if (subjectId != null) {
            requireExamSubject(examId, subjectId);
        }
        LambdaQueryWrapper<Score> qw = new LambdaQueryWrapper<Score>().eq(Score::getExamId, examId);
        if (subjectId != null) {
            qw.eq(Score::getSubjectId, subjectId);
        } else {
            qw.isNull(Score::getSubjectId); // 总分等级行
        }
        Map<Long, Score> existing = scoreMapper.selectList(qw)
                .stream().collect(Collectors.toMap(Score::getStudentId, Function.identity(), (a, b) -> a));
        int saved = 0;
        for (RowReq r : rows == null ? List.<RowReq>of() : rows) {
            Student st = r.getStudentId() == null ? null : studentMapper.selectById(r.getStudentId());
            if (st == null) {
                throw new BizException(400, "学生不存在: " + r.getStudentId());
            }
            if (visible != null && !visible.contains(st.getClassId())) { // ADMIN/SECRETARY visible=null=全校
                throw new BizException(403, "无权录入该学生所在班级: " + st.getName());
            }
            String grade = normalizeGrade(r.getGrade());
            validateGrade(grade, st.getName());
            Score old = existing.get(r.getStudentId());
            if (grade == null) {
                if (old != null) {
                    scoreMapper.deleteById(old.getId());
                }
                continue;
            }
            if (old != null) {
                scoreMapper.update(null, new LambdaUpdateWrapper<Score>()
                        .eq(Score::getId, old.getId())
                        .set(Score::getGrade, grade)
                        .set(Score::getCreatedBy, user.userId()));
            } else {
                Score sc = new Score();
                sc.setExamId(examId);
                sc.setSubjectId(subjectId); // null=总分等级行（score 同为 NULL）
                sc.setStudentId(r.getStudentId());
                sc.setGrade(grade);
                sc.setCreatedBy(user.userId());
                scoreMapper.insert(sc);
            }
            saved++;
        }
        recompute(examId); // 等级行不参与排名（recompute 按 score 非空过滤），仅走一遍统一收尾
        return Map.of("saved", saved);
    }

    /** 批63c 等级归一：null/空白→null，其余 trim+大写 */
    private String normalizeGrade(String grade) {
        return grade == null || grade.isBlank() ? null : grade.trim().toUpperCase();
    }

    /** 批63c 等级格式：A~D 可带 +/-（A+ A A- … D-，校方 9 档外留 D 系余量）；null 视为合法（=清除/不动） */
    private static boolean gradeOk(String grade) {
        return grade == null || grade.matches("[A-D][+-]?");
    }

    private void validateGrade(String grade, String who) {
        if (!gradeOk(grade)) {
            throw new BizException(400, who + " 的等级格式应为 A~D 可带 +/-（如 A+、B-），收到: " + grade);
        }
    }

    /** Excel 导入（单班多科，批63 各科分数列+批63c 各科等级列/总分等级列；权限随 entry 收口）。防呆：文件混入非本班学号→整单拒绝并列出，零写入；
     *  某科留空=不动该生该科；超满分/非法等级逐格 skip 列原因；一次收尾 recompute */
    public Map<String, Object> importExcel(UserPrincipal user, Long examId, Long classId, MultipartFile file) {
        requireScoreUpload(user);
        checkClassVisible(user, classId);
        if ("GRADE_LEADER".equals(user.role()) && !examOpen(requireExam(examId))) {
            throw new BizException(403, "该考试录入已关闭");
        }
        List<ExamSubject> examSubjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, examId).orderByAsc(ExamSubject::getSubjectId));
        if (examSubjects.isEmpty()) {
            throw new BizException(404, "该考试未设置科目");
        }
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
        List<String> orderedNames = examSubjects.stream()
                .map(es -> subjectNames.getOrDefault(es.getSubjectId(), String.valueOf(es.getSubjectId()))).toList();
        List<ExcelScoreHelper.ScoreRow> parsed;
        try (var in = file.getInputStream()) {
            parsed = excel.read(in, orderedNames);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取文件失败: " + e.getMessage());
        }
        Map<String, Student> byNo = roster(classId).stream()
                .collect(Collectors.toMap(Student::getStudentNo, Function.identity(), (a, b) -> a));
        Map<String, ExamSubject> byName = examSubjects.stream()
                .collect(Collectors.toMap(es -> subjectNames.getOrDefault(es.getSubjectId(),
                        String.valueOf(es.getSubjectId())), Function.identity(), (a, b) -> a));
        Map<Long, Map<Long, RowReq>> bySubject = new LinkedHashMap<>(); // subjectId → studentId → 合并行（分数/等级列并到同一行）
        List<RowReq> totalGradeRows = new ArrayList<>(); // 批63c 总分等级列
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<String> foreignNos = new ArrayList<>();
        for (ExcelScoreHelper.ScoreRow r : parsed) {
            Student st = byNo.get(r.studentNo());
            if (st == null) {
                foreignNos.add(r.studentNo());
                continue;
            }
            for (var en : r.scores().entrySet()) {
                ExamSubject es = byName.get(en.getKey());
                BigDecimal v = en.getValue();
                if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(es.getFullScore()) > 0) {
                    skipped.add(Map.of("studentNo", r.studentNo(), "name", r.name(), "subject", en.getKey(),
                            "reason", "超出 [0, " + es.getFullScore() + "]"));
                    continue;
                }
                RowReq row = bySubject.computeIfAbsent(es.getSubjectId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(st.getId(), k -> new RowReq());
                row.setStudentId(st.getId());
                row.setScore(v);
            }
            for (var en : r.grades().entrySet()) { // 批63c 单科等级列（非法值逐格 skip，留空不动）
                ExamSubject es = byName.get(en.getKey());
                String g = normalizeGrade(en.getValue());
                if (!gradeOk(g)) {
                    skipped.add(Map.of("studentNo", r.studentNo(), "name", r.name(), "subject", en.getKey() + "等级",
                            "reason", "等级应为 A~D 可带 +/-（如 A+、B-），收到: " + en.getValue()));
                    continue;
                }
                RowReq row = bySubject.computeIfAbsent(es.getSubjectId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(st.getId(), k -> new RowReq());
                row.setStudentId(st.getId());
                row.setGrade(g);
            }
            String tg = normalizeGrade(r.totalGrade()); // 批63c 总分等级列
            if (tg != null && !gradeOk(tg)) {
                skipped.add(Map.of("studentNo", r.studentNo(), "name", r.name(), "subject", "总分等级",
                        "reason", "等级应为 A~D 可带 +/-（如 A+、B-），收到: " + r.totalGrade()));
            } else if (tg != null) {
                RowReq row = new RowReq();
                row.setStudentId(st.getId());
                row.setGrade(tg);
                totalGradeRows.add(row);
            }
        }
        if (!foreignNos.isEmpty()) { // 整单拒绝：任一学号不属该班即全部不导入（防错班串数据）
            throw new BizException(400, "以下学号不属于该班级，为防误导整单已拒绝导入: " + String.join("、", foreignNos));
        }
        for (var en : bySubject.entrySet()) { // 批63c 等级-only 行预填该科现有分数：writeRows 是全量语义（score null=清分数），patch 导入不能误清
            Map<Long, BigDecimal> exist = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                            .eq(Score::getExamId, examId).eq(Score::getSubjectId, en.getKey())
                            .in(Score::getStudentId, en.getValue().keySet()))
                    .stream().collect(Collectors.toMap(Score::getStudentId, Score::getScore, (a, b) -> a));
            for (RowReq row : en.getValue().values()) {
                if (row.getScore() == null && row.getGrade() != null) {
                    row.setScore(exist.get(row.getStudentId())); // 无现有分数则保持 null（本就无分可清）
                }
            }
        }
        int saved = 0;
        for (var en : bySubject.entrySet()) {
            saved += writeRows(user, examId, en.getKey(), classId, List.copyOf(en.getValue().values()));
        }
        if (!totalGradeRows.isEmpty()) { // 批63c 总分等级走 entry-grade 通道（内部自带一次 recompute）
            saved += (Integer) entryGrade(user, examId, null, totalGradeRows).get("saved");
        }
        recompute(examId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        result.put("skipped", skipped);
        return result;
    }

    /** 下载导入模板（批63 按考试设定科目多列；该班名册预填；权限同查看） */
    public byte[] template(UserPrincipal user, Long examId, Long classId) {
        requireScoreAccess(user);
        checkClassVisible(user, classId);
        List<ExamSubject> examSubjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                .eq(ExamSubject::getExamId, requireExam(examId).getId()).orderByAsc(ExamSubject::getSubjectId));
        if (examSubjects.isEmpty()) {
            throw new BizException(404, "该考试未设置科目");
        }
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
        List<String> orderedNames = examSubjects.stream()
                .map(es -> subjectNames.getOrDefault(es.getSubjectId(), String.valueOf(es.getSubjectId()))).toList();
        return excel.template(roster(classId), orderedNames);
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
                .eq(Score::getExamId, examId).isNotNull(Score::getScore)
                .isNotNull(Score::getSubjectId)); // 批63c 总分等级行 subject_id NULL，不进排名
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
     * 查看门：ADMIN/书记（全校）、级长（绑定年级，班级面由 checkClassVisible/targetClassIds 收口）、
     * 校级领导（分管学段，只读）、SCORE_ALL 权限点（教学处主任/副主任按人授权，只读）；
     * 批63 放开班主任/科任（挂名班/任课班只读，名次由 canSeeRank 收口不下发）。
     * 生活老师/招采/门卫/家长等其余角色一律 403（前端零入口，此处兜底防直连）。
     */
    private void requireScoreAccess(UserPrincipal user) {
        String role = user.role();
        if ("ADMIN".equals(role) || "SECRETARY".equals(role) || "LEADER".equals(role)
                || "DIRECTOR".equals(role) || "GRADE_LEADER".equals(role)
                || "HEAD_TEACHER".equals(role) || "TEACHER".equals(role)) { // 批63 班主任/科任只读入口；批64 主任（领导层）只读
            return;
        }
        if (permissionService.hasScoreAll(user)) {
            return;
        }
        throw new BizException(403, "成绩功能仅限管理员、书记、领导层（领导/主任）、级长、班主任与科任教师使用");
    }

    /** 批63 排名可见门：管理面角色+SCORE_ALL 可见；班主任/科任只读入口与家长线一律无名次（服务端置 null 唯一口径）；批64 主任（领导层）可见 */
    private boolean canSeeRank(UserPrincipal user) {
        String role = user.role();
        return "ADMIN".equals(role) || "SECRETARY".equals(role) || "LEADER".equals(role)
                || "DIRECTOR".equals(role)
                || "GRADE_LEADER".equals(role) || permissionService.hasScoreAll(user);
    }

    /** 批49 上传门（可传可改）：ADMIN/书记（全校）+级长（绑定年级）；校级领导与 SCORE_ALL 只读 */
    private void requireScoreUpload(UserPrincipal user) {
        String role = user.role();
        if (!"ADMIN".equals(role) && !"SECRETARY".equals(role) && !"GRADE_LEADER".equals(role)) {
            throw new BizException(403, "成绩录入仅管理员、书记或级长可操作");
        }
    }

    /** 批63 可传改角色线（不含班级归属）：ADMIN/书记=可；级长=录入窗口开放；班主任/科任/领导/SCORE_ALL 恒只读 */
    private boolean canUploadRole(UserPrincipal user, Long examId) {
        String role = user.role();
        if ("ADMIN".equals(role) || "SECRETARY".equals(role)) {
            return true;
        }
        return "GRADE_LEADER".equals(role)
                && examOpen(examId == null ? null : examMapper.selectById(examId));
    }

    /** 批49 可传改判定：ADMIN/书记=可；级长=班级在绑定年级内且录入窗口开放；其余只读 */
    private boolean canUpload(UserPrincipal user, Long examId, Long classId) {
        if (!canUploadRole(user, examId)) {
            return false;
        }
        if ("GRADE_LEADER".equals(user.role())) {
            List<Long> visible = dataScope.visibleClassIds(user);
            return visible != null && visible.contains(classId);
        }
        return true;
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

    /** 批63 目标班级：classId 单班（沿用 checkClassVisible）；gradeId 年级模式=该年级班 ∩ 可见班级
     *  （ADMIN/书记/无限学段领导=全年级；年级无可见班级=403） */
    private List<Long> targetClassIds(UserPrincipal user, Long classId, Long gradeId) {
        if (classId != null) {
            checkClassVisible(user, classId);
            return List.of(classId);
        }
        if (gradeId == null) {
            throw new BizException(400, "classId 与 gradeId 至少传一个");
        }
        if (gradeMapper.selectById(gradeId) == null) {
            throw new BizException(404, "年级不存在");
        }
        List<Long> gradeClassIds = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                        .eq(Clazz::getGradeId, gradeId).orderByAsc(Clazz::getId)).stream()
                .map(Clazz::getId).toList();
        if (gradeClassIds.isEmpty()) {
            throw new BizException(404, "该年级暂无班级");
        }
        List<Long> visible = dataScope.visibleClassIds(user);
        if (visible == null) {
            return gradeClassIds;
        }
        List<Long> ids = gradeClassIds.stream().filter(visible::contains).toList();
        if (ids.isEmpty()) {
            throw new BizException(403, "无权访问该年级的班级（数据权限隔离）");
        }
        return ids;
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
        if (!canSeeRank(user)) { // 汇总含全体年级排名：班主任/科任只读入口堵绕过
            throw new BizException(403, "成绩汇总仅限管理面角色使用");
        }
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
        Map<Long, String> gradeOf = new LinkedHashMap<>(); // 批63c 上传等级：单科=该科行 grade；总分=subject_id NULL 行
        for (Score s : scoreMapper.selectList(new LambdaQueryWrapper<Score>().eq(Score::getExamId, examId))) {
            if (bySubject) {
                if (s.getSubjectId() != null && s.getSubjectId().equals(subjectId)) {
                    if (s.getScore() != null) {
                        totals.put(s.getStudentId(), s.getScore());
                    }
                    if (s.getGrade() != null) {
                        gradeOf.put(s.getStudentId(), s.getGrade());
                    }
                }
            } else if (s.getSubjectId() == null) { // 批63c 总分等级行（不进总分统计）
                if (s.getGrade() != null) {
                    gradeOf.put(s.getStudentId(), s.getGrade());
                }
            } else if (s.getScore() != null) {
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
            m.put("grade", gradeOf.get(st.getId())); // 批63c 校方上传等级，未上传为 null
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
                    r.get("score"), r.get("grade"), r.get("gradeRank")});
        }
        Exam exam = examMapper.selectById(examId);
        String subjectName = (String) data.get("subjectName");
        String name = "成绩汇总_" + (exam != null ? exam.getName() : examId)
                + (subjectName != null ? "_" + subjectName : "_总分") + ".xlsx";
        return new Exported(name, excel.export("成绩汇总",
                new String[]{"学号", "姓名", "班级", subjectName != null ? "成绩" : "总分", "等级", "年级名次"}, table));
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
        private String grade; // 批63c 上传等级（可单独上传不动分数）

        public RowReq() {}

        public RowReq(Long studentId, BigDecimal score) {
            this.studentId = studentId;
            this.score = score;
        }
    }
}
