package com.aischool.server.service.schoolyear;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Term;
import com.aischool.server.entity.WalkClass;
import com.aischool.server.entity.WalkClassStudent;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.WalkClassMapper;
import com.aischool.server.mapper.WalkClassStudentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学年一键滚动（批28）：年级按 id 序（=名册导入的年级序）逐级上升，
 * 最高年级学生标记毕业退场、班级原地保留作历史；晋升班级换挂新学年年级行并按新年级名改名；
 * 学生 class_id 不动即随班升级，任课/历史数据零迁移。新学年自动建两学期并切换当前学期。
 * 走班（教学班）是学年教学安排，随滚动整体清空（新学年重新导入）。
 */
@Service
@RequiredArgsConstructor
public class SchoolYearService {

    private final GradeMapper gradeMapper;
    private final ClazzMapper clazzMapper;
    private final StudentMapper studentMapper;
    private final TermMapper termMapper;
    private final WalkClassMapper walkClassMapper;
    private final WalkClassStudentMapper walkStudentMapper;

    /** 滚动方案（dry-run）：年级链/毕业规模/新学期计划，管理端先看后滚 */
    public Map<String, Object> preview() {
        List<Grade> all = gradeMapper.selectList(
                new LambdaQueryWrapper<Grade>().orderByAsc(Grade::getId));
        if (all.isEmpty()) {
            throw new BizException(400, "尚无年级数据，请先在「年级与班级」建立年级");
        }
        // 当前学年 = 最大学年；滚动后保留的历史毕业年级行（更小学年）不进晋升链
        String oldYear = all.stream().map(Grade::getSchoolYear).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder())
                .orElseThrow(() -> new BizException(400, "年级行缺少学年标记，请先在「年级与班级」整理"));
        List<Grade> grades = all.stream().filter(g -> oldYear.equals(g.getSchoolYear())).toList();
        String newYear = bumpYear(oldYear);

