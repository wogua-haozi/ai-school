package com.aischool.server.service.report;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.*;
import com.aischool.server.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 聚合服务：库表行数据 → 与 report-renderer/src/main/resources/golden_student.json
 * 完全相同的 JSON 契约（字段名/层级是渲染契约，值来自库表；契约漂移 = 渲染即坏）。
 *
 * 数据口径（对照 scripts/expand_golden.py）：
 * - 榜单/周线/均值等由聚合表（t_*_stat_* / t_*_avg）直接读，架构定位「报告只读聚合表」；
 * - 记录卡 = t_evaluation 按 (title, indicator) 分组求和，登记人 = 教师实名按首次出现序；
 * - 坐标轴刻度是样例固定刻度，随 t_grid/t_subject/报告模板配置下发。
 */
@Service
@RequiredArgsConstructor
public class ReportDataBuilder {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final TermMapper termMapper;
    private final UserMapper userMapper;
    private final SubjectMapper subjectMapper;
    private final ExamMapper examMapper;
    private final ExamSubjectMapper examSubjectMapper;
    private final ScoreMapper scoreMapper;
    private final RegularScoreMapper regularScoreMapper;
    private final HomeworkStatMapper homeworkStatMapper;
    private final GridMapper gridMapper;
    private final IndicatorMapper indicatorMapper;
    private final EvaluationMapper evaluationMapper;
    private final GridStatWeekMapper gridStatWeekMapper;
    private final GridStatTermMapper gridStatTermMapper;
    private final ClassGridAvgMapper classGridAvgMapper;
    private final GradeGridAvgMapper gradeGridAvgMapper;
    private final SubjectStatWeekMapper subjectStatWeekMapper;
    private final SubjectStatTermMapper subjectStatTermMapper;
    private final ProcessWeekMapper processWeekMapper;
    private final ProcessStatMapper processStatMapper;
    private final StudentAnalysisMapper studentAnalysisMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySignupMapper activitySignupMapper;
    private final CoinAccountMapper coinAccountMapper;
    private final CoinRateMapper coinRateMapper;
    private final CoinIncomeMapper coinIncomeMapper;
    private final CoinExpenseMapper coinExpenseMapper;
    private final CoinWeekMapper coinWeekMapper;
    private final CoinStatMapper coinStatMapper;
    private final GrowthLevelMapper growthLevelMapper;
    private final GrowthSymbolStatMapper growthSymbolStatMapper;
    private final ComprehensiveMapper comprehensiveMapper;
    private final CommentMapper commentMapper;
    private final ReportTemplateMapper reportTemplateMapper;
    private final MomentMapper momentMapper;
    private final MomentStudentMapper momentStudentMapper;
    private final PdfStoreService pdfStore;
    private final PortraitService portraitService;

    public Map<String, Object> build(Long studentId, Long termId) {
        return build(studentId, termId, null);
    }

