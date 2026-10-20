package com.aischool.server.service.report;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.*;
import com.aischool.server.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学年/在校报告数据（批26 报告三类）：跨学期纵向汇总，与学期报告（ReportDataBuilder 单期锚定）
 * 是两套契约——学期报告=过程细节（周线/记录卡），本报告=成长轨迹（学期对比+大事记+汇总）。
 * 学业仅个人成绩（无班/年级最高、无排名）→ 单版本同时供教师/家长，天然满足家长版口径。
 */
@Service
@RequiredArgsConstructor
public class AnnualDataBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final UserMapper userMapper;
    private final SubjectMapper subjectMapper;
    private final ExamMapper examMapper;
    private final ScoreMapper scoreMapper;
    private final GridMapper gridMapper;
    private final GridStatTermMapper gridStatTermMapper;
    private final EvaluationMapper evaluationMapper;
    private final ActivitySignupMapper activitySignupMapper;
    private final ActivityMapper activityMapper;
    private final HonorMapper honorMapper;
    private final MomentMapper momentMapper;
    private final MomentStudentMapper momentStudentMapper;
    private final CoinIncomeMapper coinIncomeMapper;
    private final ComprehensiveMapper comprehensiveMapper;
    private final CommentMapper commentMapper;
    private final ReportTemplateMapper reportTemplateMapper;
    private final PdfStoreService pdfStore;

    /** terms 必须升序、非空；scopeLabel=「学年报告」/「在校报告」 */
    public Map<String, Object> build(Long studentId, List<Term> terms, String scopeLabel) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(404, "学生不存在: " + studentId);
        }
        Clazz clazz = clazzMapper.selectById(student.getClassId());
        if (clazz == null) {
            throw new BizException(500, "学生未分配班级: " + studentId);
        }
        Grade grade = gradeMapper.selectById(clazz.getGradeId());
        User headTeacher = clazz.getHeadTeacherId() != null ? userMapper.selectById(clazz.getHeadTeacherId()) : null;
        ReportTemplate template = reportTemplateMapper.selectOne(new LambdaQueryWrapper<ReportTemplate>()
                .eq(ReportTemplate::getStatus, "启用").last("LIMIT 1"));
        if (template == null) {
            throw new BizException(500, "未配置启用的报告模板");
        }

        LocalDate rangeStart = terms.stream().map(Term::getStartDate)
                .filter(Objects::nonNull).min(Comparable::compareTo)
                .orElse(LocalDate.of(2000, 1, 1));
        LocalDate rangeEnd = terms.stream().map(Term::getEndDate)
                .filter(Objects::nonNull).max(Comparable::compareTo)
                .orElse(LocalDate.of(2099, 12, 31));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("school", buildSchool(template, terms, scopeLabel, rangeStart, rangeEnd));
        data.put("student", buildStudent(student, clazz, grade, headTeacher));
        data.put("stats", buildStats(student, terms, rangeStart, rangeEnd));
        data.put("nineGrid", buildNineGrid(student, terms));
        data.put("academic", buildAcademic(student, terms));
        data.put("comprehensive", buildComprehensive(student, terms));
        data.put("honors", buildHonors(student, rangeStart, rangeEnd));
        data.put("moments", buildMoments(student, rangeStart, rangeEnd));
        data.put("comments", buildComments(student, terms));
        return data;
    }

    private Map<String, Object> buildSchool(ReportTemplate template, List<Term> terms, String scopeLabel,
                                            LocalDate start, LocalDate end) {
        Map<String, Object> school = new LinkedHashMap<>();
        school.put("name", template.getSchoolName());
        school.put("scopeLabel", scopeLabel);
        school.put("termRange", fmt(start) + " ~ " + fmt(end));
        school.put("termCount", terms.size());
        return school;
    }

    private Map<String, Object> buildStudent(Student s, Clazz c, Grade g, User headTeacher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("studentNo", s.getStudentNo());
        m.put("grade", g == null || g.getName() == null ? "" : g.getName());
        m.put("clazz", c == null || c.getName() == null ? "" : c.getName());
        m.put("headTeacher", headTeacher != null && headTeacher.getRealName() != null
                ? headTeacher.getRealName() : "");
        m.put("photoUrl", s.getPhotoUrl() == null ? "" : s.getPhotoUrl());
        return m;
    }

    /** 总览五格：评价/活动/荣誉/微光/扬长币收入（区间内） */
    private Map<String, Object> buildStats(Student student, List<Term> terms,
                                           LocalDate start, LocalDate end) {
        long evaluations = evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, student.getId())
                .ge(Evaluation::getEvalTime, start.atStartOfDay())
                .le(Evaluation::getEvalTime, end.atTime(23, 59, 59)));
        List<Long> termIds = terms.stream().map(Term::getId).toList();
        long activities = activitySignupMapper.selectCount(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getStudentId, student.getId()));
        long honors = honorMapper.selectCount(new LambdaQueryWrapper<Honor>()
                .eq(Honor::getStudentId, student.getId())
                .eq(Honor::getConfirmStatus, "已确认")
                .ge(Honor::getHonorDate, start).le(Honor::getHonorDate, end));
        List<Long> momentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getStudentId, student.getId()))
                .stream().map(MomentStudent::getMomentId).toList();
        long moments = momentIds.isEmpty() ? 0 : momentMapper.selectCount(new LambdaQueryWrapper<Moment>()
                .in(Moment::getId, momentIds)
                .ge(Moment::getCreateTime, start.atStartOfDay())
                .le(Moment::getCreateTime, end.atTime(23, 59, 59)));
        List<CoinIncome> incomes = coinIncomeMapper.selectList(new LambdaQueryWrapper<CoinIncome>()
                .eq(CoinIncome::getStudentId, student.getId())
                .in(CoinIncome::getTermId, termIds));
        double coinTotal = incomes.stream().map(CoinIncome::getCoin)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evaluations", evaluations);
        m.put("activities", activities);
        m.put("honors", honors);
        m.put("moments", moments);
        m.put("coinTotal", Num.ofDouble(BigDecimal.valueOf(coinTotal)));
        return m;
    }

    /** 九维学期对比：雷达多系列（每学期一条）+ 明细表（维度 × 学期积分） */
    private Map<String, Object> buildNineGrid(Student student, List<Term> terms) {
        List<Grid> grids = gridMapper.selectList(new LambdaQueryWrapper<Grid>().orderByAsc(Grid::getSort));
        Map<Long, List<GridStatTerm>> byTerm = new LinkedHashMap<>();
        for (Term t : terms) {
            byTerm.put(t.getId(), gridStatTermMapper.selectList(new LambdaQueryWrapper<GridStatTerm>()
                    .eq(GridStatTerm::getStudentId, student.getId())
                    .eq(GridStatTerm::getTermId, t.getId())));
        }
        // 雷达坐标上限：各维 curAxisMax 的最大值（多期共用一个轴；Grid 轴上限为 BigDecimal）
        int radarMax = grids.stream().map(Grid::getCurAxisMax)
                .filter(Objects::nonNull).mapToInt(BigDecimal::intValue).max().orElse(100);

        List<Map<String, Object>> series = new ArrayList<>();
        for (Term t : terms) {
            Map<Long, GridStatTerm> byGrid = byTerm.get(t.getId()).stream()
                    .collect(Collectors.toMap(GridStatTerm::getGridId, g -> g, (a, b) -> a));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("term", t.getName());
            s.put("values", grids.stream().map(g -> {
                GridStatTerm st = byGrid.get(g.getId());
                return st == null ? null : Num.of(st.getScore());
            }).toList());
            BigDecimal total = byTerm.get(t.getId()).stream()
                    .map(GridStatTerm::getPoints).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            s.put("points", Num.of(total));
            series.add(s);
        }
        // 明细表行：维度 | 各学期积分 | 学期积分小计
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Grid g : grids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", g.getName());
            List<Object> values = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            boolean any = false;
            for (Term t : terms) {
                GridStatTerm st = byTerm.get(t.getId()).stream()
                        .filter(x -> x.getGridId().equals(g.getId())).findFirst().orElse(null);
                values.add(Num.of(st == null ? null : st.getPoints()));
                if (st != null && st.getPoints() != null) {
                    sum = sum.add(st.getPoints());
                    any = true;
                }
            }
            if (!any) {
                continue;   // 该生全期无此维记录则不出行（空表由模板兜底）
            }
            row.put("values", values);
            row.put("sum", Num.of(sum));
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", grids.stream().map(Grid::getName).toList());
        m.put("radarMax", radarMax);
        m.put("series", series);
        m.put("rows", rows);
        return m;
    }

    /** 学业进步：各学期期末考试个人总分趋势 + 区间内最新一场考试各科成绩（仅个人） */
    private Map<String, Object> buildAcademic(Student student, List<Term> terms) {
        Map<Long, Subject> subjects = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, s -> s));
        List<Map<String, Object>> trend = new ArrayList<>();
        Exam latestExam = null;
        for (Term t : terms) {
            Exam exam = examMapper.selectOne(new LambdaQueryWrapper<Exam>()
                    .eq(Exam::getTermId, t.getId())
                    .orderByDesc(Exam::getExamDate).orderByDesc(Exam::getId)
                    .last("LIMIT 1"));
            List<Score> scores = exam != null ? scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                    .eq(Score::getExamId, exam.getId()).eq(Score::getStudentId, student.getId())) : List.of();
            BigDecimal total = scores.stream().filter(sc -> sc.getScore() != null) // 批63c 跳过总分等级行/清分留级行
                    .map(Score::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("term", t.getName());
            point.put("hasExam", !scores.isEmpty());
            point.put("total", Num.of(total));
            trend.add(point);
            if (!scores.isEmpty() && (latestExam == null
                    || (exam.getExamDate() != null && latestExam.getExamDate() != null
                        && exam.getExamDate().isAfter(latestExam.getExamDate())))) {
                latestExam = exam;
            }
        }
        List<Score> latestScores = latestExam != null ? scoreMapper.selectList(
                new LambdaQueryWrapper<Score>()
                        .eq(Score::getExamId, latestExam.getId())
                        .eq(Score::getStudentId, student.getId())) : List.of();
        List<Map<String, Object>> subjectsOut = latestScores.stream()
                .filter(sc -> sc.getScore() != null && subjects.containsKey(sc.getSubjectId())) // 批63c 同上
                .sorted(Comparator.comparing((Score sc) -> subjects.get(sc.getSubjectId()).getSort()))
                .map(sc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", subjects.get(sc.getSubjectId()).getName());
                    m.put("score", Num.of(sc.getScore()));
                    return m;
                }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trend", trend);
        m.put("latestExamDate", latestExam != null && latestExam.getExamDate() != null
                ? latestExam.getExamDate().format(DATE_FMT) : "");
        m.put("subjects", subjectsOut);
        return m;
    }

    /** 综合素质：学期 × 五维等第表 */
    private List<Map<String, Object>> buildComprehensive(Student student, List<Term> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Term t : terms) {
            Comprehensive c = comprehensiveMapper.selectOne(new LambdaQueryWrapper<Comprehensive>()
                    .eq(Comprehensive::getStudentId, student.getId())
                    .eq(Comprehensive::getTermId, t.getId()).last("LIMIT 1"));
            if (c == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", t.getName());
            row.put("moral", c.getMoral());
            row.put("ability", c.getAbility());
            row.put("health", c.getHealth());
            row.put("aesthetic", c.getAesthetic());
            row.put("practice", c.getPractice());
            row.put("final", c.getFinalLevel());
            out.add(row);
        }
        return out;
    }

    /** 荣誉：区间内已确认，日期倒序（上限 30 条，超出模板注「等 N 项」） */
    private Map<String, Object> buildHonors(Student student, LocalDate start, LocalDate end) {
        List<Honor> honors = honorMapper.selectList(new LambdaQueryWrapper<Honor>()
                .eq(Honor::getStudentId, student.getId())
                .eq(Honor::getConfirmStatus, "已确认")
                .ge(Honor::getHonorDate, start).le(Honor::getHonorDate, end)
                .orderByDesc(Honor::getHonorDate).orderByDesc(Honor::getId)
                .last("LIMIT 31"));
        boolean more = honors.size() > 30;
        if (more) {
            honors = honors.subList(0, 30);
        }
        List<Map<String, Object>> rows = honors.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.getName() == null ? "荣誉证书" : h.getName());
            m.put("level", h.getLevel() == null ? "" : h.getLevel());
            m.put("issuer", h.getIssuer() == null ? "" : h.getIssuer());
            m.put("date", h.getHonorDate() != null ? h.getHonorDate().format(DATE_FMT) : "");
            return m;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("more", more);
        return m;
    }

    /** 成长掠影：区间内最新 6 张微光（与学期报告同款式，文字卡也计入） */
    private List<Map<String, Object>> buildMoments(Student student, LocalDate start, LocalDate end) {
        List<Long> momentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getStudentId, student.getId()))
                .stream().map(MomentStudent::getMomentId).toList();
        if (momentIds.isEmpty()) {
            return List.of();
        }
        List<Moment> moments = momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                .in(Moment::getId, momentIds)
                .ge(Moment::getCreateTime, start.atStartOfDay())
                .le(Moment::getCreateTime, end.atTime(23, 59, 59))
                .orderByDesc(Moment::getCreateTime).orderByDesc(Moment::getId)
                .last("LIMIT 6"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Moment mm : moments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", mm.getCreateTime().toLocalDate().format(DATE_FMT));
            row.put("sceneTag", mm.getSceneTag());
            row.put("note", mm.getNote() == null ? "" : mm.getNote());
            row.put("photo", photoDataUri(mm.getPhotoUrl() == null ? null
                    : mm.getPhotoUrl().split(",")[0])); // 批62 多图：报告版式取首张
            out.add(row);
        }
        return out;
    }

    /** 各学期班主任寄语（已确认/已修改，取该学期最新一条） */
    private List<Map<String, Object>> buildComments(Student student, List<Term> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Term t : terms) {
            List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getStudentId, student.getId()).eq(Comment::getTermId, t.getId())
                    .eq(Comment::getType, "班主任")
                    .in(Comment::getStatus, "已确认", "已修改")
                    .orderByDesc(Comment::getUpdateTime).last("LIMIT 1"));
            if (comments.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", t.getName());
            row.put("content", comments.get(0).getContent());
            out.add(row);
        }
        return out;
    }

    /** MinIO 原图 → 最长边 720px JPEG data URI（读失败空串占位，不阻断报告）——同 ReportDataBuilder */
    private String photoDataUri(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return "";
        }
        try (java.io.InputStream in = pdfStore.download(objectName)) {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) {
                return "";
            }
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

    private String fmt(LocalDate d) {
        return d != null ? d.format(DATE_FMT) : "";
    }
}
