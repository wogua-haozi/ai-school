package com.aischool.server.service.eval;

import com.aischool.server.common.BizException;
import com.aischool.server.common.Exported;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ConductLog;
import com.aischool.server.entity.Evaluation;
import com.aischool.server.entity.Grid;
import com.aischool.server.entity.Indicator;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Term;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClassGridAvgMapper;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.CoinWeekMapper;
import com.aischool.server.mapper.ConductLogMapper;
import com.aischool.server.mapper.EvaluationMapper;
import com.aischool.server.mapper.GradeGridAvgMapper;
import com.aischool.server.mapper.GridMapper;
import com.aischool.server.mapper.GridStatTermMapper;
import com.aischool.server.mapper.GridStatWeekMapper;
import com.aischool.server.mapper.IndicatorMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.coin.CoinLedgerService;
import com.aischool.server.service.excel.ExcelScoreHelper;
import com.aischool.server.service.moment.MomentService;
import com.aischool.server.service.conduct.ConductLedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 过程性评价引擎（功能点 §5/§6）：一次评价同时写穿全部聚合表——
 * t_evaluation → t_grid_stat_term/week → t_coin_week(in_mine) → 扬长币流水/账户 → 班/年级九维均值。
 * 报告只读聚合表（Java 无聚合代码），故写入必须与 ReportDataBuilder 的读取口径逐条对齐：
 * - 学期窗口 [start 00:00, end 00:00]（与 buildGrids 的过滤完全一致，聚合与报告永不分叉）
 * - kindCount = (title + 指标名) 去重组数（与 buildRecords 分组同构）
 * - weekNo = ⌊(evalTime − 开学日)/7天⌋ + 1
 * 写权限 = 批56③ 素养评价全体教职工可操作（不限班级关系，仅拒家长/门卫；原值班开关与任课口径废止）。
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationMapper evaluationMapper;
    private final IndicatorMapper indicatorMapper;
    private final GridMapper gridMapper;
    private final TermMapper termMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final UserMapper userMapper;
    private final GridStatTermMapper gridStatTermMapper;
    private final GridStatWeekMapper gridStatWeekMapper;
    private final CoinWeekMapper coinWeekMapper;
    private final ClassGridAvgMapper classGridAvgMapper;
    private final GradeGridAvgMapper gradeGridAvgMapper;
    private final CoinLedgerService coinLedger;
    private final ConductLedgerService conductLedger;
    private final ConductLogMapper conductLogMapper;
    private final DataScopeService dataScope;
    private final DutyService dutyService;
    private final MomentService momentService;
    private final ExcelScoreHelper excel;

    public Map<String, Object> evaluate(UserPrincipal user, Long studentId, Long indicatorId, String title,
                                        BigDecimal score, String remark, LocalDateTime evalTime) {
        // 批56③ 素养评价全体老师可操作：不限班级关系（原值班开关与数据面收窄口径废止，家长/门卫仍拒）
        checkTeacherSide(user);
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(404, "学生不存在");
        }
        Indicator ind = indicatorMapper.selectById(indicatorId);
        if (ind == null || ind.getName() == null || ind.getName().isBlank()) {
            throw new BizException(404, "指标不存在");
        }
        Grid grid = gridMapper.selectById(ind.getGridId());
        if (grid == null) {
            throw new BizException(404, "九维不存在");
        }
        if (title == null || title.isBlank()) {
            throw new BizException(400, "title 不能为空");
        }
        if (score == null || score.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException(400, "score 不能为 0");
        }
        if (evalTime == null) {
            throw new BizException(400, "evalTime 不能为空");
        }
        Term term = termOf(evalTime);
        if (term == null) {
            throw new BizException(400, "评价时间不在任何学期范围内（学期末日请选 00:00 前）");
        }

        // ① 评价明细
        Evaluation e = new Evaluation();
        e.setStudentId(studentId);
        e.setTeacherId(user.userId());
        e.setIndicatorId(indicatorId);
        e.setTitle(title.trim());
        e.setScore(score);
        e.setRemark(remark);
        e.setEvalTime(evalTime);
        evaluationMapper.insert(e);

        // 批39⑦ 减分过滤：九维体系（个人累计/周趋势/班年级均值）只累加分，减分仅留明细（教师照常可查）；
        // 历史已聚合的减分不回溯重算（聚合表按学期分行，下学期自然干净）
        boolean positive = score.signum() > 0;
        int weekNo = weekNo(term, evalTime);

        // ② 学期九维累计（原子 upsert：ODKU 消除 select-then-update 的并发丢增量，唯一键见 V6）
        if (positive) {
            gridStatTermMapper.upsertIncrement(studentId, term.getId(), grid.getId(), score,
                    kindCount(studentId, term, grid.getId()));

            // ③ 周九维（原子 upsert）
            gridStatWeekMapper.upsertIncrement(studentId, term.getId(), grid.getId(), weekNo, score);
        }

        // ④ 周扬长币（原子 upsert；只动本人的 in_mine，in_class/in_grade 是全组共现值，改动会波及他人报告）
        coinWeekMapper.upsertMineIncome(studentId, term.getId(), weekNo, score);

        // ⑤ 扬长币流水 + 账户（module=格名-指标名 与种子模块并列，display_order=99 不进收入 TOP5）
        //    批3：指标配了 coin_value 按配置入账，NULL=按 score 原值（行为同旧版）
        BigDecimal coin = ind.getCoinValue() != null ? ind.getCoinValue() : score;
        coinLedger.income(studentId, evalTime.toLocalDate(), "评价", e.getId(),
                grid.getName() + "-" + ind.getName(), coin);

        // ⑤' 操行分联动（批3）：指标配了 conduct_value 才入账，NULL=不联动（线上零变化）
        if (ind.getConductValue() != null) {
            conductLedger.apply(studentId, evalTime.toLocalDate(), ConductLog.SRC_EVALUATION, e.getId(),
                    ind.getConductValue(), grid.getName() + "-" + ind.getName(), user.userId());
        }

        // ⑥ 班/年级均值增量平移（与②③同口径：只平移加分，个人九维与均值才不分叉）
        Clazz clazz = clazzMapper.selectById(student.getClassId());
        if (positive && clazz != null) {
            long classSize = studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                    .eq(Student::getClassId, clazz.getId()));
            if (classSize > 0) {
                shiftClassAvg(clazz.getId(), term.getId(), grid.getId(), score, classSize);
            }
            long gradeSize = studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                    .in(Student::getClassId, clazzIdsOfGrade(clazz.getGradeId())));
            if (gradeSize > 0) {
                shiftGradeAvg(clazz.getGradeId(), term.getId(), grid.getId(), score, gradeSize);
            }
        }

        // ⑦ 加分单向同步微光（批6 漏项D）：正向评价自动生成无照片微光进学生档案/家长孩子流；
        //     减分不同步、微光侧无分数不反向影响评价（scene_tag 用指标名，超 32 字回落九维名）
        if (score.signum() > 0 && student.getClassId() != null) {
            String sceneTag = ind.getName() != null && ind.getName().length() <= 32
                    ? ind.getName() : grid.getName();
            String note = title.trim() + " +" + score.stripTrailingZeros().toPlainString()
                    + (remark == null || remark.isBlank() ? "" : "：" + remark.trim());
            momentService.createSynced(user, student.getClassId(), studentId, sceneTag, note);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evaluationId", e.getId());
        data.put("termId", term.getId());
        data.put("weekNo", weekNo);
        return data;
    }

    /**
     * 批40e 撤回普适：删除评价并逆向冲销全部聚合——九维学期/周累计、周扬长币 in_mine、
     * 班年级均值平移、扬长币流水（负行留痕）、操行分联动（按原 log 行 delta 取负，指标改配置也不冲错）。
     * 聚合表全是增量式，负值即冲销；kindCount 基于明细表，先删行再算即回正。
     * 边界：联动生成的微光（EVAL_SYNC）无评价关联键，不自动删，教师可在微光模块手删。
     */
    @Transactional
    public void delete(UserPrincipal user, Long id) {
        Evaluation e = evaluationMapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "评价不存在");
        }
        if (!"ADMIN".equals(user.role()) && !e.getTeacherId().equals(user.userId())) {
            throw new BizException(403, "只有评价人或管理员可删除评价");
        }
        Indicator ind = indicatorMapper.selectById(e.getIndicatorId());
        Grid grid = ind == null ? null : gridMapper.selectById(ind.getGridId());
        Term term = termOf(e.getEvalTime());
        Student student = studentMapper.selectById(e.getStudentId());

        evaluationMapper.deleteById(id);

        boolean positive = e.getScore().signum() > 0;
        if (grid != null && term != null) {
            int weekNo = weekNo(term, e.getEvalTime());
            // 周扬长币 in_mine 与 evaluate 同口径（正负分都写），冲销移出 positive 判定
            coinWeekMapper.upsertMineIncome(e.getStudentId(), term.getId(), weekNo, e.getScore().negate());
            if (positive) {
                gridStatTermMapper.upsertIncrement(e.getStudentId(), term.getId(), grid.getId(), e.getScore().negate(),
                        kindCount(e.getStudentId(), term, grid.getId()));
                gridStatWeekMapper.upsertIncrement(e.getStudentId(), term.getId(), grid.getId(), weekNo, e.getScore().negate());
            }
            if (student != null && student.getClassId() != null) {
                Clazz clazz = clazzMapper.selectById(student.getClassId());
                if (clazz != null) {
                    long classSize = studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                            .eq(Student::getClassId, clazz.getId()));
                    if (classSize > 0) {
                        shiftClassAvg(clazz.getId(), term.getId(), grid.getId(), e.getScore().negate(), classSize);
                    }
                    long gradeSize = studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                            .in(Student::getClassId, clazzIdsOfGrade(clazz.getGradeId())));
                    if (gradeSize > 0) {
                        shiftGradeAvg(clazz.getGradeId(), term.getId(), grid.getId(), e.getScore().negate(), gradeSize);
                    }
                }
            }
        }
        // 扬长币冲正：按原入账行定位（无行=批3 前老数据没入过账，自然不冲）
        coinLedger.reverse(e.getStudentId(), "评价", id);
        // 操行分联动冲正：按原 log 行 delta 取负（无行=未联动）
        ConductLog conductOrigin = conductLogMapper.selectOne(new LambdaQueryWrapper<ConductLog>()
                .eq(ConductLog::getSourceType, ConductLog.SRC_EVALUATION)
                .eq(ConductLog::getSourceId, id)
                .orderByAsc(ConductLog::getId)
                .last("LIMIT 1"));
        if (conductOrigin != null) {
            conductLedger.apply(e.getStudentId(), e.getEvalTime().toLocalDate(), ConductLog.SRC_EVALUATION, id,
                    conductOrigin.getDelta().negate(), "撤回-" + conductOrigin.getReason(), user.userId());
        }
    }

    /** 某学生某学期的评价列表（含格/指标/教师名，时间正序）；批56③ 权限同评价=全体老师 */
    public List<Map<String, Object>> list(UserPrincipal user, Long studentId, Long termId) {
        checkTeacherSide(user);
        Term term = termMapper.selectById(termId);
        if (term == null) {
            throw new BizException(404, "学期不存在");
        }
        Map<Long, Indicator> indicators = indicatorMapper.selectList(null).stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Map<Long, String> gridNames = gridMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grid::getId, Grid::getName));
        Map<Long, String> teacherNames = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, "ADMIN", "HEAD_TEACHER", "TEACHER")).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));
        List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, studentId)
                .ge(Evaluation::getEvalTime, term.getStartDate().atStartOfDay())
                .le(Evaluation::getEvalTime, term.getEndDate().atStartOfDay())
                .orderByAsc(Evaluation::getEvalTime)
                .orderByAsc(Evaluation::getId));
        return evals.stream().map(ev -> {
            Indicator ind = indicators.get(ev.getIndicatorId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.getId());
            m.put("evalTime", ev.getEvalTime());
            m.put("gridName", ind == null ? null : gridNames.get(ind.getGridId()));
            m.put("indicatorName", ind == null ? null : ind.getName());
            m.put("title", ev.getTitle());
            m.put("score", ev.getScore());
            m.put("remark", ev.getRemark());
            m.put("teacherName", teacherNames.get(ev.getTeacherId()));
            return m;
        }).toList();
    }

    /** 班级×学期评价导出 xlsx（批6 漏项C2；批56③ 权限同查看=全体老师全校班级） */
    public Exported export(UserPrincipal user, Long classId, Long termId) {
        checkTeacherSide(user);
        Term term = termMapper.selectById(termId);
        if (term == null) {
            throw new BizException(404, "学期不存在");
        }
        Clazz clazz = clazzMapper.selectById(classId);
        List<Student> students = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId));
        Map<Long, Student> stuById = students.stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Indicator> indicators = indicatorMapper.selectList(null).stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Map<Long, String> gridNames = gridMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grid::getId, Grid::getName));
        Map<Long, String> teacherNames = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getRole, "ADMIN", "HEAD_TEACHER", "TEACHER", "LEADER")).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        List<Object[]> table = new java.util.ArrayList<>();
        if (!stuById.isEmpty()) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                    .in(Evaluation::getStudentId, stuById.keySet())
                    .ge(Evaluation::getEvalTime, term.getStartDate().atStartOfDay())
                    .le(Evaluation::getEvalTime, term.getEndDate().atStartOfDay())
                    .orderByAsc(Evaluation::getEvalTime)
                    .orderByAsc(Evaluation::getId));
            for (Evaluation ev : evals) {
                Student s = stuById.get(ev.getStudentId());
                Indicator ind = indicators.get(ev.getIndicatorId());
                table.add(new Object[]{
                        s == null ? "" : s.getStudentNo(),
                        s == null ? "" : s.getName(),
                        ev.getEvalTime() == null ? "" : ev.getEvalTime().format(fmt),
                        ind == null ? "" : gridNames.getOrDefault(ind.getGridId(), ""),
                        ind == null ? "" : ind.getName(),
                        ev.getTitle(),
                        ev.getScore(),
                        ev.getRemark() == null ? "" : ev.getRemark(),
                        teacherNames.getOrDefault(ev.getTeacherId(), "")});
            }
        }
        String name = "素养评价_" + (clazz != null ? clazz.getName() : classId)
                + "_" + term.getName() + ".xlsx";
        return new Exported(name, excel.export("素养评价",
                new String[]{"学号", "姓名", "时间", "九维", "指标", "标题", "分值", "备注", "评价人"}, table));
    }

    // ───────────────── 内部：与 ReportDataBuilder 同构的口径 ─────────────────

    /** 批56③ 素养评价全体教职工可操作：仅拒家长/门卫（口径同 StudentController.checkTeacherSide） */
    private void checkTeacherSide(UserPrincipal user) {
        if ("PARENT".equals(user.role()) || "GUARD".equals(user.role())) {
            throw new BizException(403, "仅教职工可使用素养评价");
        }
    }

    /** 学期窗口判定：[start 00:00, end 00:00] 闭区间（与 buildGrids 的 ge/le 过滤一致） */
    private Term termOf(LocalDateTime evalTime) {
        return termMapper.selectList(null).stream()
                .filter(t -> t.getStartDate() != null && t.getEndDate() != null)
                .filter(t -> !evalTime.isBefore(t.getStartDate().atStartOfDay())
                        && !evalTime.isAfter(t.getEndDate().atStartOfDay()))
                .findFirst().orElse(null);
    }

    /** kindCount = (title + 指标名) 去重组数（buildRecords 分组同构：空名占位指标同样计数） */
    private int kindCount(Long studentId, Term term, Long gridId) {
        List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, studentId)
                .ge(Evaluation::getEvalTime, term.getStartDate().atStartOfDay())
                .le(Evaluation::getEvalTime, term.getEndDate().atStartOfDay())
                .orderByAsc(Evaluation::getId));
        Map<Long, Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getGridId, gridId)).stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Evaluation ev : evals) {
            Indicator ind = indicators.get(ev.getIndicatorId());
            if (ind != null) {
                keys.add(ev.getTitle() + " " + ind.getName());
            }
        }
        return keys.size();
    }

    /** weekNo = ⌊(evalTime − 开学日)/7天⌋ + 1（种子口径） */
    private int weekNo(Term term, LocalDateTime evalTime) {
        long days = ChronoUnit.DAYS.between(term.getStartDate().atStartOfDay(), evalTime);
        return (int) (days / 7) + 1;
    }

    private List<Long> clazzIdsOfGrade(Long gradeId) {
        return clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                        .eq(Clazz::getGradeId, gradeId)).stream().map(Clazz::getId).toList();
    }

    private void shiftClassAvg(Long classId, Long termId, Long gridId, BigDecimal s, long size) {
        BigDecimal delta = s.divide(BigDecimal.valueOf(size), 4, RoundingMode.HALF_UP);
        classGridAvgMapper.upsertShift(classId, termId, gridId, delta);
    }

    private void shiftGradeAvg(Long gradeId, Long termId, Long gridId, BigDecimal s, long size) {
        BigDecimal delta = s.divide(BigDecimal.valueOf(size), 4, RoundingMode.HALF_UP);
        gradeGridAvgMapper.upsertShift(gradeId, termId, gridId, delta);
    }
}
