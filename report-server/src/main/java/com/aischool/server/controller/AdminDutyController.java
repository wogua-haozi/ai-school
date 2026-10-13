package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.DutySchedule;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.DutyScheduleMapper;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
}
