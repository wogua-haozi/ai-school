package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Report;
import com.aischool.server.entity.ReportTask;
import com.aischool.server.entity.Student;
import com.aischool.server.mapper.ReportMapper;
import com.aischool.server.mapper.ReportTaskMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.report.PdfStoreService;
import com.aischool.server.service.report.ReportDataBuilder;
import com.aischool.server.service.report.ReportTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportTaskService taskService;
    private final ReportTaskMapper taskMapper;
    private final ReportMapper reportMapper;
    private final ReportDataBuilder dataBuilder;
    private final PdfStoreService pdfStore;
    private final DataScopeService dataScope;
    private final com.aischool.server.mapper.ClazzMapper clazzMapper;

    // ───────────────── 发起生成 ─────────────────

    @Data
    public static class GenerateReq {
        @NotNull(message = "studentId 不能为空")
        private Long studentId;
        @NotNull(message = "termId 不能为空")
        private Long termId;
        /** 报告类型（批26）：TERM 学期=默认 / YEAR 学年 / SCHOOL 在校 */
        private String reportType;
        /** 期次（批42）：MID 期中 / FINAL 期末，缺省=期末；仅 TERM 报告生效 */
        private String reportPeriod;
    }

    @Data
    public static class GenerateBatchReq {
        @NotNull(message = "classId 不能为空")
        private Long classId;
        @NotNull(message = "termId 不能为空")
        private Long termId;
        /** 报告类型（批26）：TERM 学期=默认 / YEAR 学年 / SCHOOL 在校 */
        private String reportType;
        /** 期次（批42）：MID 期中 / FINAL 期末，缺省=期末；仅 TERM 报告生效 */
        private String reportPeriod;
    }

    @Data
    public static class GenerateGradeReq {
        @NotNull(message = "gradeId 不能为空")
        private Long gradeId;
        @NotNull(message = "termId 不能为空")
        private Long termId;
        /** 报告类型（批26）：TERM 学期=默认 / YEAR 学年 / SCHOOL 在校 */
        private String reportType;
        /** 期次（批42）：MID 期中 / FINAL 期末，缺省=期末；仅 TERM 报告生效 */
        private String reportPeriod;
    }

    /** 单份生成：30s 内出 PDF（优先级最高） */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@Validated @RequestBody GenerateReq req) {
        var user = AuthUtil.current();
        Student student = dataScope.checkStudentAccess(user, req.studentId);
        if (!"ADMIN".equals(user.role())) {
            dataScope.checkClassOperable(user, student.getClassId());
        }
        ReportTask task = taskService.createTask("单生", req.studentId, req.termId, typeOf(req.reportType), periodOf(req.reportPeriod), user.userId());
        return ApiResponse.ok(taskView(task));
    }

    /** 班级批量生成 */
    @PostMapping("/generate-batch")
    public ApiResponse<Map<String, Object>> generateBatch(@Validated @RequestBody GenerateBatchReq req) {
        var user = AuthUtil.current();
        dataScope.checkClassOperable(user, req.classId);
        ReportTask task = taskService.createTask("班级", req.classId, req.termId, typeOf(req.reportType), periodOf(req.reportPeriod), user.userId());
        return ApiResponse.ok(taskView(task));
    }

    /** 年级批量生成（仅管理员：覆盖全部班级，班主任无年级操作权） */
    @PostMapping("/generate-grade")
    public ApiResponse<Map<String, Object>> generateGrade(@Validated @RequestBody GenerateGradeReq req) {
        var user = AuthUtil.current();
        if (!"ADMIN".equals(user.role())) {
            throw new BizException(403, "只有管理员可生成全年级报告");
        }
        ReportTask task = taskService.createTask("年级", req.gradeId, req.termId, typeOf(req.reportType), periodOf(req.reportPeriod), user.userId());
        return ApiResponse.ok(taskView(task));
    }

    /** 失败重试 */
    @PostMapping("/task/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable Long id) {
        var user = AuthUtil.current();
        ReportTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(404, "任务不存在");
        }
        checkTaskReadable(user, task);
        return ApiResponse.ok(taskView(taskService.retryTask(id, user.userId())));
    }

    // ───────────────── 进度与列表 ─────────────────

    @GetMapping("/task/{id}")
    public ApiResponse<Map<String, Object>> task(@PathVariable Long id) {
        var user = AuthUtil.current();
        ReportTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(404, "任务不存在");
        }
        checkTaskReadable(user, task);
        Map<String, Object> m = taskService.progress(id);
        m.put("scope", task.getScope());
        m.put("createTime", task.getCreateTime());
        return ApiResponse.ok(m);
    }

    @GetMapping("/task/list")
    public ApiResponse<List<Map<String, Object>>> taskList(@RequestParam(defaultValue = "10") long limit) {
        var user = AuthUtil.current();
        List<ReportTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ReportTask>()
                .orderByDesc(ReportTask::getId).last("LIMIT " + Math.min(limit, 50)));
        return ApiResponse.ok(tasks.stream()
                .filter(t -> canReadTask(user, t))
                .map(this::taskView).toList());
    }

    /** 学生的最新报告（报告列表页） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam Long classId,
                                                       @RequestParam Long termId,
                                                       @RequestParam(defaultValue = "TERM") String scopeType) {
        var user = AuthUtil.current();
        List<Long> visible = dataScope.visibleClassIds(user);
        if (visible != null && !visible.contains(classId)) {
            throw new BizException(403, "无权访问该班级");
        }
        // 每个学生×每期次取最新一份（批42 分期归档：期中/期末各一行；重生成只显最新版）
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getTermId, termId)
                .eq(Report::getScopeType, scopeType)
                .in(Report::getStatus, "成功", "失败", "排队", "渲染中")
                .inSql(Report::getStudentId,
                        "SELECT id FROM t_student WHERE class_id = " + classId)
                .orderByDesc(Report::getId));
        Map<String, Report> latest = new java.util.LinkedHashMap<>();
        for (Report r : reports) {
            latest.putIfAbsent(r.getStudentId() + ":" + ("MID".equals(r.getPeriod()) ? "MID" : "FINAL"), r);
        }
        return ApiResponse.ok(latest.values().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportId", r.getId());
            m.put("studentId", r.getStudentId());
            m.put("scopeType", r.getScopeType() == null ? "TERM" : r.getScopeType());
            m.put("period", "MID".equals(r.getPeriod()) ? "MID" : "FINAL");
            m.put("status", r.getStatus());
            m.put("fileUrl", r.getFileUrl());
            m.put("genTime", r.getGenTime());
            m.put("error", r.getError());
            m.put("taskId", r.getTaskId());
            return m;
        }).toList());
    }

    /** 契约 JSON（调试/契约零漂移校验用，同渲染核心输入） */
    @GetMapping("/data/{studentId}")
    public ApiResponse<Map<String, Object>> data(@PathVariable Long studentId,
                                                 @RequestParam Long termId) {
        var user = AuthUtil.current();
        dataScope.checkStudentAccess(user, studentId);
        return ApiResponse.ok(dataBuilder.build(studentId, termId));
    }

    // ───────────────── PDF 预览 / 下载 ─────────────────

    @GetMapping("/file/{reportId}")
    public ResponseEntity<byte[]> file(@PathVariable Long reportId,
                                       @RequestParam(defaultValue = "inline") String disposition) throws IOException {
        var user = AuthUtil.current();
        Report report = reportMapper.selectById(reportId);
        if (report == null || !"成功".equals(report.getStatus()) || report.getFileUrl() == null) {
            throw new BizException(404, "报告尚未生成成功");
        }
        dataScope.checkStudentAccess(user, report.getStudentId());
        // PDF 约 600KB：整体读入同步返回。不用 StreamingResponseBody——其异步派发会再次穿过
        // Security 链（JWT 过滤器跳过 ASYNC 派发导致上下文为空），连接被中途掐断造成下载截断。
        byte[] bytes;
        try (InputStream in = pdfStore.download(report.getFileUrl())) {
            bytes = in.readAllBytes();
        }
        String fileName = "student-" + report.getStudentId() + "-" + report.getId() + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(bytes.length);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                ("download".equals(disposition) ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ───────────────── 内部 ─────────────────

    private Map<String, Object> taskView(ReportTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getId());
        m.put("termId", t.getTermId());
        m.put("scope", t.getScope());
        m.put("scopeType", t.getScopeType() == null ? "TERM" : t.getScopeType());
        m.put("targetId", t.getTargetId());
        m.put("status", t.getStatus());
        m.put("total", t.getTotal());
        m.put("done", t.getDone());
        m.put("failed", t.getFailed());
        return m;
    }

    private static String typeOf(String reportType) {
        return reportType == null || reportType.isBlank() ? "TERM" : reportType;
    }

    /** 期次归一（批42）：只认 MID，其余一律 FINAL（默认期末） */
    private static String periodOf(String reportPeriod) {
        return "MID".equals(reportPeriod) ? "MID" : "FINAL";
    }

    private void checkTaskReadable(com.aischool.server.security.UserPrincipal user, ReportTask task) {
        // 批64 书记与领导平权（读全量报告任务）
        if ("ADMIN".equals(user.role()) || "LEADER".equals(user.role()) || "SECRETARY".equals(user.role())) {
            return;
        }
        if ("单生".equals(task.getScope())) {
            dataScope.checkStudentAccess(user, task.getTargetId());
            return;
        }
        List<Long> visible = dataScope.visibleClassIds(user);
        boolean ok = false;
        if (visible != null) {
            if ("班级".equals(task.getScope())) {
                ok = visible.contains(task.getTargetId());
            } else if ("年级".equals(task.getScope())) {
                ok = visible.stream().anyMatch(cid -> {
                    com.aischool.server.entity.Clazz c = clazzMapper.selectById(cid);
                    return c != null && c.getGradeId().equals(task.getTargetId());
                });
            }
        }
        if (!ok) {
            throw new BizException(403, "无权查看该任务");
        }
    }

    private boolean canReadTask(com.aischool.server.security.UserPrincipal user, ReportTask t) {
        try {
            checkTaskReadable(user, t);
            return true;
        } catch (BizException e) {
            return false;
        }
    }
}
