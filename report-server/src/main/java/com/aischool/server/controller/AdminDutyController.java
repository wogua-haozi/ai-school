package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.DutySchedule;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.DutyScheduleMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.service.auth.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @PostMapping
    public ApiResponse<Void> add(@Validated @RequestBody DutyReq req) {
        permissionService.checkAdminAccess("只有管理员可配置值班");
        if (userMapper.selectById(req.getTeacherId()) == null) {
            throw new BizException(404, "教师不存在");
        }
        DutySchedule row = new DutySchedule();
        row.setDutyDate(req.getDutyDate());
        row.setTeacherId(req.getTeacherId());
        row.setNote(req.getNote());
        try {
            dutyScheduleMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "该教师当天已在值班名单");
        }
        return ApiResponse.ok();
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
        @NotNull(message = "teacherId 不能为空")
        private Long teacherId;
        private String note;
    }
}
