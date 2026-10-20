package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.Exported;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.score.ScoreService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 成绩管理：考试/录入/Excel 导入/排名（权限见各端点；录入=管理员或该班该科任课教师） */
@RestController
@RequestMapping("/api/score")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scoreService;
    private final PermissionService permissionService;

    @Data
    public static class ExamReq {
        @NotNull(message = "termId 不能为空")
        private Long termId;
        @NotBlank(message = "name 不能为空")
        private String name;
        @NotNull(message = "examDate 不能为空")
        private LocalDate examDate;
        @NotEmpty(message = "subjects 不能为空")
        private List<ScoreService.SubjectReq> subjects;
    }

    @Data
    public static class EntryReq {
        @NotNull(message = "examId 不能为空")
        private Long examId;
        @NotNull(message = "subjectId 不能为空")
        private Long subjectId;
        private Long classId; // 批63 可空=年级模式（按 studentId 逐行校验跨班保存）
        private List<ScoreService.RowReq> rows;
    }

    @Data
    public static class GradeEntryReq {
        @NotNull(message = "examId 不能为空")
        private Long examId;
        private Long subjectId; // 批63c 可空=总分等级（subject_id NULL 行）
        private List<ScoreService.RowReq> rows;
    }

    /** 建考试（批49 随上传权限收紧：ADMIN/书记/级长） */
    @PostMapping("/exam")
    public ApiResponse<Map<String, Object>> createExam(@Validated @RequestBody ExamReq req) {
        Long id = scoreService.createExam(AuthUtil.current(), req.getTermId(), req.getName(),
                req.getExamDate(), req.getSubjects());
        return ApiResponse.ok(Map.of("examId", id));
    }

    /** 批63 修改考试：科目增删+各科满分（删科连带清该科成绩，前端二次确认） */
    @PutMapping("/exam/{id}")
    public ApiResponse<Void> updateExam(@PathVariable Long id,
                                        @Validated @RequestBody ExamUpdateReq req) {
        scoreService.updateExam(AuthUtil.current(), id, req.getSubjects());
        return ApiResponse.ok(null);
    }

    @Data
    public static class ExamUpdateReq {
        @NotEmpty(message = "subjects 不能为空")
        private List<ScoreService.SubjectReq> subjects;
    }

    /** 考试列表（批49 限成绩可见角色，教师零入口；附 entryOpen 窗口状态） */
    @GetMapping("/exam/list")
    public ApiResponse<List<Map<String, Object>>> examList() {
        return ApiResponse.ok(scoreService.examList(AuthUtil.current()));
    }

    /** 批40e 撤回普适：删除整场考试（含其下全部成绩，误建撤回）。批49：管理员/书记（全部权限）或 ADMIN_ACCESS 领导 */
    @DeleteMapping("/exam/{id}")
    public ApiResponse<Void> deleteExam(@PathVariable Long id) {
        checkScoreAdmin("只有管理员或书记可删除考试");
        scoreService.deleteExam(id);
        return ApiResponse.ok(null);
    }

    /** 开关录入窗口（批4 管理端考试页签）。批49：管理员/书记（全部权限）或 ADMIN_ACCESS 领导 */
    @PutMapping("/exam/{id}/entry-open")
    public ApiResponse<Void> setEntryOpen(@PathVariable Long id, @RequestBody EntryOpenReq req) {
        checkScoreAdmin("只有管理员或书记可开关成绩录入窗口");
        scoreService.setEntryOpen(id, Boolean.TRUE.equals(req.getOpen()));
        return ApiResponse.ok(null);
    }

    /** 成绩域管理动作门（批49 甲方口径：管理员、书记拥有全部权限） */
    private void checkScoreAdmin(String message) {
        if ("SECRETARY".equals(AuthUtil.current().role())) {
            return;
        }
        permissionService.checkAdminAccess(message);
    }

    @Data
    public static class EntryOpenReq {
        private Boolean open;
    }

    /** 某班/某年级在某考试下的科目上下文（批63 classId/gradeId 二选一；班主任/科任只读） */
    @GetMapping("/subject-context")
    public ApiResponse<List<Map<String, Object>>> subjectContext(@RequestParam Long examId,
                                                                 @RequestParam(required = false) Long classId,
                                                                 @RequestParam(required = false) Long gradeId) {
        return ApiResponse.ok(scoreService.subjectContext(AuthUtil.current(), examId, classId, gradeId));
    }

    /** 成绩单（classId 单班 / gradeId 年级；subjectId 空=总分；班主任/科任只读无排名） */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam Long examId,
                                                 @RequestParam(required = false) Long subjectId,
                                                 @RequestParam(required = false) Long classId,
                                                 @RequestParam(required = false) Long gradeId) {
        return ApiResponse.ok(scoreService.listScores(AuthUtil.current(), examId, subjectId, classId, gradeId));
    }

    /** 成绩导出（含名次列，限管理面角色）：当前成绩单 → xlsx */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Long examId,
                                         @RequestParam(required = false) Long subjectId,
                                         @RequestParam(required = false) Long classId,
                                         @RequestParam(required = false) Long gradeId) {
        Exported f = scoreService.exportScores(AuthUtil.current(), examId, subjectId, classId, gradeId);
        String filename = URLEncoder.encode(f.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(f.content().length)
                .body(f.content());
    }

    /** 批量录入（score=null 清除；classId 空=年级模式跨班保存，批63） */
    @PutMapping("/entry")
    public ApiResponse<Map<String, Object>> entry(@Validated @RequestBody EntryReq req) {
        return ApiResponse.ok(scoreService.entry(AuthUtil.current(), req.getExamId(), req.getSubjectId(),
                req.getClassId(), req.getRows()));
    }

    /** 批63c 等级录入（上传制，不动分数）：subjectId 空=总分等级；rows.grade 空=清除 */
    @PutMapping("/entry-grade")
    public ApiResponse<Map<String, Object>> entryGrade(@Validated @RequestBody GradeEntryReq req) {
        return ApiResponse.ok(scoreService.entryGrade(AuthUtil.current(), req.getExamId(),
                req.getSubjectId(), req.getRows()));
    }

    /** Excel 导入（单班多科：file + examId/classId，科目列按模板表头匹配，批63） */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importExcel(@RequestParam Long examId,
                                                        @RequestParam Long classId,
                                                        @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(scoreService.importExcel(AuthUtil.current(), examId, classId, file));
    }

    /** 下载导入模板（该班名册预填，列=考试设定科目，批63） */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam Long examId, @RequestParam Long classId) {
        byte[] bytes = scoreService.template(AuthUtil.current(), examId, classId);
        String filename = URLEncoder.encode("成绩导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }
}
