package com.aischool.server.service.civility;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.CivilityAward;
import com.aischool.server.entity.CivilityScore;
import com.aischool.server.entity.ClassScore;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Evaluation;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.CivilityAwardMapper;
import com.aischool.server.mapper.CivilityScoreMapper;
import com.aischool.server.mapper.ClassScoreMapper;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.EvaluationMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.eval.DutyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文明班评比（批30 B 案 → 批39⑥ 数据源合并 → 批43 三分源 → 批50 摘评价分）：
 * 旧口径=教师按德育规范 12 项打分（扣分/特殊加减），当日分=基础 120+当日合计；
 * 批39⑥ 起打分入口下线，切换日（t_sys_config.civility_eval_from）起新口径；
 * 批43 并入「班级整体加减分」（t_class_score，不落具体学生的班级层面记分）；
 * 批50 起学生评价分不再直加班级总分（个人成长数据不连坐班级），评价记录仅保留
 * 「德育检查痕迹」身份——检查日=该班有评价记录或班级记分的日期（当日基础 120），
 * 新段总分=检查日×120+Σ班级记分。
 * 新旧不混算：跨切换日的区间按切换日切分，两段各自口径加总；切换日前的历史打分保留进排名。
 * 排名按 [from,to] 区间自动聚合；批46③ 起评选=按周冻结排名快照（自然周周一~周日，
 * rank 1-3 即文明班金银铜，周期标签如 2026-W41），每周一自动评选上周，管理端可手动重评；
 * 月度评选停用（历史 MONTH 快照保留可查，仍可手动重评历史月份）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CivilityService {

    private final CivilityScoreMapper scoreMapper;
    private final CivilityAwardMapper awardMapper;
    private final ClassScoreMapper classScoreMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final UserMapper userMapper;
    private final StudentMapper studentMapper;
    private final EvaluationMapper evaluationMapper;
    private final SysConfigMapper sysConfigMapper;
    private final DutyService dutyService;

    /** 批39⑥ 切换日（yyyy-MM-dd）；空=未切换，排名仍按旧打分口径 */
    private LocalDate evalFrom() {
        SysConfig c = sysConfigMapper.selectById("civility_eval_from");
        if (c == null || c.getCfgValue() == null || c.getCfgValue().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(c.getCfgValue().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Data
    public static class ScoreReq {
        private Long classId;
        private String scoreDate; // yyyy-MM-dd
        private Integer sectionNo;
        private String itemText;
        private BigDecimal delta;
        private Integer cnt;
        private String note;
    }

    /** 打分录入（批39⑥ 已下线、批50 起班级分只看记分：仅保留历史记录查询/删除） */
    public Map<String, Object> create(ScoreReq req) {
        throw new BizException(403, "文明班打分入口已下线：班级层面加减分请到「素养评价 → 班级记分」录入");
    }

    /** 打分记录（classId/dateFrom/dateTo 可选筛选，近 200 条；批39⑥ 起仅供历史查询） */
    public List<Map<String, Object>> records(Long classId, LocalDate from, LocalDate to) {
        List<CivilityScore> rows = scoreMapper.selectList(new LambdaQueryWrapper<CivilityScore>()
                .eq(classId != null, CivilityScore::getClassId, classId)
                .ge(from != null, CivilityScore::getScoreDate, from)
                .le(to != null, CivilityScore::getScoreDate, to)
                .orderByDesc(CivilityScore::getId)
                .last("LIMIT 200"));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> classNames = clazzMapper.selectBatchIds(rows.stream()
                        .map(CivilityScore::getClassId).distinct().toList()).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName, (a, b) -> a));
        List<Long> opIds = rows.stream().map(CivilityScore::getOperatorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> opNames = opIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(opIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return rows.stream().<Map<String, Object>>map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("classId", r.getClassId());
            m.put("className", classNames.getOrDefault(r.getClassId(), ""));
            m.put("scoreDate", r.getScoreDate().toString());
            m.put("sectionNo", r.getSectionNo());
            m.put("itemText", r.getItemText());
            m.put("delta", r.getDelta());
            m.put("cnt", r.getCnt());
            m.put("note", r.getNote());
            m.put("operatorName", r.getOperatorId() == null ? "" : opNames.getOrDefault(r.getOperatorId(), ""));
            m.put("createTime", r.getCreateTime());
            return m;
        }).toList();
    }

    /** 误录删除（仅管理员/有管理权限的领导） */
    public void delete(Long id) {
        if (scoreMapper.selectById(id) == null) {
            throw new BizException(404, "打分记录不存在");
        }
        scoreMapper.deleteById(id);
    }

    // ───────── 批43：班级整体加减分（进文明班评比，不落具体学生、不进个人档案） ─────────

    /** 班级记分写权限（批56③ 拍板）：当日值日老师可记任意班 + 管理员/领导兜底；
     *  生活老师（DORM）放行记宿舍类分（批62：宿舍评比归生活老师，不限值日排班）。
     *  原班主任本班/级长/学成中心主任口径废止（对齐原软件德育巡检玩法——值日老师记分）；
     *  值日排班由校方在管理端「值班排班」维护，未排班时除管理员/领导外无人可记。 */
    private void checkClassScoreAllowed() {
        var user = AuthUtil.current();
        String role = user.role();
        // 批64 书记与领导平权（免排班可记分）；主任/级长仍走值日排班口径
        if ("ADMIN".equals(role) || "LEADER".equals(role) || "SECRETARY".equals(role) || "DORM".equals(role)) {
            return;
        }
        if (dutyService.isOnDuty(user.userId(), LocalDate.now())) {
            return;
        }
        throw new BizException(403, "班级记分限当日值日老师（或管理员）——值日排班由学校在管理端配置");
    }

    /** 可记分班级列表（前端下拉）：管理员/领导/当日值日老师=全校；其余 403（批56③ 值日口径，
     *  前端探测到 403 即不显示「班级记分」切换） */
    public List<Map<String, Object>> classScoreClasses() {
        checkClassScoreAllowed();
        List<Clazz> all = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId));
        Map<Long, String> gradeNames = gradeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
        return all.stream().<Map<String, Object>>map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("gradeName", gradeNames.getOrDefault(c.getGradeId(), ""));
            return m;
        }).toList();
    }

    @Data
    public static class ClassScoreReq {
        private Long classId;
        private String scoreDate; // yyyy-MM-dd
        private Integer category; // 德育规范大项 1-12，可空
        private String itemText;
        private BigDecimal delta;
        private String note;
    }

    public Map<String, Object> createClassScore(ClassScoreReq req) {
        if (req.getClassId() == null) {
            throw new BizException(400, "请选择班级");
        }
        Clazz c = clazzMapper.selectById(req.getClassId());
        if (c == null) {
            throw new BizException(404, "班级不存在");
        }
        checkClassScoreAllowed();
        LocalDate date;
        try {
            date = LocalDate.parse(req.getScoreDate() == null ? "" : req.getScoreDate().trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, "记分日期格式须为 yyyy-MM-dd");
        }
        LocalDate today = LocalDate.now();
        if (date.isAfter(today) || date.isBefore(today.minusDays(ClassScore.DATE_WINDOW_DAYS))) {
            throw new BizException(400, "记分日期限当日往前 " + ClassScore.DATE_WINDOW_DAYS + " 天内");
        }
        if (req.getCategory() != null && (req.getCategory() < 1 || req.getCategory() > 12)) {
            throw new BizException(400, "大项须为 1-12");
        }
        if (req.getItemText() == null || req.getItemText().isBlank()) {
            throw new BizException(400, "请填写事项说明");
        }
        if (req.getItemText().length() > 200) {
            throw new BizException(400, "事项说明不能超过 200 字");
        }
        if (req.getDelta() == null || req.getDelta().compareTo(BigDecimal.ZERO) == 0
                || req.getDelta().abs().compareTo(BigDecimal.valueOf(ClassScore.DELTA_LIMIT)) > 0) {
            throw new BizException(400, "分值须为 ±1~" + ClassScore.DELTA_LIMIT + " 的非零值");
        }
        ClassScore cs = new ClassScore();
        cs.setClassId(req.getClassId());
        cs.setScoreDate(date);
        cs.setCategory(req.getCategory());
        cs.setItemText(req.getItemText().trim());
        cs.setDelta(req.getDelta());
        cs.setNote(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim());
        cs.setOperatorId(AuthUtil.current().userId());
        classScoreMapper.insert(cs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cs.getId());
        return m;
    }

    /** 班级记分记录（同写权限可看；classId 必填，近 200 条） */
    public List<Map<String, Object>> classScoreList(Long classId, LocalDate from, LocalDate to) {
        if (classId == null) {
            throw new BizException(400, "请选择班级");
        }
        Clazz c = clazzMapper.selectById(classId);
        if (c == null) {
            throw new BizException(404, "班级不存在");
        }
        checkClassScoreAllowed();
        List<ClassScore> rows = classScoreMapper.selectList(new LambdaQueryWrapper<ClassScore>()
                .eq(ClassScore::getClassId, classId)
                .ge(from != null, ClassScore::getScoreDate, from)
                .le(to != null, ClassScore::getScoreDate, to)
                .orderByDesc(ClassScore::getId)
                .last("LIMIT 200"));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> opIds = rows.stream().map(ClassScore::getOperatorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> opNames = opIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(opIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return rows.stream().<Map<String, Object>>map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("classId", r.getClassId());
            m.put("scoreDate", r.getScoreDate().toString());
            m.put("category", r.getCategory());
            m.put("itemText", r.getItemText());
            m.put("delta", r.getDelta());
            m.put("note", r.getNote());
            m.put("operatorName", r.getOperatorId() == null ? "" : opNames.getOrDefault(r.getOperatorId(), ""));
            m.put("createTime", r.getCreateTime());
            return m;
        }).toList();
    }

    /** 班级记分删除冲正（本人或管理员；排名下次聚合自动对齐） */
    public void deleteClassScore(Long id) {
        ClassScore cs = classScoreMapper.selectById(id);
        if (cs == null) {
            throw new BizException(404, "班级记分不存在");
        }
        if (!"ADMIN".equals(AuthUtil.current().role())
                && !AuthUtil.current().userId().equals(cs.getOperatorId())) {
            throw new BizException(403, "仅记分本人或管理员可删除");
        }
        classScoreMapper.deleteById(id);
    }

    /**
     * 自动汇总排名（[from,to] 含两端，批39⑥ 双口径 + 批50 摘评价分）：
     * 切换日前=旧打分口径（全校有打分记录的日子算检查日，当天未被打分的班保底 120；
     * 班级段内分=该段全部 delta×cnt 合计，含扣分）；
     * 切换日起=检查日×120+Σ班级记分（批50：学生评价不再直加班级总分，评价记录
     * 仅与班级记分一起标记「当日有德育检查」——检查日=有评价或记分记录的日期）。
     * 两段各自聚合后加总（新旧不混算），按年级分组返回名次。
     */
    public Map<String, Object> rank(LocalDate from, LocalDate to, Long gradeId) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BizException(400, "日期区间不合法");
        }
        LocalDate era = evalFrom();
        // 旧段截止=min(to, era-1)；新段起点=max(from, era)（era 空=全区间旧口径）
        LocalDate oldTo = era == null ? to : (era.isAfter(to) ? to : era.minusDays(1));
        LocalDate newFrom = era == null || era.isAfter(to) ? null : (from.isAfter(era) ? from : era);

        List<CivilityScore> rows = oldTo.isBefore(from) ? List.of() : scoreMapper.selectList(
                new LambdaQueryWrapper<CivilityScore>()
                        .ge(CivilityScore::getScoreDate, from)
                        .le(CivilityScore::getScoreDate, oldTo));
        Set<LocalDate> checkDaysOld = rows.stream().map(CivilityScore::getScoreDate).collect(Collectors.toSet());
        Map<Long, BigDecimal> classDelta = rows.stream().collect(Collectors.toMap(CivilityScore::getClassId,
                r -> r.getDelta().multiply(BigDecimal.valueOf(r.getCnt())), BigDecimal::add));

        // 新段评价聚合：学生→班级映射限定目标年级（gradeId 空=全校），避免全量评价扫描；
        // 批50 起只取评价日期作检查痕迹，评价分不进班级总分
        List<Clazz> classes = clazzMapper.selectList(null);
        Map<Long, Set<LocalDate>> classEvalDays = new LinkedHashMap<>();
        Map<Long, BigDecimal> classOwnScore = new LinkedHashMap<>();
        Map<Long, Set<LocalDate>> classOwnDays = new LinkedHashMap<>();
        if (newFrom != null) {
            Set<Long> scopeClassIds = classes.stream()
                    .filter(c -> gradeId == null || gradeId.equals(c.getGradeId()))
                    .map(Clazz::getId).collect(Collectors.toSet());
            Map<Long, Long> stuClass = scopeClassIds.isEmpty() ? Map.of()
                    : studentMapper.selectList(new LambdaQueryWrapper<Student>()
                            .in(Student::getClassId, scopeClassIds)).stream()
                    .filter(s -> s.getClassId() != null)
                    .collect(Collectors.toMap(Student::getId, Student::getClassId, (a, b) -> a));
            if (!stuClass.isEmpty()) {
                for (Evaluation ev : evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                        .in(Evaluation::getStudentId, stuClass.keySet())
                        .ge(Evaluation::getEvalTime, newFrom.atStartOfDay())
                        .le(Evaluation::getEvalTime, to.atTime(LocalTime.MAX)))) {
                    Long cid = stuClass.get(ev.getStudentId());
                    classEvalDays.computeIfAbsent(cid, k -> new LinkedHashSet<>()).add(ev.getEvalTime().toLocalDate());
                }
            }
            // 批43 班级整体记分：分值直加 + 记分日并入检查日（任一源有记录即当日有德育检查）
            if (!scopeClassIds.isEmpty()) {
                for (ClassScore cs : classScoreMapper.selectList(new LambdaQueryWrapper<ClassScore>()
                        .in(ClassScore::getClassId, scopeClassIds)
                        .ge(ClassScore::getScoreDate, newFrom)
                        .le(ClassScore::getScoreDate, to))) {
                    classOwnScore.merge(cs.getClassId(), cs.getDelta(), BigDecimal::add);
                    classOwnDays.computeIfAbsent(cs.getClassId(), k -> new LinkedHashSet<>()).add(cs.getScoreDate());
                }
            }
        }

        Map<Long, String> gradeNames = gradeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
        List<Map<String, Object>> grades = new ArrayList<>();
        Map<Long, List<Clazz>> byGrade = classes.stream()
                .filter(c -> gradeId == null || gradeId.equals(c.getGradeId()))
                .collect(Collectors.groupingBy(Clazz::getGradeId));
        byGrade.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    List<Map<String, Object>> list = e.getValue().stream()
                            .map(c -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("classId", c.getId());
                                m.put("className", c.getName());
                                Set<LocalDate> days = new LinkedHashSet<>(classEvalDays.getOrDefault(c.getId(), Set.of()));
                                days.addAll(classOwnDays.getOrDefault(c.getId(), Set.of()));
                                int evalDays = days.size();
                                BigDecimal total = BigDecimal.valueOf(CivilityScore.DAILY_BASE)
                                        .multiply(BigDecimal.valueOf((long) checkDaysOld.size() + evalDays))
                                        .add(classDelta.getOrDefault(c.getId(), BigDecimal.ZERO))
                                        .add(classOwnScore.getOrDefault(c.getId(), BigDecimal.ZERO));
                                m.put("checkDays", checkDaysOld.size() + evalDays);
                                m.put("totalScore", total);
                                return m;
                            })
                            .sorted(Comparator.comparing(m -> (BigDecimal) m.get("totalScore"), Comparator.reverseOrder()))
                            .toList();
                    for (int i = 0; i < list.size(); i++) {
                        list.get(i).put("rankNo", i + 1);
                    }
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("gradeId", e.getKey());
                    g.put("gradeName", gradeNames.getOrDefault(e.getKey(), ""));
                    g.put("classes", list);
                    grades.add(g);
                });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("evalFrom", era == null ? null : era.toString());
        data.put("grades", grades);
        return data;
    }

    /** 评选（按月冻结快照，批46 前的月度口径）：仅供历史月份重评，月度评选已停用不再自动新增 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> settle(String month, Long settleBy) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BizException(400, "月份格式须为 yyyy-MM");
        }
        if (ym.isAfter(YearMonth.now())) {
            throw new BizException(400, "不能评选未来月份");
        }
        return snapshot("MONTH", month, ym.atDay(1), ym.atEndOfMonth(), settleBy);
    }

    /**
     * 批46③ 评选改周（自然周周一~周日）：week 形如 2026-W41（ISO 周编号）；
     * 周区间=该 ISO 周的周一~周日，只允许评当前周及既往周。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> settleWeek(String week, Long settleBy) {
        LocalDate monday = parseWeekMonday(week);
        if (monday.isAfter(LocalDate.now().with(java.time.DayOfWeek.MONDAY))) {
            throw new BizException(400, "不能评选未来周");
        }
        return snapshot("WEEK", weekLabel(monday), monday, monday.plusDays(6), settleBy);
    }

    /** 周标签（yyyy-'W'ww，ISO 周）：周一所在的周编号年份+周次 */
    static String weekLabel(LocalDate monday) {
        int y = monday.get(java.time.temporal.WeekFields.ISO.weekBasedYear());
        int w = monday.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        return y + "-W" + String.format("%02d", w);
    }

    /** 解析周标签为周一日期（非法即 400） */
    private static LocalDate parseWeekMonday(String week) {
        if (week == null || !week.matches("\\d{4}-W\\d{2}")) {
            throw new BizException(400, "周格式须为 yyyy-Www（如 2026-W41）");
        }
        int y = Integer.parseInt(week.substring(0, 4));
        int w = Integer.parseInt(week.substring(6));
        if (w < 1 || w > 53) {
            throw new BizException(400, "周次不合法");
        }
        LocalDate probe = LocalDate.of(y, 1, 4).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        for (int i = 0; i < 53; i++) {
            if (weekLabel(probe).equals(week)) {
                return probe;
            }
            probe = probe.plusWeeks(1);
        }
        throw new BizException(400, "周次不存在：" + week);
    }

    /** 快照冻结（月/周共用）：rank 1-3=文明班金银铜；重评覆盖同周期旧快照 */
    private List<Map<String, Object>> snapshot(String periodType, String periodValue,
                                               LocalDate from, LocalDate to, Long settleBy) {
        List<Map<String, Object>> grades = (List<Map<String, Object>>) rank(from, to, null).get("grades");
        awardMapper.delete(new LambdaQueryWrapper<CivilityAward>()
                .eq(CivilityAward::getPeriodType, periodType).eq(CivilityAward::getPeriodValue, periodValue));
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> g : grades) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) g.get("classes");
            for (Map<String, Object> c : classes) {
                CivilityAward a = new CivilityAward();
                a.setPeriodType(periodType);
                a.setPeriodValue(periodValue);
                a.setClassId(((Number) c.get("classId")).longValue());
                a.setGradeId(((Number) g.get("gradeId")).longValue());
                a.setRankNo(((Number) c.get("rankNo")).intValue());
                a.setTotalScore((BigDecimal) c.get("totalScore"));
                a.setSettleTime(LocalDateTime.now().withNano(0));
                a.setSettleBy(settleBy);
                awardMapper.insert(a);
                if (a.getRankNo() <= 3) { // 快照全量存档，文明班=前三
                    Map<String, Object> m = new LinkedHashMap<>(c);
                    m.put("gradeName", g.get("gradeName"));
                    all.add(m);
                }
            }
        }
        return all;
    }

    /** 已评选结果（只出文明班前三，全量名次在快照表）；月/周同表按 type 区分 */
    public List<Map<String, Object>> awards(String periodType, String periodValue) {
        List<CivilityAward> rows = awardMapper.selectList(new LambdaQueryWrapper<CivilityAward>()
                .eq(periodType != null, CivilityAward::getPeriodType, periodType)
                .eq(periodValue != null, CivilityAward::getPeriodValue, periodValue)
                .orderByDesc(CivilityAward::getPeriodValue)
                .orderByAsc(CivilityAward::getGradeId)
                .orderByAsc(CivilityAward::getRankNo));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> classNames = clazzMapper.selectBatchIds(rows.stream()
                        .map(CivilityAward::getClassId).distinct().toList()).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName, (a, b) -> a));
        Map<Long, String> gradeNames = gradeMapper.selectBatchIds(rows.stream()
                        .map(CivilityAward::getGradeId).distinct().toList()).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
        return rows.stream().filter(a -> a.getRankNo() <= 3).<Map<String, Object>>map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("periodType", a.getPeriodType());
            m.put("periodValue", a.getPeriodValue());
            m.put("gradeName", gradeNames.getOrDefault(a.getGradeId(), ""));
            m.put("className", classNames.getOrDefault(a.getClassId(), ""));
            m.put("rankNo", a.getRankNo());
            m.put("totalScore", a.getTotalScore());
            return m;
        }).toList();
    }

    /** 每周一 08:10 自动评选上周（自然周；无人值守；管理端可手动重评覆盖） */
    @Scheduled(cron = "0 10 8 ? * MON")
    public void autoSettle() {
        try {
            String last = weekLabel(LocalDate.now().minusWeeks(1));
            int top = settleWeek(last, null).size();
            log.info("文明班周度自动评选完成：{} 文明班 {} 个", last, top);
        } catch (Exception e) {
            log.warn("文明班周度自动评选失败：{}", e.getMessage());
        }
    }
}
