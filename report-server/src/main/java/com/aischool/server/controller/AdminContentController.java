package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ContentItem;
import com.aischool.server.entity.Grade;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ContentItemMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 管理端：内容发布（通知公告 NOTICE / 育儿课堂 PARENTING，批2）。
 * 学校自助配置：封面图传 MinIO（先 /upload 得 objectName 再 create/update），
 * 范围=全校或指定班级；发布/下架单个+批量；删除连带清封面对象。
 * 育儿课堂视频=第三方平台外链（不直传视频文件，磁盘/流量吃不消）。
 */
@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

    private final ContentItemMapper contentMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final PermissionService permissionService;
    private final PdfStoreService pdfStore;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作内容发布");
    }

    // ────────────────────────── 列表 ──────────────────────────

    /** 分页列表（type 必选；keyword 匹配标题；status 空=全部） */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        checkAdmin();
        requireType(type);
        var p = contentMapper.selectPage(Page.of(page, Math.min(size, 100)),
                new LambdaQueryWrapper<ContentItem>()
                        .eq(ContentItem::getType, type)
                        .eq(status != null, ContentItem::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), ContentItem::getTitle, keyword)
                        .orderByDesc(ContentItem::getPublishTime)
                        .orderByDesc(ContentItem::getId));
        Map<Long, String> classNames = classNamesOf(p.getRecords());
        Map<Long, String> gradeNames = gradeNamesOf(p.getRecords());
        List<Map<String, Object>> records = p.getRecords().stream()
                .map(it -> row(it, classNames, gradeNames)).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("records", records);
        return ApiResponse.ok(data);
    }

    // ────────────────────────── 新建 / 编辑 ──────────────────────────

    /** 新建（status=1 直接发布，publishTime=now） */
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@Validated @RequestBody ItemReq req) {
        checkAdmin();
        ContentItem it = new ContentItem();
        apply(it, req);
        it.setType(req.getType());
        it.setCreateBy(AuthUtil.current().userId());
        if (req.getStatus() != null && req.getStatus() == 1) {
            it.setStatus(1);
            it.setPublishTime(java.time.LocalDateTime.now());
        } else {
            it.setStatus(0);
        }
        contentMapper.insert(it);
        return ApiResponse.ok(Map.of("id", it.getId()));
    }

    /** 编辑（只改内容字段，发布/下架走 /status 专用接口；封面被替换/清空时删旧对象） */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody ItemReq req) {
        checkAdmin();
        ContentItem it = find(id);
        String oldCover = it.getCoverUrl();
        apply(it, req);
        contentMapper.updateById(it);
        if (oldCover != null && !oldCover.isBlank() && !oldCover.equals(it.getCoverUrl())) {
            pdfStore.delete(oldCover);
        }
        return ApiResponse.ok();
    }

    /** 封面上传（jpg/jpeg/png ≤5MB，返回 objectName；重复上传各自独立对象，保存时清旧） */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        checkAdmin();
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择封面图片");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(400, "封面不能超过 5MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
            throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(400, "读取上传文件失败");
        }
        String objectName = "content/" + UUID.randomUUID() + "." + ext;
        pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length,
                file.getContentType() == null ? "image/jpeg" : file.getContentType());
        return ApiResponse.ok(Map.of("coverUrl", objectName));
    }

    // ────────────────────────── 发布/下架/删除（单个+批量） ──────────────────────────

    /** 发布/下架（首次发布写 publishTime） */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @Validated @RequestBody StatusReq req) {
        checkAdmin();
        ContentItem it = find(id);
        applyStatus(it, req.getStatus());
        contentMapper.updateById(it);
        return ApiResponse.ok();
    }

    /** 批量发布/下架 */
    @PutMapping("/batch/status")
    public ApiResponse<Void> batchStatus(@Validated @RequestBody BatchStatusReq req) {
        checkAdmin();
        List<ContentItem> items = contentMapper.selectBatchIds(req.getIds());
        for (ContentItem it : items) {
            applyStatus(it, req.getStatus());
            contentMapper.updateById(it);
        }
        return ApiResponse.ok();
    }

    /** 删除（连带清封面对象） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        checkAdmin();
        removeCover(find(id));
        contentMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /** 批量删除 */
    @DeleteMapping("/batch")
    public ApiResponse<Void> batchDelete(@Validated @RequestBody BatchIdsReq req) {
        checkAdmin();
        for (ContentItem it : contentMapper.selectBatchIds(req.getIds())) {
            removeCover(it);
            contentMapper.deleteById(it.getId());
        }
        return ApiResponse.ok();
    }

    // ────────────────────────── helpers ──────────────────────────

    /** 公共字段校验+落值（type 不在此处：create 定死不可改）；批66② 范围扩到 STAFF/GRADE */
    private void apply(ContentItem it, ItemReq req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(400, "标题不能为空");
        }
        String scope = switch (req.getScope() == null ? "" : req.getScope()) {
            case "CLASS" -> "CLASS";
            case ContentItem.SCOPE_STAFF -> ContentItem.SCOPE_STAFF;
            case ContentItem.SCOPE_GRADE -> ContentItem.SCOPE_GRADE;
            default -> "ALL";
        };
        if ("CLASS".equals(scope)) {
            if (req.getClassId() == null) {
                throw new BizException(400, "指定班级时须选择班级");
            }
            if (clazzMapper.selectById(req.getClassId()) == null) {
                throw new BizException(404, "班级不存在");
            }
        }
        if (ContentItem.SCOPE_GRADE.equals(scope)) {
            if (req.getGradeId() == null) {
                throw new BizException(400, "指定年级时须选择年级");
            }
            if (gradeMapper.selectById(req.getGradeId()) == null) {
                throw new BizException(404, "年级不存在");
            }
        }
        it.setTitle(req.getTitle().trim());
        it.setCoverUrl(blankToNull(req.getCoverUrl()));
        it.setVideoUrl(blankToNull(req.getVideoUrl()));
        it.setContent(blankToNull(req.getContent()));
        it.setScope(scope);
        it.setClassId("CLASS".equals(scope) ? req.getClassId() : null);
        it.setGradeId(ContentItem.SCOPE_GRADE.equals(scope) ? req.getGradeId() : null);
    }

    private void applyStatus(ContentItem it, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(400, "status 须为 0 或 1");
        }
        if (status == 1 && it.getPublishTime() == null) {
            it.setPublishTime(java.time.LocalDateTime.now());
        }
        it.setStatus(status);
    }

    private void removeCover(ContentItem it) {
        if (it.getCoverUrl() != null && !it.getCoverUrl().isBlank()) {
            pdfStore.delete(it.getCoverUrl());
        }
    }

    private ContentItem find(Long id) {
        ContentItem it = contentMapper.selectById(id);
        if (it == null) {
            throw new BizException(404, "内容不存在");
        }
        return it;
    }

    private void requireType(String type) {
        if (!ContentItem.TYPE_NOTICE.equals(type) && !ContentItem.TYPE_PARENTING.equals(type)) {
            throw new BizException(400, "type 须为 NOTICE 或 PARENTING");
        }
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private Map<Long, String> classNamesOf(List<ContentItem> items) {
        List<Long> ids = items.stream().map(ContentItem::getClassId)
                .filter(c -> c != null).distinct().toList();
        return ids.isEmpty() ? Map.of() : clazzMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName));
    }

    private Map<Long, String> gradeNamesOf(List<ContentItem> items) {
        List<Long> ids = items.stream().map(ContentItem::getGradeId)
                .filter(g -> g != null).distinct().toList();
        return ids.isEmpty() ? Map.of() : gradeMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
    }

    private Map<String, Object> row(ContentItem it, Map<Long, String> classNames, Map<Long, String> gradeNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("type", it.getType());
        m.put("title", it.getTitle());
        m.put("coverUrl", it.getCoverUrl());
        m.put("videoUrl", it.getVideoUrl());
        m.put("content", it.getContent());
        m.put("scope", it.getScope());
        m.put("classId", it.getClassId());
        m.put("className", it.getClassId() == null ? null : classNames.get(it.getClassId()));
        m.put("gradeId", it.getGradeId());
        m.put("gradeName", it.getGradeId() == null ? null : gradeNames.get(it.getGradeId()));
        m.put("createBy", it.getCreateBy());
        m.put("status", it.getStatus());
        m.put("publishTime", it.getPublishTime() == null ? null : it.getPublishTime().toString());
        m.put("createTime", it.getCreateTime() == null ? null : it.getCreateTime().toString());
        return m;
    }

    // ────────────────────────── req ──────────────────────────

    @Data
    public static class ItemReq {
        @NotBlank(message = "type 不能为空")
        private String type;
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多 200 字")
        private String title;
        private String coverUrl;
        @Size(max = 500, message = "视频链接最多 500 字")
        private String videoUrl;
        private String content;
        private String scope;
        private Long classId;
        /** 批66② GRADE 范围的目标年级 */
        private Long gradeId;
        /** 仅 create 消费：1=保存并发布；update 忽略此字段（发布/下架走专用接口） */
        private Integer status;
    }

    @Data
    public static class StatusReq {
        @NotNull(message = "status 不能为空")
        private Integer status;
    }

    @Data
    public static class BatchIdsReq {
        @NotEmpty(message = "ids 不能为空")
        private List<Long> ids;
    }

    @Data
    public static class BatchStatusReq {
        @NotEmpty(message = "ids 不能为空")
        private List<Long> ids;
        @NotNull(message = "status 不能为空")
        private Integer status;
    }
}
