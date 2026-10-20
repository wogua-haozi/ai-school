package com.aischool.server.service.score;

import com.aischool.server.entity.Exam;
import com.aischool.server.entity.ExamSubject;
import com.aischool.server.entity.Score;
import com.aischool.server.entity.Subject;
import com.aischool.server.entity.Term;
import com.aischool.server.mapper.ExamMapper;
import com.aischool.server.mapper.ExamSubjectMapper;
import com.aischool.server.mapper.ScoreMapper;
import com.aischool.server.mapper.SubjectMapper;
import com.aischool.server.mapper.TermMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 批63 家长成绩：白名单式输出——分数+满分+等级（批63c 等级=校方上传 grade，不再现算），
 * 刻意不含任何名次字段（甲方口径：家长只见分数+等级）。
 */
@Service
@RequiredArgsConstructor
public class ParentScoreService {

    private final ScoreMapper scoreMapper;
    private final ExamMapper examMapper;
    private final ExamSubjectMapper examSubjectMapper;
    private final SubjectMapper subjectMapper;
    private final TermMapper termMapper;

    /** 历次成绩按考试分组（新→旧）：[{examName,termName,examDate,totalGrade,subjects:[{subjectName,score,fullScore,grade}]}] */
    public List<Map<String, Object>> scoresOf(Long studentId) {
        Map<Long, List<Score>> byExam = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .eq(Score::getStudentId, studentId)) // 批63c 不过滤 score NULL——总分等级行也要带回
                .stream().collect(Collectors.groupingBy(Score::getExamId));
        if (byExam.isEmpty()) {
            return List.of();
        }
        Map<Long, Exam> exams = examMapper.selectBatchIds(byExam.keySet()).stream()
                .collect(Collectors.toMap(Exam::getId, e -> e));
        Map<Long, String> termNames = termMapper.selectList(null).stream()
                .collect(Collectors.toMap(Term::getId, Term::getName));
        Map<Long, String> subjectNames = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName));
        List<Exam> ordered = byExam.keySet().stream().map(exams::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Exam::getExamDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Exam::getId, Comparator.reverseOrder()))
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Exam exam : ordered) {
            List<ExamSubject> examSubjects = examSubjectMapper.selectList(new LambdaQueryWrapper<ExamSubject>()
                    .eq(ExamSubject::getExamId, exam.getId()).orderByAsc(ExamSubject::getSubjectId));
            Map<Long, java.math.BigDecimal> scoreOf = byExam.get(exam.getId()).stream()
                    .filter(sc -> sc.getSubjectId() != null && sc.getScore() != null) // 批63c 排除总分等级行/清分留级行
                    .collect(Collectors.toMap(Score::getSubjectId, Score::getScore, (a, b) -> a));
            Map<Long, String> gradeOf = byExam.get(exam.getId()).stream()
                    .filter(sc -> sc.getSubjectId() != null && sc.getGrade() != null)
                    .collect(Collectors.toMap(Score::getSubjectId, Score::getGrade, (a, b) -> a));
            String totalGrade = byExam.get(exam.getId()).stream()
                    .filter(sc -> sc.getSubjectId() == null).map(Score::getGrade)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            List<Map<String, Object>> subjects = new ArrayList<>();
            for (ExamSubject es : examSubjects) {
                java.math.BigDecimal sc = scoreOf.get(es.getSubjectId());
                if (sc == null) {
                    continue; // 未录科目不展示
                }
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("subjectName", subjectNames.getOrDefault(es.getSubjectId(), String.valueOf(es.getSubjectId())));
                sm.put("score", sc);
                sm.put("fullScore", es.getFullScore());
                sm.put("grade", gradeOf.get(es.getSubjectId())); // 批63c 校方上传等级，未上传为 null
                subjects.add(sm);
            }
            if (subjects.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examId", exam.getId());
            m.put("examName", exam.getName());
            m.put("termName", termNames.get(exam.getTermId()));
            m.put("examDate", exam.getExamDate() == null ? null : exam.getExamDate().toString());
            m.put("totalGrade", totalGrade); // 批63c 总分等级（subject_id NULL 行），未上传为 null
            m.put("subjects", subjects);
            result.add(m);
        }
        return result;
    }
}
