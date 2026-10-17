package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Moment;
import com.aischool.server.mapper.MomentMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.moment.MomentService;
import com.aischool.server.service.report.PdfStoreService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 微光时刻：随手拍记录学生闪光时刻（照片走 MinIO，JWT 内联预览） */
@RestController
@RequestMapping("/api/moment")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;
    private final MomentMapper momentMapper;
    private final PdfStoreService pdfStore;

    /** 创建（multipart：photo + classId + studentIds + sceneTag + note） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestParam Long classId,
                                                   @RequestParam(required = false) List<Long> studentIds,
                                                   @RequestParam String sceneTag,
                                                   @RequestParam(required = false) String note,
                                                   @RequestParam("photos") List<MultipartFile> photos) {
        return ApiResponse.ok(momentService.create(AuthUtil.current(), classId, studentIds, sceneTag, note, photos));
    }

    /** 场景标签字典（批6 漏项H：教师端下拉用，家长 403） */
    @GetMapping("/tags")
    public ApiResponse<List<Map<String, Object>>> tags() {
        return ApiResponse.ok(momentService.listTags(AuthUtil.current()));
    }

    /** 教师自建标签（批6 漏项H） */
    @PostMapping("/tags")
    public ApiResponse<Map<String, Object>> createTag(@Validated @RequestBody TagReq req) {
        return ApiResponse.ok(momentService.createTag(AuthUtil.current(), req.getName()));
    }

    @Data
    public static class TagReq {
        @NotBlank(message = "name 不能为空")
        private String name;
    }

    /** 班级最近微光（班级页「本周微光」轮播） */
    @GetMapping("/class")
    public ApiResponse<List<Map<String, Object>>> listByClass(@RequestParam Long classId,
                                                              @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(momentService.listByClass(AuthUtil.current(), classId, limit));
    }

    /** 某学生的微光（学生详情「TA的闪光时刻」） */
    @GetMapping("/student")
    public ApiResponse<List<Map<String, Object>>> listByStudent(@RequestParam Long studentId,
                                                                @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(momentService.listByStudent(AuthUtil.current(), studentId, limit));
    }

    /** 删除（仅记录教师本人或管理员） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        momentService.delete(AuthUtil.current(), id);
        return ApiResponse.ok();
    }

    /** 照片预览（inline）。流式+ETag+Cache-Control：家长端万人级拉图热路径，
        命中缓存/304 后 MinIO 与本服务带宽近似归零（同 ContentController 模式）。
        批62 多图：?i= 索引（默认 0=首张），photo_url 逗号分隔多对象名 */
    @GetMapping("/file/{id}")
    public ResponseEntity<InputStreamResource> file(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "0") int i) {
        Moment m = momentMapper.selectById(id);
        String[] parts = m == null || m.getPhotoUrl() == null
                ? new String[0] : m.getPhotoUrl().split(",");
        if (i < 0 || i >= parts.length) {
            throw new BizException(404, "照片不存在");
        }
        String objectName = parts[i];
        InputStream in = pdfStore.download(objectName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeOf(objectName)))
                .eTag("\"" + objectName + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(new InputStreamResource(in));
    }

    private String contentTypeOf(String objectName) {
        String ext = objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ext.equals("png") ? "image/png" : "image/jpeg";
    }
}
