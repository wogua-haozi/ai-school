package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.DutySchedule;
import com.aischool.server.entity.Term;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.DutyScheduleMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.service.auth.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 值班排班管理（批6 漏项C1；批56③ 开关退役——值日口径固定，仅保留排班表维护） */
@RestController
@RequestMapping("/api/admin/duty")
@RequiredArgsConstructor
public class AdminDutyController {

    private final DutyScheduleMapper dutyScheduleMapper;
    private final UserMapper userMapper;
    private final TermMapper termMapper;
    private final PermissionService permissionService;

    /** 某日排班（默认今天；含教师姓名） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String date) {
        permissionService.checkAdminAccess("只有管理员可配置值班");
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        List<DutySchedule> rows = dutyScheduleMapper.selectList(new LambdaQueryWrapper<DutySchedule>()
                .eq(DutySchedule::getDutyDate, d)
                .orderByAsc(DutySchedule::getId));
        Map<Long, String> names = rows.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(rows.stream().map(DutySchedule::getTeacherId).distinct().toList())
                        .stream().collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return ApiResponse.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("dutyDate", r.getDutyDate());
            m.put("teacherId", r.getTeacherId());
            m.put("teacherName", names.getOrDefault(r.getTeacherId(), ""));
            m.put("note", r.getNote());
            return m;
        }).toList());
    }

    /** 批量排班（批57②）：日期范围 × 多教师一次排完；同日同师已排的跳过（幂等） */
    @PostMapping
    public ApiResponse<Map<String, Integer>> add(@Validated @RequestBody DutyReq req) {
        permissionService.checkAdminAccess("只有管理员可配置值班");
        LocalDate end = req.getDateEnd() == null || req.getDateEnd().isBefore(req.getDutyDate())
                ? req.getDutyDate() : req.getDateEnd();
        if (java.time.temporal.ChronoUnit.DAYS.between(req.getDutyDate(), end) > 365) {
            throw new BizException(400, "日期范围不能超过 1 年");
        }
        if (req.getTeacherIds() == null || req.getTeacherIds().isEmpty()) {
            throw new BizException(400, "请选择至少一位教师");
        }
        if (userMapper.selectBatchIds(req.getTeacherIds()).size() != req.getTeacherIds().size()) {
            throw new BizException(404, "包含不存在的教师");
        }
        int added = 0;
        int skipped = 0;
        for (LocalDate d = req.getDutyDate(); !d.isAfter(end); d = d.plusDays(1)) {
            for (Long tid : req.getTeacherIds()) {
                DutySchedule row = new DutySchedule();
                row.setDutyDate(d);
                row.setTeacherId(tid);
                row.setNote(req.getNote());
                try {
                    dutyScheduleMapper.insert(row);
                    added++;
                } catch (DuplicateKeyException e) {
                    skipped++; // 该教师当天已在名单：跳过继续，整体幂等
                }
            }
        }
        return ApiResponse.ok(Map.of("added", added, "skipped", skipped));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        permissionService.checkAdminAccess("只有管理员可配置值班");
        dutyScheduleMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * 批66① 按周模板批量生成：整学期排班一键排（每学期重排不再逐日手工/导 SQL）。
     * weeklyEven 不传=每周同一套；传了则按「教学周」单双周交替（教学周以当前学期开学日所在周为第 1 周）。
     * 生成前先清掉日期范围内、两套模板涉及教师的旧排班（重生成即覆盖，其他人手动加的行不动），再写入。
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Integer>> generate(@Validated @RequestBody GenerateReq req) {
        permissionService.checkAdminAccess("只有管理员可配置值班");
        if (req.getToDate().isBefore(req.getFromDate())) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(req.getFromDate(), req.getToDate()) > 400) {
            throw new BizException(400, "日期范围不能超过 400 天");
        }
        Map<DayOfWeek, List<Long>> odd = parseTemplate(req.getWeekly());
        Map<DayOfWeek, List<Long>> even = req.getWeeklyEven() == null || req.getWeeklyEven().isEmpty()
                ? odd : parseTemplate(req.getWeeklyEven());
        LinkedHashSet<Long> allIds = new LinkedHashSet<>();
        odd.values().forEach(allIds::addAll);
        even.values().forEach(allIds::addAll);
        if (allIds.isEmpty()) {
            throw new BizException(400, "模板里至少要安排一位教师");
        }
        if (userMapper.selectBatchIds(allIds).size() != allIds.size()) {
            throw new BizException(404, "包含不存在的教师");
        }
        // 教学周锚点：当前学期开学日所在周的周一为第 1 周起点
        Term term = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                .eq(Term::getIsCurrent, 1).last("LIMIT 1"));
        if (term == null || term.getStartDate() == null) {
            throw new BizException(400, "未配置当前学期，无法判定单双周");
        }
        LocalDate week1Mon = term.getStartDate().with(DayOfWeek.MONDAY);
        // 覆盖式重生成：只清模板涉及教师在该范围内的旧行
        int deleted = dutyScheduleMapper.delete(new LambdaQueryWrapper<DutySchedule>()
                .between(DutySchedule::getDutyDate, req.getFromDate(), req.getToDate())
                .in(DutySchedule::getTeacherId, allIds));
        int added = 0;
        for (LocalDate d = req.getFromDate(); !d.isAfter(req.getToDate()); d = d.plusDays(1)) {
            boolean oddWeek = (ChronoUnit.WEEKS.between(week1Mon, d.with(DayOfWeek.MONDAY)) + 1) % 2 == 1;
            List<Long> ids = (oddWeek ? odd : even).get(d.getDayOfWeek());
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            for (Long tid : ids) {
                DutySchedule row = new DutySchedule();
                row.setDutyDate(d);
                row.setTeacherId(tid);
                try {
                    dutyScheduleMapper.insert(row);
                    added++;
                } catch (DuplicateKeyException e) {
                    // 同日同师（模板内重复/他人已排）：跳过保持幂等
                }
            }
        }
        return ApiResponse.ok(Map.of("added", added, "deleted", deleted));
    }

    /** 周模板入参校验：键=MONDAY~SUNDAY，值=教师ids（去重保序） */
    private Map<DayOfWeek, List<Long>> parseTemplate(Map<String, List<Long>> weekly) {
        Map<DayOfWeek, List<Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : weekly.entrySet()) {
            DayOfWeek dow;
            try {
                dow = DayOfWeek.valueOf(e.getKey().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BizException(400, "星期键只能是 MONDAY~SUNDAY：" + e.getKey());
            }
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                out.put(dow, new ArrayList<>(new LinkedHashSet<>(e.getValue())));
            }
        }
        if (out.isEmpty()) {
            throw new BizException(400, "周模板至少要安排一天");
        }
        return out;
    }

    @Data
    public static class DutyReq {
        @NotNull(message = "dutyDate 不能为空")
        private LocalDate dutyDate;
        /** 结束日（含）；缺省=仅 dutyDate 当天 */
        private LocalDate dateEnd;
        @NotEmpty(message = "请选择至少一位教师")
        private List<Long> teacherIds;
        private String note;
    }

    /** 批66① 周模板生成入参 */
    @Data
    public static class GenerateReq {
        @NotNull(message = "fromDate 不能为空")
        private LocalDate fromDate;
        @NotNull(message = "toDate 不能为空")
        private LocalDate toDate;
        /** 单周模板：MONDAY~SUNDAY → 教师ids（当天无人可缺省） */
        @NotEmpty(message = "weekly 不能为空")
        private Map<String, List<Long>> weekly;
        /** 双周模板（可选）；不传=每周同一套 */
        private Map<String, List<Long>> weeklyEven;
    }
}