    /** 批42 分期归档：period=MID（期中）时取数窗口锁定学期前半——任何时候（重）生成口径都稳定，
     *  期中之后补录的数据自然进期末版；FINAL/null=全学期（存量默认） */
    public Map<String, Object> build(Long studentId, Long termId, String period) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(404, "学生不存在: " + studentId);
        }
        Term loaded = termMapper.selectById(termId);
        if (loaded == null) {
            throw new BizException(404, "学期不存在: " + termId);
        }
        Term term = loaded;
        if ("MID".equals(period) && loaded.getStartDate() != null && loaded.getEndDate() != null) {
            term = new Term();
            term.setId(loaded.getId());
            term.setName(loaded.getName());
            term.setStartDate(loaded.getStartDate());
            term.setEndDate(loaded.getStartDate().plusDays(
                    java.time.temporal.ChronoUnit.DAYS.between(loaded.getStartDate(), loaded.getEndDate()) / 2));
            term.setIsCurrent(loaded.getIsCurrent());
        }
        Clazz clazz = clazzMapper.selectById(student.getClassId());
        Grade grade = gradeMapper.selectById(clazz.getGradeId());
        User headTeacher = clazz.getHeadTeacherId() != null ? userMapper.selectById(clazz.getHeadTeacherId()) : null;
        ReportTemplate template = reportTemplateMapper.selectOne(new LambdaQueryWrapper<ReportTemplate>()
                .eq(ReportTemplate::getStatus, "启用").last("LIMIT 1"));
        if (template == null) {
            throw new BizException(500, "未配置启用的报告模板");
        }
        JsonNode sections = parseSections(template.getSections());
        Term prevTerm = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                .lt(Term::getId, termId).orderByDesc(Term::getId).last("LIMIT 1"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("school", buildSchool(template, term, sections));
        data.put("student", buildStudent(student, clazz, grade, headTeacher));
        data.put("academic", buildAcademic(student, term, sections));
        data.put("subjectPages", buildSubjectPages(student, term));
        data.put("regularScores", buildRegularScores(student, term));
        Map<String, Object> radar = buildRadar(student, term, prevTerm, sections);
        data.put("radar", radar);
        List<Map<String, Object>> grids = buildGrids(student, term, prevTerm, clazz, grade);
        data.put("grids", grids);
        List<Map<String, Object>> activities = buildActivities(student, term);
        data.put("activities", activities);
        data.put("coin", buildCoin(student, term, sections));
        Map<String, Object> growthSymbol = buildGrowthSymbol(student, term);
        data.put("growthSymbol", growthSymbol);
        // 批40c IP 成长画像垫图版：性别选底图 + 等级/最强格/最热课程 → edits；
        // 底图未上传 → null（虚线占位框），生成失败 → IP 原图占位
        data.put("portraitFile", portraitService.materialize(
                student.getGender(), growthSymbol, grids, activities));
        data.put("comprehensive", buildComprehensive(student, term));
        data.put("headTeacherComment", buildHeadTeacherComment(student, term));
        data.put("moments", buildMoments(student, term));
        // 批63 家长版期末成绩页：分数+满分+等级（无排名）；教师版模板不渲染此键（渲染产物零影响）
        data.put("examScores", buildExamScores(student, term));
        return data;
    }

    /** 批63 家长版期末成绩页素材：学期最新考试各科 分数/满分/等级（批63c 等级=校方上传，未上传为 null；另附总分等级） */
    private Map<String, Object> buildExamScores(Student student, Term term) {
        Exam exam = latestExam(term.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (exam == null) {
            m.put("examName", "");
            m.put("subjects", List.of());
            return m;
        }
        Map<Long, Subject> subjects = allSubjects();
        List<ExamSubject> ordered = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                        .eq(ExamSubject::getExamId, exam.getId())).stream()
                .sorted(Comparator.comparing(es -> subjects.containsKey(es.getSubjectId())
                        ? subjects.get(es.getSubjectId()).getSort() : Integer.MAX_VALUE))
                .toList();
        List<Score> mine = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                .eq(Score::getExamId, exam.getId()).eq(Score::getStudentId, student.getId()));
        Map<Long, Score> scoreBySubject = mine.stream()
                .filter(sc -> sc.getSubjectId() != null && sc.getScore() != null) // 批63c 排除总分等级行/清分留级行
                .collect(Collectors.toMap(Score::getSubjectId, s -> s));
        String totalGrade = mine.stream().filter(sc -> sc.getSubjectId() == null)
                .map(Score::getGrade).filter(Objects::nonNull).findFirst().orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExamSubject es : ordered) {
            Score sc = scoreBySubject.get(es.getSubjectId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", subjects.containsKey(es.getSubjectId())
                    ? subjects.get(es.getSubjectId()).getName() : String.valueOf(es.getSubjectId()));
            row.put("score", Num.of(sc != null ? sc.getScore() : null));
            row.put("fullScore", Num.of(es.getFullScore()));
            row.put("grade", sc != null ? sc.getGrade() : null); // 批63c 校方上传等级
            rows.add(row);
        }
        m.put("examName", exam.getName() == null ? "" : exam.getName());
        m.put("subjects", rows);
        m.put("totalGrade", totalGrade == null ? "" : totalGrade); // 批63c 总分等级（subject_id NULL 行）
        return m;
    }

    // ───────────────── school / student ─────────────────

    private Map<String, Object> buildSchool(ReportTemplate template, Term term, JsonNode sections) {
        List<List<String>> philosophy = new ArrayList<>();
        sections.path("philosophy").forEach(pair -> {
            List<String> p = new ArrayList<>();
            pair.forEach(v -> p.add(v.asText()));
            philosophy.add(p);
        });
        Map<String, Object> school = new LinkedHashMap<>();
        school.put("name", template.getSchoolName());
        school.put("term", term.getName());
        school.put("intro", sections.path("intro").asText(""));
        school.put("nineGridIntro", sections.path("nineGridIntro").asText(""));
        school.put("philosophy", philosophy);
        return school;
    }

    private Map<String, Object> buildStudent(Student s, Clazz c, Grade g, User headTeacher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("studentNo", s.getStudentNo());
        m.put("grade", g.getName());
        m.put("clazz", c.getName());
        m.put("headTeacher", headTeacher != null ? headTeacher.getRealName() : "");
        m.put("photoUrl", s.getPhotoUrl() == null ? "" : s.getPhotoUrl());
        return m;
    }

    // ───────────────── academic（学业综合页 p07） ─────────────────

    private Map<String, Object> buildAcademic(Student student, Term term, JsonNode sections) {
        Exam exam = latestExam(term.getId());
        Map<Long, Subject> subjects = allSubjects();          // subjectId -> subject
        List<Score> scores = exam != null ? scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                .eq(Score::getExamId, exam.getId()).eq(Score::getStudentId, student.getId())) : List.of();

        // 按分数降序、学科序升序（样例：物理97 在 音乐97 前）
        List<Score> ordered = scores.stream()
                .filter(sc -> subjects.containsKey(sc.getSubjectId()))
                .sorted(Comparator.comparing(Score::getScore).reversed()
                        .thenComparing(sc -> subjects.get(sc.getSubjectId()).getSort()))
                .toList();
        List<Map<String, Object>> subjectList = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Score sc : ordered) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", subjects.get(sc.getSubjectId()).getName());
            m.put("score", Num.of(sc.getScore()));
            subjectList.add(m);
            total = total.add(sc.getScore());
        }

        StudentAnalysis analysis = studentAnalysisMapper.selectOne(new LambdaQueryWrapper<StudentAnalysis>()
                .eq(StudentAnalysis::getStudentId, student.getId())
                .eq(StudentAnalysis::getTermId, term.getId()).last("LIMIT 1"));

        ProcessWeek maxWeek = processWeekMapper.selectOne(new LambdaQueryWrapper<ProcessWeek>()
                .eq(ProcessWeek::getStudentId, student.getId()).eq(ProcessWeek::getTermId, term.getId())
                .orderByDesc(ProcessWeek::getWeekNo).last("LIMIT 1"));
        List<Integer> weekNos = weekNos(maxWeek == null ? 0 : maxWeek.getWeekNo());
        List<ProcessWeek> pw = processWeekMapper.selectList(new LambdaQueryWrapper<ProcessWeek>()
                .eq(ProcessWeek::getStudentId, student.getId()).eq(ProcessWeek::getTermId, term.getId())
                .orderByAsc(ProcessWeek::getWeekNo));
        Map<Integer, ProcessWeek> pwByWeek = pw.stream()
                .collect(Collectors.toMap(ProcessWeek::getWeekNo, w -> w));

        ProcessStat ps = processStatMapper.selectOne(new LambdaQueryWrapper<ProcessStat>()
                .eq(ProcessStat::getStudentId, student.getId()).eq(ProcessStat::getTermId, term.getId())
                .last("LIMIT 1"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjects", subjectList);
        Map<String, Object> totalMap = new LinkedHashMap<>();
        totalMap.put("score", Num.of(total));
        totalMap.put("classMax", Num.of(exam != null ? exam.getClassMaxTotal() : null));
        totalMap.put("gradeMax", Num.of(exam != null ? exam.getGradeMaxTotal() : null));
        m.put("total", totalMap);
        m.put("advantage", analysis != null ? analysis.getAdvantage() : "");
        m.put("toImprove", analysis != null ? analysis.getToImprove() : "");
        Map<String, Object> process = new LinkedHashMap<>();
        if (ps != null) {
            process.put("positive", triple(ps.getPosMine(), ps.getPosClassAvg(), ps.getPosGradeAvg()));
            process.put("negative", triple(ps.getNegMine(), ps.getNegClassAvg(), ps.getNegGradeAvg()));
        }
        Map<String, Object> weekly = new LinkedHashMap<>();
        weekly.put("weeks", weekLabels(weekNos));
        weekly.put("mine", series(weekNos, pwByWeek, ProcessWeek::getMine));
        weekly.put("classAvg", series(weekNos, pwByWeek, ProcessWeek::getClassAvg));
        weekly.put("gradeAvg", series(weekNos, pwByWeek, ProcessWeek::getGradeAvg));
        process.put("weekly", weekly);
        m.put("process", process);
        return m;
    }

    // ───────────────── subjectPages（学科页 p08-18） ─────────────────

    private List<Map<String, Object>> buildSubjectPages(Student student, Term term) {
        Exam exam = latestExam(term.getId());
        Map<Long, Subject> subjects = allSubjects();
        List<SubjectStatTerm> stats = subjectStatTermMapper.selectList(new LambdaQueryWrapper<SubjectStatTerm>()
                .eq(SubjectStatTerm::getStudentId, student.getId()).eq(SubjectStatTerm::getTermId, term.getId()));
        Set<Long> subjectIds = stats.stream().map(SubjectStatTerm::getSubjectId).collect(Collectors.toSet());
        Map<Long, Score> scoreBySubject = exam != null ? scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, exam.getId()).eq(Score::getStudentId, student.getId())).stream()
                .collect(Collectors.toMap(Score::getSubjectId, s -> s)) : Map.of();
        Map<Long, ExamSubject> examSubject = exam != null ? examSubjectMapper.selectList(
                        new LambdaQueryWrapper<ExamSubject>().eq(ExamSubject::getExamId, exam.getId())).stream()
                .collect(Collectors.toMap(ExamSubject::getSubjectId, es -> es)) : Map.of();

        List<Subject> ordered = subjects.values().stream()
                .filter(s -> subjectIds.contains(s.getId()))
                .sorted(Comparator.comparing(Subject::getSort)).toList();

        List<Map<String, Object>> pages = new ArrayList<>();
        for (Subject subject : ordered) {
            SubjectStatTerm st = stats.stream().filter(x -> x.getSubjectId().equals(subject.getId())).findFirst().orElse(null);
            if (st == null) {
                continue;
            }
            List<SubjectStatWeek> sw = subjectStatWeekMapper.selectList(new LambdaQueryWrapper<SubjectStatWeek>()
                    .eq(SubjectStatWeek::getStudentId, student.getId())
                    .eq(SubjectStatWeek::getTermId, term.getId())
                    .eq(SubjectStatWeek::getSubjectId, subject.getId())
                    .orderByAsc(SubjectStatWeek::getWeekNo));
            List<Integer> weekNos = sw.stream().map(SubjectStatWeek::getWeekNo).toList();

            Map<String, Object> page = new LinkedHashMap<>();
            page.put("name", subject.getName());
            page.put("score", Num.of(scoreBySubject.containsKey(subject.getId())
                    ? scoreBySubject.get(subject.getId()).getScore() : null));
            ExamSubject es = examSubject.get(subject.getId());
            page.put("classMax", Num.of(es != null ? es.getClassMax() : null));
            page.put("gradeMax", Num.of(es != null ? es.getGradeMax() : null));
            page.put("motto", subject.getMotto() == null ? "" : subject.getMotto());
            Map<String, Object> procH = new LinkedHashMap<>();
            procH.put("min", Num.of(subject.getProcHMin()));
            procH.put("max", Num.of(subject.getProcHMax()));
            procH.put("step", Num.of(subject.getProcHStep()));
            page.put("procH", procH);
            page.put("pos", triple(st.getPosMine(), st.getPosClassAvg(), st.getPosGradeAvg()));
            page.put("neg", triple(st.getNegMine(), st.getNegClassAvg(), st.getNegGradeAvg()));
            // 样例刻度规则：procW.max==3 → step=1.0；否则 max/4（expand_golden.py）
            Map<String, Object> procW = new LinkedHashMap<>();
            procW.put("min", 0);
            procW.put("max", Num.of(subject.getProcWMax()));
            procW.put("step", procWStep(subject.getProcWMax()));
            page.put("procW", procW);
            Map<String, Object> weekly = new LinkedHashMap<>();
            weekly.put("weeks", weekLabels(weekNos));
            weekly.put("mine", sw.stream().map(w -> Num.of(w.getMine())).toList());
            weekly.put("classAvg", sw.stream().map(w -> Num.of(w.getClassAvg())).toList());
            weekly.put("gradeAvg", sw.stream().map(w -> Num.of(w.getGradeAvg())).toList());
            page.put("weekly", weekly);
            pages.add(page);
        }
        return pages;
    }

    // ───────────────── regularScores（p19 两表） ─────────────────

    private Map<String, Object> buildRegularScores(Student student, Term term) {
        Map<Long, Subject> subjects = allSubjects();
        List<RegularScore> regulars = regularScoreMapper.selectList(new LambdaQueryWrapper<RegularScore>()
                .eq(RegularScore::getStudentId, student.getId()).eq(RegularScore::getTermId, term.getId()));
        List<Map<String, Object>> subjectList = regulars.stream()
                .sorted(Comparator.comparing(r -> regularSort(subjects.get(r.getSubjectId()))))
                .map(r -> {
                    Subject s = subjects.get(r.getSubjectId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.getShortName() != null && !s.getShortName().isEmpty() ? s.getShortName() : s.getName());
                    m.put("score", Num.of(r.getScore()));
                    return m;
                }).toList();

        List<HomeworkStat> homework = homeworkStatMapper.selectList(new LambdaQueryWrapper<HomeworkStat>()
                .eq(HomeworkStat::getStudentId, student.getId()).eq(HomeworkStat::getTermId, term.getId()));
        Map<Long, Map<Integer, HomeworkStat>> bySubject = homework.stream().collect(Collectors.groupingBy(
                HomeworkStat::getSubjectId,
                Collectors.toMap(HomeworkStat::getColType, h -> h)));
        List<Map<String, Object>> rows = bySubject.entrySet().stream()
                .filter(e -> subjects.containsKey(e.getKey()))
                .sorted(Comparator.comparing(e -> regularSort(subjects.get(e.getKey()))))
                .map(e -> {
                    Subject s = subjects.get(e.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("subject", s.getShortName() != null && !s.getShortName().isEmpty() ? s.getShortName() : s.getName());
                    List<String> values = new ArrayList<>();
                    for (int col = 0; col < 5; col++) {
                        HomeworkStat h = e.getValue().get(col);
                        // times=0 = 该科纳入作业登记但本期无记录（样例政治行全 '-'）
                        values.add(h == null || h.getTimes() == 0 ? "-" : fmtInt(h.getScore()) + "(" + h.getTimes() + "次)");
                    }
                    row.put("values", values);
                    return row;
                }).toList();

        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("cols", List.of("作业登记综合", "未完成作业", "有待努力", "良好继续加油", "优秀继续保持"));
        hw.put("rows", rows);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjects", subjectList);
        m.put("homework", hw);
        return m;
    }

    // ───────────────── radar（p21） ─────────────────

    private Map<String, Object> buildRadar(Student student, Term term, Term prevTerm, JsonNode sections) {
        List<Grid> grids = orderedGrids();
        JsonNode radarCfg = sections.path("radar");
        StudentAnalysis analysis = studentAnalysisMapper.selectOne(new LambdaQueryWrapper<StudentAnalysis>()
                .eq(StudentAnalysis::getStudentId, student.getId())
                .eq(StudentAnalysis::getTermId, term.getId()).last("LIMIT 1"));

        Map<Long, GridStatTerm> cur = gridStatTerm(student.getId(), term.getId());
        Map<Long, GridStatTerm> prev = prevTerm != null
                ? gridStatTerm(student.getId(), prevTerm.getId()) : cur;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", grids.stream().map(Grid::getName).toList());
        m.put("current", grids.stream().map(g -> Num.of(cur.containsKey(g.getId()) ? cur.get(g.getId()).getScore() : null)).toList());
        m.put("last", grids.stream().map(g -> Num.of(prev.containsKey(g.getId()) ? prev.get(g.getId()).getScore() : null)).toList());
        m.put("max", numNode(radarCfg.path("max")));
        m.put("advantages", analysis != null ? parseStringArray(analysis.getRadarAdvantages()) : List.of());
        m.put("toImprove", analysis != null ? parseStringArray(analysis.getRadarToImprove()) : List.of());
        m.put("motto", radarCfg.path("motto").asText(""));
        m.put("mottoNote", radarCfg.path("mottoNote").asText(""));
        m.put("mottoSource", radarCfg.path("mottoSource").asText(""));
        return m;
    }

    // ───────────────── grids（p22-40） ─────────────────

    private List<Map<String, Object>> buildGrids(Student student, Term term, Term prevTerm, Clazz clazz, Grade grade) {
        List<Grid> grids = orderedGrids();
        List<Indicator> indicators = indicatorMapper.selectList(null);
        Map<Long, Indicator> indicatorById = indicators.stream()
                .collect(Collectors.toMap(Indicator::getId, i -> i));
        Map<Long, String> teacherNames = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, "ADMIN", "HEAD_TEACHER", "TEACHER")).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        Map<Long, GridStatTerm> cur = gridStatTerm(student.getId(), term.getId());
        Map<Long, GridStatTerm> prev = prevTerm != null ? gridStatTerm(student.getId(), prevTerm.getId()) : cur;
        Map<Long, BigDecimal> curClassAvg = gridAvg(classGridAvgMapper, clazz.getId(), term.getId());
        Map<Long, BigDecimal> curGradeAvg = gridAvg(gradeGridAvgMapper, grade.getId(), term.getId());
        Map<Long, BigDecimal> prevClassAvg = prevTerm != null ? gridAvg(classGridAvgMapper, clazz.getId(), prevTerm.getId()) : curClassAvg;
        Map<Long, BigDecimal> prevGradeAvg = prevTerm != null ? gridAvg(gradeGridAvgMapper, grade.getId(), prevTerm.getId()) : curGradeAvg;

        List<Evaluation> evaluations = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, student.getId())
                .ge(Evaluation::getEvalTime, term.getStartDate() == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : term.getStartDate().atStartOfDay())
                .le(Evaluation::getEvalTime, term.getEndDate() == null ? LocalDateTime.of(2099, 12, 31, 0, 0) : term.getEndDate().atStartOfDay())
                .orderByAsc(Evaluation::getEvalTime)
                // 同刻评价的 id 决胜（M7）：登记人/记录组顺序依赖此查询，无决胜时平局顺序随优化器计划漂移
                .orderByAsc(Evaluation::getId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Grid grid : grids) {
            GridStatTerm curStat = cur.get(grid.getId());
            if (curStat == null) {
                continue;
            }
            GridStatTerm prevStat = prev.get(grid.getId());

            List<GridStatWeek> weeks = gridStatWeekMapper.selectList(new LambdaQueryWrapper<GridStatWeek>()
                    .eq(GridStatWeek::getStudentId, student.getId()).eq(GridStatWeek::getTermId, term.getId())
                    .eq(GridStatWeek::getGridId, grid.getId()).orderByAsc(GridStatWeek::getWeekNo));

            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", grid.getName());
            g.put("points", Num.of(curStat.getPoints()));
            g.put("count", curStat.getEvalCount());
            g.put("kinds", curStat.getKindCount());
            Map<String, Object> curMap = new LinkedHashMap<>();
            curMap.put("mine", Num.of(curStat.getScore()));
            curMap.put("classAvg", Num.of(curClassAvg.get(grid.getId())));
            curMap.put("gradeAvg", Num.of(curGradeAvg.get(grid.getId())));
            curMap.put("axisMax", Num.of(grid.getCurAxisMax()));
            curMap.put("step", Num.of(grid.getCurAxisStep()));
            g.put("cur", curMap);
            Map<String, Object> prevMap = new LinkedHashMap<>();
            prevMap.put("mine", Num.of(prevStat != null ? prevStat.getScore() : null));
            prevMap.put("classAvg", Num.of(prevClassAvg.get(grid.getId())));
            prevMap.put("gradeAvg", Num.of(prevGradeAvg.get(grid.getId())));
            prevMap.put("axisMax", Num.of(grid.getPrevAxisMax()));
            prevMap.put("step", Num.of(grid.getPrevAxisStep()));
            g.put("prev", prevMap);
            Map<String, Object> weekly = new LinkedHashMap<>();
            weekly.put("weeks", weekLabels(weeks.stream().map(GridStatWeek::getWeekNo).toList()));
            weekly.put("mine", weeks.stream().map(w -> Num.of(w.getScore())).toList());
            weekly.put("min", Num.of(grid.getWeekMin()));
            weekly.put("max", Num.of(grid.getWeekMax()));
            weekly.put("step", Num.of(grid.getWeekStep()));
            g.put("weekly", weekly);
            List<Map<String, Object>> records = buildRecords(evaluations, indicatorById, teacherNames, grid);
            g.put("records", records);
            g.put("recordPageCount", (records.size() + 6) / 7);
            out.add(g);
        }
        return out;
    }

    /** 记录卡：按 (title, indicator) 分组求和；组序 = 组内最早评价时间；登记人 ≥5 人时 more=true */
    private List<Map<String, Object>> buildRecords(List<Evaluation> evaluations,
                                                   Map<Long, Indicator> indicatorById,
                                                   Map<Long, String> teacherNames, Grid grid) {
        LinkedHashMap<String, List<Evaluation>> groups = new LinkedHashMap<>();
        for (Evaluation e : evaluations) {
            Indicator ind = indicatorById.get(e.getIndicatorId());
            if (ind == null || !ind.getGridId().equals(grid.getId())) {
                continue;
            }
            groups.computeIfAbsent(e.getTitle() + " " + ind.getName(), k -> new ArrayList<>()).add(e);
        }
        List<Map.Entry<String, List<Evaluation>>> ordered = groups.entrySet().stream()
                .sorted(Comparator.comparing(
                        (Map.Entry<String, List<Evaluation>> e) -> e.getValue().get(0).getEvalTime()))
                .toList();

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map.Entry<String, List<Evaluation>> entry : ordered) {
            List<Evaluation> group = entry.getValue();
            Indicator ind = indicatorById.get(group.get(0).getIndicatorId());
            BigDecimal sum = group.stream().map(Evaluation::getScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (Evaluation e : group) {
                String n = teacherNames.get(e.getTeacherId());
                if (n != null) {
                    names.add(n);
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("title", group.get(0).getTitle());
            r.put("subtitle", grid.getName() + "-" + ind.getName());
            r.put("score", fmtSigned(sum));
            r.put("registrants", String.join("、", names));
            r.put("more", names.size() >= 5);
            records.add(r);
        }
        return records;
    }

    // ───────────────── activities（p42 空态由渲染核心处理） ─────────────────

    private List<Map<String, Object>> buildActivities(Student student, Term term) {
        List<ActivitySignup> signups = activitySignupMapper.selectList(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getStudentId, student.getId()));
        if (signups.isEmpty()) {
            return List.of();
        }
        Map<Long, Activity> activities = activityMapper.selectBatchIds(
                        signups.stream().map(ActivitySignup::getActivityId).toList()).stream()
                .collect(Collectors.toMap(Activity::getId, a -> a));
        return signups.stream()
                .filter(s -> activities.containsKey(s.getActivityId()))
                .map(s -> {
                    Activity a = activities.get(s.getActivityId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", a.getTitle());
                    m.put("time", a.getStartTime() != null ? a.getStartTime().format(DATE_FMT) : "");
                    m.put("place", a.getPlace());
                    m.put("type", a.getType()); // 批40c：画像取「参与最多的课程类别」（模板未消费，零契约影响）
                    m.put("award", s.getAward());
                    m.put("performance", s.getPerformance());
                    return m;
                }).toList();
    }

    // ───────────────── coin（p44-46 成长银行） ─────────────────

    private Map<String, Object> buildCoin(Student student, Term term, JsonNode sections) {
        JsonNode coinCfg = sections.path("coin");
        CoinAccount account = coinAccountMapper.selectOne(new LambdaQueryWrapper<CoinAccount>()
                .eq(CoinAccount::getStudentId, student.getId()));
        List<CoinExpense> expenses = coinExpenseMapper.selectList(new LambdaQueryWrapper<CoinExpense>()
                .eq(CoinExpense::getStudentId, student.getId()).eq(CoinExpense::getTermId, term.getId()));
        BigDecimal spent = expenses.stream().map(CoinExpense::getCoin)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal current = account != null ? account.getCurrentCoin() : BigDecimal.ZERO;
        BigDecimal total = account != null ? account.getTotalCoin() : BigDecimal.ZERO;
        BigDecimal exchangeable = total.subtract(current).subtract(spent);

        CoinRate coinRate = coinRateMapper.selectOne(new LambdaQueryWrapper<CoinRate>()
                .orderByDesc(CoinRate::getEffectiveDate).last("LIMIT 1"));
        CoinStat coinStat = coinStatMapper.selectOne(new LambdaQueryWrapper<CoinStat>()
                .eq(CoinStat::getStudentId, student.getId()).eq(CoinStat::getTermId, term.getId())
                .last("LIMIT 1"));

        List<CoinWeek> coinWeeks = coinWeekMapper.selectList(new LambdaQueryWrapper<CoinWeek>()
                .eq(CoinWeek::getStudentId, student.getId()).eq(CoinWeek::getTermId, term.getId())
                .orderByAsc(CoinWeek::getWeekNo));
        List<Integer> weekNos = coinWeeks.stream().map(CoinWeek::getWeekNo).toList();

        // 收入榜：按模块聚合；TOP5 按展示序（含缺位），最少3 按金额升序取3再倒序
        List<CoinIncome> incomes = coinIncomeMapper.selectList(new LambdaQueryWrapper<CoinIncome>()
                .eq(CoinIncome::getStudentId, student.getId()).eq(CoinIncome::getTermId, term.getId()));
        LinkedHashMap<String, List<CoinIncome>> byModule = incomes.stream()
                .collect(Collectors.groupingBy(CoinIncome::getModule, LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<CoinIncome>>> moduleOrder = byModule.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue().get(0).getDisplayOrder()))
                .toList();
        List<Map<String, Object>> incomeTop5 = new ArrayList<>();
        for (Map.Entry<String, List<CoinIncome>> e : moduleOrder) {
            if (incomeTop5.size() >= 5) {
                break;
            }
            BigDecimal sum = e.getValue().stream().map(CoinIncome::getCoin)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("value", e.getValue().stream().allMatch(i -> i.getCoin() == null) ? null : Num.of(sum));
            incomeTop5.add(m);
        }
        while (incomeTop5.size() < 5) {
            Map<String, Object> pad = new LinkedHashMap<>();
            pad.put("name", "-");
            pad.put("value", null);
            incomeTop5.add(pad);
        }
        List<Map.Entry<String, List<CoinIncome>>> nonNullModules = moduleOrder.stream()
                .filter(e -> e.getValue().stream().anyMatch(i -> i.getCoin() != null)).toList();
        List<Map<String, Object>> incomeLeast3 = nonNullModules.stream()
                .sorted(Comparator.comparing(e -> e.getValue().stream()
                        .filter(i -> i.getCoin() != null).map(CoinIncome::getCoin)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)))
                .limit(3).sorted(Comparator.comparing((Map.Entry<String, List<CoinIncome>> e) ->
                        e.getValue().stream().filter(i -> i.getCoin() != null).map(CoinIncome::getCoin)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)).reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", Num.of(e.getValue().stream().filter(i -> i.getCoin() != null)
                            .map(CoinIncome::getCoin).reduce(BigDecimal.ZERO, BigDecimal::add)));
                    return m;
                }).toList();

        Map<String, BigDecimal> expenseByItem = expenses.stream().collect(Collectors.groupingBy(
                CoinExpense::getItem, Collectors.reducing(BigDecimal.ZERO, CoinExpense::getCoin, BigDecimal::add)));
        List<Map<String, Object>> expenseTop5 = expenseByItem.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", Num.of(e.getValue()));
                    return m;
                }).toList();
        List<Map<String, Object>> dailyTop10 = expenses.stream()
                .sorted(Comparator.comparing(CoinExpense::getCoin).reversed()
                        .thenComparing(Comparator.comparing(CoinExpense::getCreateTime).reversed()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", e.getCreateTime() != null ? e.getCreateTime().format(DATE_FMT) : "");
                    m.put("item", e.getItem());
                    m.put("amount", fmtInt(e.getCoin()) + "能量币");
                    return m;
                }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", Num.of(current));
        m.put("spent", Num.of(spent));
        m.put("exchangeable", Num.of(exchangeable));
        m.put("total", Num.of(total));
        LocalDate eff = coinRate != null ? coinRate.getEffectiveDate() : null;
        m.put("rateMonth", eff != null ? String.format("%02d", eff.getMonthValue()) : "");
        m.put("rateDay", eff != null ? String.format("%02d", eff.getDayOfMonth()) : "");
        m.put("rate", Num.of(coinRate != null ? coinRate.getRate() : null));
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("mine", List.of(Num.of(total), Num.of(current), Num.of(spent), Num.of(exchangeable)));
        compare.put("classAvg", parseNumArray(coinStat != null ? coinStat.getCompareClassAvg() : null));
        compare.put("gradeAvg", parseNumArray(coinStat != null ? coinStat.getCompareGradeAvg() : null));
        compare.put("max", numNode(coinCfg.path("compare").path("max")));
        compare.put("step", numNode(coinCfg.path("compare").path("step")));
        m.put("compare", compare);
        Map<String, Object> weeklyIncome = new LinkedHashMap<>();
        weeklyIncome.put("weeks", weekLabels(weekNos));
        weeklyIncome.put("mine", coinWeeks.stream().map(w -> Num.of(w.getInMine())).toList());
        weeklyIncome.put("classAvg", coinWeeks.stream().map(w -> Num.of(w.getInClass())).toList());
        weeklyIncome.put("gradeAvg", coinWeeks.stream().map(w -> Num.of(w.getInGrade())).toList());
        weeklyIncome.put("max", numNode(coinCfg.path("weeklyIncome").path("max")));
        weeklyIncome.put("step", numNode(coinCfg.path("weeklyIncome").path("step")));
        m.put("weeklyIncome", weeklyIncome);
        Map<String, Object> weeklyExpense = new LinkedHashMap<>();
        weeklyExpense.put("weeks", weekLabels(weekNos));
        weeklyExpense.put("mine", coinWeeks.stream().map(w -> Num.of(w.getOutMine())).toList());
        weeklyExpense.put("classAvg", coinWeeks.stream().map(w -> Num.of(w.getOutClass())).toList());
        weeklyExpense.put("gradeAvg", coinWeeks.stream().map(w -> Num.of(w.getOutGrade())).toList());
        weeklyExpense.put("max", numNode(coinCfg.path("weeklyExpense").path("max")));
        weeklyExpense.put("step", numNode(coinCfg.path("weeklyExpense").path("step")));
        m.put("weeklyExpense", weeklyExpense);
        m.put("incomeTop5", incomeTop5);
        Map<String, Object> incomeTopAxis = new LinkedHashMap<>();
        incomeTopAxis.put("max", numNode(coinCfg.path("incomeTop").path("max")));
        incomeTopAxis.put("step", numNode(coinCfg.path("incomeTop").path("step")));
        m.put("incomeTopAxis", incomeTopAxis);
        m.put("incomeLeast3", incomeLeast3);
        Map<String, Object> incomeLeastAxis = new LinkedHashMap<>();
        incomeLeastAxis.put("max", numNode(coinCfg.path("incomeLeast").path("max")));
        incomeLeastAxis.put("step", numNode(coinCfg.path("incomeLeast").path("step")));
        m.put("incomeLeastAxis", incomeLeastAxis);
        m.put("expenseTop5", expenseTop5);
        Map<String, Object> expenseTopAxis = new LinkedHashMap<>();
        expenseTopAxis.put("max", numNode(coinCfg.path("expenseTop").path("max")));
        expenseTopAxis.put("step", numNode(coinCfg.path("expenseTop").path("step")));
        m.put("expenseTopAxis", expenseTopAxis);
        m.put("dailyTop10", dailyTop10);
        return m;
    }

    // ───────────────── moments（成长掠影 p51，可选页：无微光整页跳过） ─────────────────

    /** 该生本学期微光（学期区间与 AiDraftService.injectMomentFacts 同口径），倒序最多 6 张 */
    private List<Map<String, Object>> buildMoments(Student student, Term term) {
        List<Long> momentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getStudentId, student.getId()))
                .stream().map(MomentStudent::getMomentId).toList();
        if (momentIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<Moment>()
                .in(Moment::getId, momentIds)
                .orderByDesc(Moment::getCreateTime).orderByDesc(Moment::getId)
                .last("LIMIT 6");
        // 当前学期截到今天；历史学期按学期区间（两端齐备才过滤，防御未配置区间）
        if (term.getStartDate() != null && term.getEndDate() != null) {
            LocalDate end = term.getIsCurrent() != null && term.getIsCurrent() == 1
                    ? LocalDate.now() : term.getEndDate();
            qw.between(Moment::getCreateTime, term.getStartDate().atStartOfDay(),
                    end.plusDays(1).atStartOfDay());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Moment m : momentMapper.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", m.getCreateTime().toLocalDate().format(DATE_FMT));
            row.put("sceneTag", m.getSceneTag());
            row.put("note", m.getNote() == null ? "" : m.getNote());
            row.put("photo", photoDataUri(m.getPhotoUrl()));
            out.add(row);
        }
        return out;
    }

    /** MinIO 原图 → 最长边 720px JPEG → data URI（6 张约 1MB，renderer 走 file:// 访问不了 API 必须内嵌；
     *  读失败返回空串，模板渲染占位框，不阻断整份报告） */
    private String photoDataUri(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return "";
        }
        try (java.io.InputStream in = pdfStore.download(objectName)) {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) {
                return "";
            }
            // 垫白底（png 透明直接写 jpg 会偏色）+ 双线性缩放一步到位
            double scale = Math.min(1, 720.0 / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "jpg", bos);
            return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    // ───────────────── growthSymbol / comprehensive / 寄语 ─────────────────

    private Map<String, Object> buildGrowthSymbol(Student student, Term term) {
        GrowthSymbolStat stat = growthSymbolStatMapper.selectOne(new LambdaQueryWrapper<GrowthSymbolStat>()
                .eq(GrowthSymbolStat::getStudentId, student.getId())
                .eq(GrowthSymbolStat::getTermId, term.getId()).last("LIMIT 1"));
        List<GrowthLevel> levels = growthLevelMapper.selectList(
                new LambdaQueryWrapper<GrowthLevel>().orderByAsc(GrowthLevel::getLevel));
        BigDecimal score = stat != null ? stat.getScore() : BigDecimal.ZERO;
        int level = levels.stream().filter(l -> l.getMinScore().compareTo(score) <= 0)
                .mapToInt(GrowthLevel::getLevel).max().orElse(1);
        BigDecimal toNext = levels.stream().filter(l -> l.getLevel() > level)
                .min(Comparator.comparingInt(GrowthLevel::getLevel))
                .map(l -> l.getMinScore().subtract(score)).orElse(BigDecimal.ZERO);

        Map<String, Object> m = new LinkedHashMap<>();
        // 样例契约：score/toNext 页面原样输出（th:text），整值也是小数形态（1020.0/172.0）→ 恒 Double
        m.put("score", Num.ofDouble(score));
        m.put("level", level);
        m.put("toNext", Num.ofDouble(toNext));
        return m;
    }

    private Map<String, Object> buildComprehensive(Student student, Term term) {
        Comprehensive c = comprehensiveMapper.selectOne(new LambdaQueryWrapper<Comprehensive>()
                .eq(Comprehensive::getStudentId, student.getId())
                .eq(Comprehensive::getTermId, term.getId()).last("LIMIT 1"));
        List<Map<String, Object>> dims = new ArrayList<>();
        if (c != null) {
            dims.add(dim("思想品德", c.getMoral()));
            dims.add(dim("学业水平", c.getAbility()));
            dims.add(dim("身心健康", c.getHealth()));
            dims.add(dim("艺术素养", c.getAesthetic()));
            dims.add(dim("社会实践", c.getPractice()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dims", dims);
        m.put("finalLevel", c != null ? c.getFinalLevel() : "");
        return m;
    }

    private Map<String, Object> dim(String name, String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("level", level);
        return m;
    }

    // 批39⑦：学生改进方向页整体取消（学业差距表+九维弱项表），报告数据面与 PDF 模板同步移除

    private String buildHeadTeacherComment(Student student, Term term) {
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getStudentId, student.getId()).eq(Comment::getTermId, term.getId())
                .eq(Comment::getType, "班主任")
                .in(Comment::getStatus, "已确认", "已修改")
                .orderByDesc(Comment::getUpdateTime));
        return comments.isEmpty() ? "" : comments.get(0).getContent();
    }

    // ───────────────── 小工具 ─────────────────

    private JsonNode parseSections(String json) {
        try {
            return OM.readTree(json == null || json.isEmpty() ? "{}" : json);
        } catch (Exception e) {
            throw new BizException(500, "报告模板 sections JSON 解析失败: " + e.getMessage());
        }
    }

    private Exam latestExam(Long termId) {
        return examMapper.selectOne(new LambdaQueryWrapper<Exam>()
                .eq(Exam::getTermId, termId)
                .orderByDesc(Exam::getExamDate).orderByDesc(Exam::getId)
                .last("LIMIT 1"));
    }

    private Map<Long, Subject> allSubjects() {
        return subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, s -> s));
    }

    private List<Grid> orderedGrids() {
        return gridMapper.selectList(new LambdaQueryWrapper<Grid>().orderByAsc(Grid::getSort));
    }

    private Map<Long, GridStatTerm> gridStatTerm(Long studentId, Long termId) {
        return gridStatTermMapper.selectList(new LambdaQueryWrapper<GridStatTerm>()
                        .eq(GridStatTerm::getStudentId, studentId).eq(GridStatTerm::getTermId, termId)).stream()
                .collect(Collectors.toMap(GridStatTerm::getGridId, g -> g));
    }

    private Map<Long, BigDecimal> gridAvg(ClassGridAvgMapper mapper, Long classId, Long termId) {
        return mapper.selectList(new LambdaQueryWrapper<ClassGridAvg>()
                        .eq(ClassGridAvg::getClassId, classId).eq(ClassGridAvg::getTermId, termId)).stream()
                .collect(Collectors.toMap(ClassGridAvg::getGridId, ClassGridAvg::getAvgScore));
    }

    private Map<Long, BigDecimal> gridAvg(GradeGridAvgMapper mapper, Long gradeId, Long termId) {
        return mapper.selectList(new LambdaQueryWrapper<GradeGridAvg>()
                        .eq(GradeGridAvg::getGradeId, gradeId).eq(GradeGridAvg::getTermId, termId)).stream()
                .collect(Collectors.toMap(GradeGridAvg::getGridId, GradeGridAvg::getAvgScore));
    }

    private Map<String, Object> triple(BigDecimal mine, BigDecimal classAvg, BigDecimal gradeAvg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mine", Num.of(mine));
        m.put("classAvg", Num.of(classAvg));
        m.put("gradeAvg", Num.of(gradeAvg));
        return m;
    }

    private Object numNode(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isInt() ? node.asInt() : node.decimalValue().stripTrailingZeros().doubleValue();
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return OM.readValue(json, OM.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Object> parseNumArray(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode arr = OM.readTree(json);
            List<Object> out = new ArrayList<>();
            arr.forEach(n -> out.add(n.isInt() ? n.asInt() : n.decimalValue().stripTrailingZeros().doubleValue()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> weekNos(int max) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            out.add(i);
        }
        return out;
    }

    private List<String> weekLabels(List<Integer> weekNos) {
        return weekNos.stream().map(n -> "第" + n + "周").toList();
    }

    private <T> List<Object> series(List<Integer> weekNos, Map<Integer, T> byWeek,
                                    java.util.function.Function<T, BigDecimal> getter) {
        return weekNos.stream().map(n -> Num.of(byWeek.containsKey(n) ? getter.apply(byWeek.get(n)) : null)).toList();
    }

    /** "+3" / "-6" / "+0"（expand_golden 记录卡分值格式） */
    private String fmtSigned(BigDecimal sum) {
        BigDecimal stripped = sum.stripTrailingZeros();
        String body = stripped.scale() <= 0 ? String.valueOf(stripped.intValueExact())
                : String.valueOf(stripped.doubleValue());
        return (stripped.signum() < 0 ? "" : "+") + body;
    }

    /** 77 / -5 / 0（homework、dailyTop 数值格式） */
    private String fmtInt(BigDecimal bd) {
        BigDecimal stripped = bd.stripTrailingZeros();
        return stripped.scale() <= 0 ? String.valueOf(stripped.intValueExact())
                : String.valueOf(stripped.doubleValue());
    }

    /** procW step 规则（expand_golden.py）：max==3 → 1.0，否则 max/4 保留 3 位 */
    private Object procWStep(BigDecimal procWMax) {
        if (procWMax == null) {
            return null;
        }
        if (procWMax.compareTo(BigDecimal.valueOf(3)) == 0) {
            return 1.0;
        }
        return procWMax.divide(BigDecimal.valueOf(4), 3, RoundingMode.HALF_UP)
                .stripTrailingZeros().doubleValue();
    }

    /** regularScores 两表顺序：regular_sort（未配置时退回 sort） */
    private int regularSort(Subject s) {
        return s.getRegularSort() != null ? s.getRegularSort() : (s.getSort() != null ? s.getSort() : 99);
    }
}
