package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.excel.ExcelWalkHelper;
import com.aischool.server.service.walk.WalkClassService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 管理端：走班（教学班）全量替换导入（批75）。换班=下载模板→填新分班表→预览差异→确认替换，校方自助维护 */
@RestController
@RequestMapping("/api/admin/walk-class")
@RequiredArgsConstructor
public class AdminWalkClassController {

    private final WalkClassService walkClassService;
    private final ExcelWalkHelper excelWalk;
    private final PermissionService permissionService;

    /** 当前全部走班（按年级/学科/名称排序，附教师与人数） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        checkAdmin();
        return ApiResponse.ok(walkClassService.listAll());
    }

    /** 某走班名册（学号/姓名/行政班） */
    @GetMapping("/{id}/students")
    public ApiResponse<List<Map<String, Object>>> students(@PathVariable Long id) {
        checkAdmin();
        return ApiResponse.ok(walkClassService.rosterView(id));
    }

    /** 清空全部走班（取消走班制用）：走班与学生映射两表全删；成绩/微光历史不引用走班，不受影响 */
    @DeleteMapping("/clear")
    public ApiResponse<Map<String, Object>> clear() {
        checkAdmin();
        return ApiResponse.ok(walkClassService.clearAll());
    }

    /** 预览：校验并给出与现状的差异（不落库）；表内有错则逐行列出 */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importPreview(@RequestParam("file") MultipartFile file) {
        checkAdmin();
        return ApiResponse.ok(walkClassService.preview(file));
    }

    /** 确认导入：按文件覆盖的 年级+学科 对整体替换（任一行有误整体不导入） */
    @PostMapping("/import/commit")
    public ApiResponse<Map<String, Object>> importCommit(@RequestParam("file") MultipartFile file) {
        checkAdmin();
        return ApiResponse.ok(walkClassService.commit(file));
    }

    /** 导入模板（两个工作表：学生分班/老师分工，仅表头） */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template() {
        checkAdmin();
        byte[] bytes = excelWalk.template();
        String filename = URLEncoder.encode("走班导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可维护走班");
    }
}