        List<Map<String, Object>> gradePlans = new ArrayList<>();
        long graduatedStudents = 0;
        long promotedClasses = 0;
        for (int i = 0; i < grades.size(); i++) {
            Grade g = grades.get(i);
            long classCount = clazzMapper.selectCount(new LambdaQueryWrapper<Clazz>()
                    .eq(Clazz::getGradeId, g.getId()));
            List<Long> classIds = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                            .eq(Clazz::getGradeId, g.getId()).select(Clazz::getId)).stream()
                    .map(Clazz::getId).toList();
            long studentCount = classIds.isEmpty() ? 0 : studentMapper.selectCount(
                    new LambdaQueryWrapper<Student>()
                            .in(Student::getClassId, classIds)
                            .eq(Student::getStatus, "在读"));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("gradeId", g.getId());
            p.put("name", g.getName());
            boolean graduate = i == grades.size() - 1;
            p.put("action", graduate ? "GRADUATE" : "PROMOTE");
            p.put("targetName", graduate ? g.getName() : grades.get(i + 1).getName());
            p.put("classCount", classCount);
            p.put("studentCount", studentCount);
            if (graduate) {
                graduatedStudents = studentCount;
            } else {
                promotedClasses += classCount;
            }
            gradePlans.add(p);
        }
        // 新学年首年级（新一年级/初一）：空年级行，新生名册随后导入
        Map<String, Object> intake = new LinkedHashMap<>();
        intake.put("name", grades.get(0).getName());
        intake.put("action", "NEW_INTAKE");
        gradePlans.add(0, intake);

        // 学期计划：旧学年的学期（start_date 落在 [旧学年9月, 新学年9月) 窗口；无日期的按名称
        // 含旧学年任一半兜底，兼容「2025年秋季学期」式命名）→ 日期顺延一年、名称换新年份，
        // 第一个新学期自动设为当前
        String oldStart = oldYear.substring(0, 4), oldEnd = oldYear.substring(5);
        String newStart = newYear.substring(0, 4), newEnd = newYear.substring(5);
        LocalDate winStart = LocalDate.parse(oldStart + "-09-01");
        LocalDate winEnd = LocalDate.parse(newStart + "-09-01");
        List<Map<String, Object>> termPlans = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Term t : termMapper.selectList(new LambdaQueryWrapper<Term>().orderByAsc(Term::getId))) {
            boolean inWindow = t.getStartDate() != null
                    && !t.getStartDate().isBefore(winStart) && t.getStartDate().isBefore(winEnd);
            boolean nameHit = t.getStartDate() == null && t.getName() != null
                    && (t.getName().contains(oldYear) || t.getName().contains(oldStart) || t.getName().contains(oldEnd));
            if (!inWindow && !nameHit) {
                continue;
            }
            String base = t.getName() == null ? "" : t.getName();
            // 单命中替换：链式 replace 会把已替换出的新年份再替换一次（2026-2027 → 2027-2027）
            String newName;
            if (base.contains(oldYear)) {
                newName = base.replace(oldYear, newYear);
            } else if (base.contains(oldStart)) {
                newName = base.replace(oldStart, newStart);
            } else if (base.contains(oldEnd)) {
                newName = base.replace(oldEnd, newEnd);
            } else {
                newName = base + "(" + newYear + ")";
            }
            if (termMapper.selectCount(new LambdaQueryWrapper<Term>().eq(Term::getName, newName)) > 0) {
                throw new BizException(400, "新学期「" + newName + "」已存在（可能已滚动过），请勿重复操作");
            }
            Map<String, Object> tp = new LinkedHashMap<>();
            tp.put("oldName", t.getName());
            tp.put("newName", newName);
            tp.put("start", t.getStartDate() == null ? null : t.getStartDate().plusYears(1).toString());
            tp.put("end", t.getEndDate() == null ? null : t.getEndDate().plusYears(1).toString());
            termPlans.add(tp);
        }
        if (termPlans.isEmpty()) {
            warnings.add("未找到旧学年（" + oldYear + "）的学期，滚动不会自动建学期，请到「学期」页签手动创建新学年学期");
        }
        warnings.add("班主任与任课关系随班保留（班级 id 不变），如需调整请到「年级与班级」/「教师与任课」处理");
        // 走班（批75）是学年教学安排：映射的是本学年年级的学生，随滚动整体作废，新学年重新导入
        long walkClasses = walkClassMapper.selectCount(null);
        if (walkClasses > 0) {
            long walkStudents = walkStudentMapper.selectCount(null);
            warnings.add("走班（教学班）是学年教学安排，滚动将清空全部走班分班（" + walkClasses
                    + " 个走班 / " + walkStudents + " 条学生映射）；新学年开学后请到「走班分班」重新导入");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oldYear", oldYear);
        m.put("newYear", newYear);
        m.put("grades", gradePlans);
        m.put("promotedClasses", promotedClasses);
        m.put("graduatedStudents", graduatedStudents);
        m.put("terms", termPlans);
        m.put("warnings", warnings);
        return m;
    }

    /** 执行滚动：全事务。confirmNewYear 须与方案一致（防看到旧方案后数据已变仍照滚） */
    @Transactional
    public Map<String, Object> roll(String confirmNewYear) {
        Map<String, Object> plan = preview();
        if (!plan.get("newYear").equals(confirmNewYear)) {
            throw new BizException(400, "确认学年与方案不一致，请刷新预览后重试");
        }
        String newYear = (String) plan.get("newYear");
        String oldYear = (String) plan.get("oldYear");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gradePlans = (List<Map<String, Object>>) plan.get("grades");

        // 方案行：NEW_INTAKE(首行) + PROMOTE(targetName) + GRADUATE(末行)，按 id 升序的源年级在后
        // 1) 新学年年级行：晋升目标年级名 + 新生入口年级名，各建一行
        Map<String, Long> newGradeIds = new LinkedHashMap<>();
        List<String> newGradeNames = new ArrayList<>();
        for (Map<String, Object> p : gradePlans) {
            String target = "PROMOTE".equals(p.get("action"))
                    ? (String) p.get("targetName") : "NEW_INTAKE".equals(p.get("action"))
                    ? (String) p.get("name") : null;
            if (target != null && !newGradeNames.contains(target)) {
                newGradeNames.add(target);
            }
        }
        for (String name : newGradeNames) {
            Grade g = new Grade();
            g.setName(name);
            g.setSchoolYear(newYear);
            gradeMapper.insert(g);
            newGradeIds.put(name, g.getId());
        }

        // 2) 晋升班级：换挂新年级行 + 按新年级名改名（原名以源年级名开头的才改，自定义名保留）
        long promotedClasses = 0;
        for (Map<String, Object> p : gradePlans) {
            if (!"PROMOTE".equals(p.get("action"))) {
                continue;
            }
            Long srcGradeId = ((Number) p.get("gradeId")).longValue();
            String srcName = (String) p.get("name");
            String targetName = (String) p.get("targetName");
            Long newGradeId = newGradeIds.get(targetName);
            List<Clazz> classes = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                    .eq(Clazz::getGradeId, srcGradeId));
            for (Clazz c : classes) {
                String newName = c.getName() != null && c.getName().startsWith(srcName)
                        ? targetName + c.getName().substring(srcName.length()) : c.getName();
                clazzMapper.update(null, new LambdaUpdateWrapper<Clazz>()
                        .eq(Clazz::getId, c.getId())
                        .set(Clazz::getGradeId, newGradeId)
                        .set(Clazz::getName, newName));
                promotedClasses++;
            }
        }

        // 3) 毕业年级：在读学生全部标记毕业（班级/年级行保留作历史档案）
        long graduatedStudents = 0;
        for (Map<String, Object> p : gradePlans) {
            if (!"GRADUATE".equals(p.get("action"))) {
                continue;
            }
            Long srcGradeId = ((Number) p.get("gradeId")).longValue();
            List<Long> classIds = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                            .eq(Clazz::getGradeId, srcGradeId).select(Clazz::getId)).stream()
                    .map(Clazz::getId).toList();
            if (!classIds.isEmpty()) {
                graduatedStudents += studentMapper.update(null, new LambdaUpdateWrapper<Student>()
                        .in(Student::getClassId, classIds)
                        .eq(Student::getStatus, "在读")
                        .set(Student::getStatus, "毕业"));
            }
        }

        // 4) 学期：旧学期全部取消当前 → 新学期顺延一年建行，首个设为当前
        int newTerms = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> termPlans = (List<Map<String, Object>>) plan.get("terms");
        if (!termPlans.isEmpty()) {
            termMapper.update(null, new LambdaUpdateWrapper<Term>().set(Term::getIsCurrent, 0));
            boolean first = true;
            for (Map<String, Object> tp : termPlans) {
                Term t = new Term();
                t.setName((String) tp.get("newName"));
                t.setStartDate(tp.get("start") == null ? null : LocalDate.parse((String) tp.get("start")));
                t.setEndDate(tp.get("end") == null ? null : LocalDate.parse((String) tp.get("end")));
                t.setIsCurrent(first ? 1 : 0);
                termMapper.insert(t);
                first = false;
                newTerms++;
            }
        }

        // 5) 清空走班（教学班）：走班映射的是本学年年级的学生，滚动后整体作废，新学年重新导入。
        //    「学年相关安排表」清单：凡随学年整体失效的安排（目前仅走班两表）都必须挂进本步骤，
        //    后续新增此类表时同样在此清空，否则旧映射会污染新学年的排名分组与教师端入口
        long clearedWalkClasses = walkClassMapper.selectCount(null);
        if (clearedWalkClasses > 0) {
            walkStudentMapper.delete(new LambdaQueryWrapper<WalkClassStudent>());
            walkClassMapper.delete(new LambdaQueryWrapper<WalkClass>());
        }

        // 6) 清掉滚动后空了的旧年级行（晋升源）；毕业年级行保留
        int removedOldGrades = 0;
        for (Map<String, Object> p : gradePlans) {
            if ("PROMOTE".equals(p.get("action"))) {
                Long srcGradeId = ((Number) p.get("gradeId")).longValue();
                if (clazzMapper.selectCount(new LambdaQueryWrapper<Clazz>()
                        .eq(Clazz::getGradeId, srcGradeId)) == 0) {
                    gradeMapper.deleteById(srcGradeId);
                    removedOldGrades++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newSchoolYear", newYear);
        result.put("oldSchoolYear", oldYear);
        result.put("promotedClasses", promotedClasses);
        result.put("graduatedStudents", graduatedStudents);
        result.put("newGrades", newGradeNames.size());
        result.put("newTerms", newTerms);
        result.put("clearedWalkClasses", clearedWalkClasses);
        result.put("removedOldGrades", removedOldGrades);
        return result;
    }

    /** 2025-2026 → 2026-2027 */
    private String bumpYear(String year) {
        if (year == null || !year.matches("^\\d{4}-\\d{4}$")) {
            throw new BizException(400, "学年格式应为 2025-2026，当前为「" + year + "」，请先在「年级与班级」修正");
        }
        int a = Integer.parseInt(year.substring(0, 4));
        return (a + 1) + "-" + (a + 2);
    }
}
