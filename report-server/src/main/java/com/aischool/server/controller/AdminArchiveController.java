package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Honor;
import com.aischool.server.entity.Moment;
import com.aischool.server.entity.Report;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.StudentLeave;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.HonorMapper;
import com.aischool.server.mapper.MomentMapper;
import com.aischool.server.mapper.ReportMapper;
import com.aischool.server.mapper.StudentLeaveMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：毕业班文件归档（批28）——按班打包导出（学生照片/荣誉证书/微光照片/请假凭证/成长报告 PDF）；
 * 导出过后才允许清理学生照片（释放存储，档案数据不动）。
 */
@RestController
@RequestMapping("/api/admin/archive")
@RequiredArgsConstructor
public class AdminArchiveController {

    private final ClazzMapper clazzMapper;
    private final StudentMapper studentMapper;
    private final HonorMapper honorMapper;
    private final MomentMapper momentMapper;
    private final StudentLeaveMapper leaveMapper;
    private final ReportMapper reportMapper;
    private final PdfStoreService pdfStore;
    private final PermissionService permissionService;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可执行文件归档");
    }

    private Clazz requireClass(Long classId) {
        Clazz c = clazzMapper.selectById(classId);
        if (c == null) {
            throw new BizException(404, "班级不存在");
        }
        return c;
    }

    /** 归档预览：该班各类文件数量 + 是否已导出过（清理的前置） */
    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam Long classId) {
        checkAdmin();
        Clazz c = requireClass(classId);
        ArchiveBundle b = collect(classId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("className", c.getName());
        m.put("archivedTime", c.getArchivedTime() == null ? null : c.getArchivedTime().toString());
        m.put("students", b.students.size());
        m.put("reading", b.students.stream().filter(s -> "在读".equals(s.getStatus())).count());
        m.put("photos", b.photos.size());
        m.put("honors", b.honors.size());
        m.put("moments", b.moments.size());
        m.put("leaves", b.leaves.size());
        m.put("reports", b.reports.size());
        m.put("totalFiles", b.photos.size() + b.honors.size() + b.moments.size()
                + b.leaves.size() + b.reports.size());
        return ApiResponse.ok(m);
    }

    /** 导出该班文件包（zip 流式，大包可能需要几分钟）；导出成功后记录归档时间 */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam Long classId) {
        checkAdmin();
        Clazz c = requireClass(classId);
        ArchiveBundle b = collect(classId);
        List<String> skipped = new ArrayList<>();
        StreamingResponseBody body = out -> {
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                Map<String, String> studentDirs = new LinkedHashMap<>();
                for (Student s : b.students) {
                    studentDirs.put(s.getId().toString(),
                            safe(s.getStudentNo() + "_" + s.getName()));
                }
                // 学生照片
                for (Map.Entry<String, String> e : b.photos) {
                    writeTo(zip, "学生照片/" + studentDirs.getOrDefault(e.getKey(), e.getKey())
                            + "." + ext(e.getValue()), e.getValue(), skipped);
                }
                // 荣誉证书
                for (ArchiveFile f : b.honors) {
                    writeTo(zip, "荣誉证书/" + studentDirs.getOrDefault(f.ownerKey, f.ownerKey)
                            + "/" + safe(f.title) + "." + ext(f.objectName), f.objectName, skipped);
                }
                // 请假凭证（photos JSON 逐张）
                for (ArchiveFile f : b.leaves) {
                    writeTo(zip, "请假凭证/" + studentDirs.getOrDefault(f.ownerKey, f.ownerKey)
                            + "/" + safe(f.title) + "." + ext(f.objectName), f.objectName, skipped);
                }
                // 班级微光照片
                for (ArchiveFile f : b.moments) {
                    writeTo(zip, "微光照片/" + safe(f.title) + "." + ext(f.objectName), f.objectName, skipped);
                }
                // 成长报告 PDF（教师版+家长版）
                for (ArchiveFile f : b.reports) {
                    writeTo(zip, "成长报告/" + studentDirs.getOrDefault(f.ownerKey, f.ownerKey)
                            + "/" + safe(f.title) + "." + ext(f.objectName), f.objectName, skipped);
                }
                // 清单
                StringBuilder manifest = new StringBuilder();
                manifest.append("班级：").append(c.getName()).append('\n');
                manifest.append("导出时间：").append(LocalDateTime.now()).append('\n');
                manifest.append("学生数：").append(b.students.size())
                        .append("，文件数：").append(b.totalFiles()).append('\n');
                if (!skipped.isEmpty()) {
                    manifest.append("\n以下对象在存储中缺失已跳过（").append(skipped.size()).append("）：\n");
                    skipped.forEach(s -> manifest.append("  ").append(s).append('\n'));
                }
                zip.putNextEntry(new java.util.zip.ZipEntry("归档清单.txt"));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } finally {
                // 全部条目写完（含缺失跳过）即视为已归档——清理的前提
                clazzMapper.update(null, new LambdaUpdateWrapper<Clazz>()
                        .eq(Clazz::getId, classId)
                        .set(Clazz::getArchivedTime, LocalDateTime.now()));
            }
        };
        String filename = URLEncoder.encode("归档-" + c.getName() + ".zip", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /** 清理该班学生照片（归档导出过才允许；仅照片，其余文件保留线上） */
    @PostMapping("/cleanup")
    public ApiResponse<Map<String, Object>> cleanup(@RequestParam Long classId) {
        checkAdmin();
        Clazz c = requireClass(classId);
        if (c.getArchivedTime() == null) {
            throw new BizException(400, "请先导出归档包，再执行清理");
        }
        int deleted = 0;
        for (Student s : studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId)
                .isNotNull(Student::getPhotoUrl)
                .ne(Student::getPhotoUrl, ""))) {
            pdfStore.delete(s.getPhotoUrl());
            studentMapper.update(null, new LambdaUpdateWrapper<Student>()
                    .eq(Student::getId, s.getId()).set(Student::getPhotoUrl, null));
            deleted++;
        }
        return ApiResponse.ok(Map.of("deleted", deleted));
    }

    // ────────────────── 收集对象清单 ──────────────────

    private record ArchiveFile(String ownerKey, String title, String objectName) {}

    private static class ArchiveBundle {
        List<Student> students = new ArrayList<>();
        List<Map.Entry<String, String>> photos = new ArrayList<>();     // studentId → objectName
        List<ArchiveFile> honors = new ArrayList<>();
        List<ArchiveFile> moments = new ArrayList<>();
        List<ArchiveFile> leaves = new ArrayList<>();
        List<ArchiveFile> reports = new ArrayList<>();
        int totalFiles() {
            return photos.size() + honors.size() + moments.size() + leaves.size() + reports.size();
        }
    }

    private ArchiveBundle collect(Long classId) {
        ArchiveBundle b = new ArchiveBundle();
        b.students = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId).orderByAsc(Student::getStudentNo));
        if (b.students.isEmpty()) {
            return b;
        }
        List<Long> ids = b.students.stream().map(Student::getId).toList();
        DateTimeFormatter day = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        for (Student s : b.students) {
            if (s.getPhotoUrl() != null && !s.getPhotoUrl().isBlank()) {
                b.photos.add(Map.entry(s.getId().toString(), s.getPhotoUrl()));
            }
        }
        for (Honor h : honorMapper.selectList(new LambdaQueryWrapper<Honor>()
                .in(Honor::getStudentId, ids))) {
            if (h.getFileUrl() != null && !h.getFileUrl().isBlank()) {
                b.honors.add(new ArchiveFile(h.getStudentId().toString(),
                        h.getName() + "-" + (h.getHonorDate() == null ? "" : h.getHonorDate()), h.getFileUrl()));
            }
        }
        for (Moment m : momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                .eq(Moment::getClassId, classId))) {
            if (m.getPhotoUrl() != null && !m.getPhotoUrl().isBlank()) {
                String title = (m.getCreateTime() == null ? "" : m.getCreateTime().format(day))
                        + "-" + m.getSceneTag();
                // 批62 多图：photo_url 逗号分隔，逐张入包（序号后缀）
                String[] objs = m.getPhotoUrl().split(",");
                for (int i = 0; i < objs.length; i++) {
                    b.moments.add(new ArchiveFile(null,
                            title + (objs.length > 1 ? "-" + (i + 1) : ""), objs[i]));
                }
            }
        }
        for (StudentLeave l : leaveMapper.selectList(new LambdaQueryWrapper<StudentLeave>()
                .in(StudentLeave::getStudentId, ids))) {
            if (l.getPhotos() != null && !l.getPhotos().isBlank() && !"[]".equals(l.getPhotos())) {
                String title = l.getLeaveType() + "-" + l.getStartTime().toLocalDate();
                for (String obj : parsePhotos(l.getPhotos())) {
                    b.leaves.add(new ArchiveFile(l.getStudentId().toString(), title, obj));
                }
            }
        }
        // 批42 分期归档：每生每格（学期×类型×期次）只打包最新一份（重生成的旧版留库不进归档，防 zip 膨胀+重名）
        java.util.Map<String, Report> latestReport = new java.util.LinkedHashMap<>();
        for (Report r : reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .in(Report::getStudentId, ids)
                .orderByDesc(Report::getGenTime).orderByDesc(Report::getId))) {
            latestReport.putIfAbsent(r.getStudentId() + ":" + r.getTermId() + ":"
                    + (r.getScopeType() == null ? "TERM" : r.getScopeType())
                    + ":" + ("MID".equals(r.getPeriod()) ? "MID" : "FINAL"), r);
        }
        for (Report r : latestReport.values()) {
            String label = (r.getScopeType() == null ? "TERM" : r.getScopeType()) + "-T" + r.getTermId()
                    + ("MID".equals(r.getPeriod()) ? "-期中" : "") + "-";
            if (r.getFileUrl() != null && !r.getFileUrl().isBlank()) {
                b.reports.add(new ArchiveFile(r.getStudentId().toString(),
                        label + "教师版", r.getFileUrl()));
            }
            if (r.getParentFileUrl() != null && !r.getParentFileUrl().isBlank()) {
                b.reports.add(new ArchiveFile(r.getStudentId().toString(),
                        label + "家长版", r.getParentFileUrl()));
            }
        }
        return b;
    }

    /** photos JSON 数组 → objectName 列表（存量格式为 JSON 数组字符串；坏格式整体跳过） */
    private List<String> parsePhotos(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<String> out = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> {
                    String v = n.asText("");
                    if (!v.isBlank()) {
                        out.add(v);
                    }
                });
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void writeTo(java.util.zip.ZipOutputStream zip, String entry, String objectName,
                         List<String> skipped) {
        try (InputStream in = pdfStore.download(objectName)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            in.transferTo(zip);
            zip.closeEntry();
        } catch (Exception e) {
            skipped.add(entry + " ← " + objectName);
        }
    }

    private static String ext(String objectName) {
        int i = objectName.lastIndexOf('.');
        return i < 0 ? "bin" : objectName.substring(i + 1).toLowerCase();
    }

    /** zip 条目名去 Windows 非法字符 */
    private static String safe(String name) {
        return (name == null || name.isBlank() ? "未命名" : name)
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
