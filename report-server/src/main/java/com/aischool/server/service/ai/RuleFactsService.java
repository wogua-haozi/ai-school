package com.aischool.server.service.ai;

import com.aischool.server.entity.*;
import com.aischool.server.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎：为 AI 草稿准备硬数字事实（铁律：硬数字一律走规则引擎，LLM 只做语言组织）。
 * 产出 facts 结构直接喂 Prompt；数字与库表一致，杜绝幻觉。
 */
@Service
@RequiredArgsConstructor
public class RuleFactsService {

    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final UserMapper userMapper;
    private final TermMapper termMapper;
    private final SubjectMapper subjectMapper;
    private final ExamMapper examMapper;
    private final ExamSubjectMapper examSubjectMapper;
    private final ScoreMapper scoreMapper;
    private final GridMapper gridMapper;
    private final GridStatTermMapper gridStatTermMapper;
    private final EvaluationMapper evaluationMapper;
    private final StudentAnalysisMapper analysisMapper;
    private final ComprehensiveMapper comprehensiveMapper;

    public Map<String, Object> facts(Long studentId, Long termId) {
        Student student = studentMapper.selectById(studentId);
        Clazz clazz = clazzMapper.selectById(student.getClassId());
        Grade grade = gradeMapper.selectById(clazz.getGradeId());
        User headTeacher = clazz.getHeadTeacherId() != null ? userMapper.selectById(clazz.getHeadTeacherId()) : null;
        Term term = termMapper.selectById(termId);
        Map<Long, Subject> subjects = subjectMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Subject::getId, s -> s));
        Exam exam = examMapper.selectOne(new LambdaQueryWrapper<Exam>()
                .eq(Exam::getTermId, termId).orderByDesc(Exam::getExamDate).orderByDesc(Exam::getId)
                .last("LIMIT 1"));

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("姓名", student.getName());
        facts.put("年级", grade.getName());
        facts.put("班级", clazz.getName());
        facts.put("学期", term.getName());
        facts.put("班主任", headTeacher != null ? headTeacher.getRealName() : "");

        // 期末成绩（含班/年级最高对比）
        if (exam != null) {
            Map<Long, ExamSubject> esMap = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                            .eq(ExamSubject::getExamId, exam.getId())).stream()
                    .collect(java.util.stream.Collectors.toMap(ExamSubject::getSubjectId, e -> e));
            List<Score> scores = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                    .eq(Score::getExamId, exam.getId()).eq(Score::getStudentId, studentId));
            List<Map<String, Object>> subjectRows = new ArrayList<>();
            double total = 0;
            for (Score sc : scores) {
                Subject s = subjects.get(sc.getSubjectId());
                if (s == null || sc.getScore() == null) {
                    continue; // 批63c 跳过总分等级行（subject_id NULL）/清分留级行
                }
                total += sc.getScore().doubleValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("科目", s.getName());
                row.put("得分", sc.getScore().doubleValue());
                ExamSubject es = esMap.get(sc.getSubjectId());
                if (es != null) {
                    row.put("班级最高", es.getClassMax() == null ? null : es.getClassMax().doubleValue());
                    row.put("年级最高", es.getGradeMax() == null ? null : es.getGradeMax().doubleValue());
                }
                // 排名不进 AI 事实（教师触发的寄语/报告不得出现排名字样，敏感口径）
                subjectRows.add(row);
            }
            subjectRows.sort((a, b) -> Double.compare((Double) b.get("得分"), (Double) a.get("得分")));
            facts.put("期末成绩", subjectRows);
            facts.put("总分", round1(total));
            if (exam.getClassMaxTotal() != null) {
                facts.put("班级总分最高", exam.getClassMaxTotal().doubleValue());
            }
            if (exam.getGradeMaxTotal() != null) {
                facts.put("年级总分最高", exam.getGradeMaxTotal().doubleValue());
            }
        }

        // 九维学期表现
        List<Grid> grids = gridMapper.selectList(new LambdaQueryWrapper<Grid>().orderByAsc(Grid::getSort));
        Map<Long, GridStatTerm> stats = gridStatTermMapper.selectList(new LambdaQueryWrapper<GridStatTerm>()
                        .eq(GridStatTerm::getStudentId, studentId).eq(GridStatTerm::getTermId, termId)).stream()
                .collect(java.util.stream.Collectors.toMap(GridStatTerm::getGridId, g -> g));
        List<Map<String, Object>> gridRows = new ArrayList<>();
        for (Grid g : grids) {
            GridStatTerm st = stats.get(g.getId());
            if (st == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("维度", g.getName());
            row.put("获得评价积分", st.getPoints().doubleValue());
            row.put("获得评价次数", st.getEvalCount());
            row.put("获得评价种类", st.getKindCount());
            gridRows.add(row);
        }
        facts.put("综合素质表现", gridRows);

        // 分析结论（优势/待提升；规则引擎产出）
        StudentAnalysis analysis = analysisMapper.selectOne(new LambdaQueryWrapper<StudentAnalysis>()
                .eq(StudentAnalysis::getStudentId, studentId).eq(StudentAnalysis::getTermId, termId)
                .last("LIMIT 1"));
        if (analysis != null) {
            facts.put("优势学科", analysis.getAdvantage());
            facts.put("待提升学科", analysis.getToImprove());
        }

        // 综合素质评定
        Comprehensive comp = comprehensiveMapper.selectOne(new LambdaQueryWrapper<Comprehensive>()
                .eq(Comprehensive::getStudentId, studentId).eq(Comprehensive::getTermId, termId)
                .last("LIMIT 1"));
        if (comp != null) {
            facts.put("综合素质评定", Map.of(
                    "思想品德", comp.getMoral(), "学业水平", comp.getAbility(),
                    "身心健康", comp.getHealth(), "艺术素养", comp.getAesthetic(),
                    "社会实践", comp.getPractice(), "综合等级", comp.getFinalLevel()));
        }

        // 学期评价次数合计
        Long evalCount = evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, studentId));
        facts.put("学期累计被评价次数", evalCount == null ? 0 : evalCount.intValue());
        return facts;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
