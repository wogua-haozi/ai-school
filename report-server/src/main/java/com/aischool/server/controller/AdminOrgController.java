package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.ActivitySignup;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Comment;
import com.aischool.server.entity.Comprehensive;
import com.aischool.server.entity.Evaluation;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.GradeBinding;
import com.aischool.server.entity.Honor;
import com.aischool.server.entity.Score;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ActivitySignupMapper;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.CommentMapper;
import com.aischool.server.mapper.ComprehensiveMapper;
import com.aischool.server.mapper.EvaluationMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.GradeBindingMapper;
import com.aischool.server.mapper.HonorMapper;
import com.aischool.server.mapper.ScoreMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.excel.ExcelStudentHelper;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端：年级 / 班级（含班主任绑定）/ 学生档案（全部仅管理员） */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrgController {

    private final GradeMapper gradeMapper;
    private final GradeBindingMapper gradeBindingMapper;
    private final ClazzMapper clazzMapper;
    private final PermissionService permissionService;
    private final StudentMapper studentMapper;
    private final UserMapper userMapper;
    private final EvaluationMapper evaluationMapper;
    private final ScoreMapper scoreMapper;
    private final ActivitySignupMapper signupMapper;
    private final ComprehensiveMapper comprehensiveMapper;
    private final CommentMapper commentMapper;
    private final HonorMapper honorMapper;
    private final PdfStoreService pdfStore;
    private final ExcelStudentHelper excelStudent;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作系统管理");
    }

    // ───────────────── 年级 ─────────────────

    @GetMapping("/grade")
    public ApiResponse<List<Grade>> gradeList() {
        checkAdmin();
        return ApiResponse.ok(gradeMapper.selectList(
                new LambdaQueryWrapper<Grade>().orderByAsc(Grade::getId)));
    }

    @Data
    public static class GradeReq {
        @NotBlank(message = "name 不能为空")
        private String name;
        private String schoolYear;
    }

    @PostMapping("/grade")
    public ApiResponse<Map<String, Object>> createGrade(@Validated @RequestBody GradeReq req) {
        checkAdmin();
        Grade g = new Grade();
        g.setName(req.getName());
        g.setSchoolYear(req.getSchoolYear());
        gradeMapper.insert(g);
        return ApiResponse.ok(Map.of("gradeId", g.getId()));
    }

    @PutMapping("/grade/{id}")
    public ApiResponse<Void> updateGrade(@PathVariable Long id, @Validated @RequestBody GradeReq req) {
        checkAdmin();
        if (gradeMapper.selectById(id) == null) {
            throw new BizException(404, "年级不存在");
        }
        gradeMapper.update(null, new LambdaUpdateWrapper<Grade>()
                .eq(Grade::getId, id)
                .set(Grade::getName, req.getName())
                .set(Grade::getSchoolYear, req.getSchoolYear()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/grade/{id}")
    public ApiResponse<Void> deleteGrade(@PathVariable Long id) {
        checkAdmin();
        if (gradeMapper.selectById(id) == null) {
            throw new BizException(404, "年级不存在");
        }
        if (clazzMapper.selectCount(new LambdaQueryWrapper<Clazz>().eq(Clazz::getGradeId, id)) > 0) {
            throw new BizException(400, "该年级下仍有班级，不可删除");
        }
        gradeMapper.deleteById(id);
        gradeBindingMapper.delete(new LambdaQueryWrapper<GradeBinding>().eq(GradeBinding::getGradeId, id));
        return ApiResponse.ok();
    }

    // ───────────────── 年级岗位绑定（批32 级长 / 批51 级部主任，请假审批同权） ─────────────────

    /** 岗位绑定列表（duty=GRADE_LEADER 级长 / GRADE_DIRECTOR 级部主任；含姓名/年级名，Org 页签表格回显） */
    @GetMapping("/grade-binding")
    public ApiResponse<List<Map<String, Object>>> gradeBindingList(
            @RequestParam(defaultValue = GradeBinding.DUTY_GRADE_LEADER) String duty) {
        checkAdmin();
        List<GradeBinding> rows = gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                .eq(GradeBinding::getDuty, duty)
                .orderByAsc(GradeBinding::getGradeId));
        if (rows.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Map<Long, String> gradeNames = gradeMapper.selectBatchIds(rows.stream()
                        .map(GradeBinding::getGradeId).distinct().toList()).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
        Map<Long, String> userNames = userMapper.selectBatchIds(rows.stream()
                        .map(GradeBinding::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return ApiResponse.ok(rows.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("gradeId", b.getGradeId());
            m.put("gradeName", gradeNames.getOrDefault(b.getGradeId(), ""));
            m.put("userId", b.getUserId());
            m.put("userName", userNames.getOrDefault(b.getUserId(), ""));
            m.put("duty", b.getDuty());
            return m;
        }).toList());
    }

    /** 绑定岗位（批51：级长=GRADE_LEADER 角色 / 级部主任=GRADE_DIRECTOR 岗位须主任角色账号；同人同年级同岗位幂等） */
    @PostMapping("/grade-binding")
    public ApiResponse<Map<String, Object>> addGradeBinding(@Validated @RequestBody BindingReq req) {
        checkAdmin();
        boolean directorDuty = GradeBinding.DUTY_GRADE_DIRECTOR.equals(req.getDuty());
        String duty = directorDuty ? GradeBinding.DUTY_GRADE_DIRECTOR : GradeBinding.DUTY_GRADE_LEADER;
        if (gradeMapper.selectById(req.getGradeId()) == null) {
            throw new BizException(404, "年级不存在");
        }
        User u = userMapper.selectById(req.getUserId());
        if (u == null || !("GRADE_LEADER".equals(u.getRole()) || (directorDuty && "DIRECTOR".equals(u.getRole())))) {
            throw new BizException(directorDuty
                    ? "级部主任岗位须选「学成中心主任」角色的账号（先在账号管理把该教师角色设为学成中心主任）"
                    : "请选择级长角色的账号（先在账号管理把该教师角色设为级长）");
        }
        GradeBinding exists = gradeBindingMapper.selectOne(new LambdaQueryWrapper<GradeBinding>()
                .eq(GradeBinding::getUserId, req.getUserId())
                .eq(GradeBinding::getGradeId, req.getGradeId())
                .eq(GradeBinding::getDuty, duty));
        if (exists == null) {
            GradeBinding b = new GradeBinding();
            b.setUserId(req.getUserId());
            b.setGradeId(req.getGradeId());
            b.setDuty(duty);
            gradeBindingMapper.insert(b);
            return ApiResponse.ok(Map.of("bindingId", b.getId()));
        }
        return ApiResponse.ok(Map.of("bindingId", exists.getId()));
    }

    @Data
    public static class BindingReq {
        @NotNull(message = "userId 不能为空")
        private Long userId;
        @NotNull(message = "gradeId 不能为空")
        private Long gradeId;
        private String duty;
    }

    @DeleteMapping("/grade-binding/{id}")
    public ApiResponse<Void> removeGradeBinding(@PathVariable Long id) {
        checkAdmin();
        gradeBindingMapper.deleteById(id);
        return ApiResponse.ok();
    }

    // ───────────────── 班级 ─────────────────

    /** 班级列表（附年级名/班主任名，Java 内存 join） */
    @GetMapping("/class/list")
    public ApiResponse<List<Map<String, Object>>> classList() {
        checkAdmin();
        List<Clazz> classes = clazzMapper.selectList(
                new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId));
        Map<Long, String> gradeNames = classes.isEmpty() ? Map.of()
                : gradeMapper.selectBatchIds(classes.stream().map(Clazz::getGradeId).distinct().toList())
                        .stream().collect(Collectors.toMap(Grade::getId, Grade::getName));
        List<Long> htIds = classes.stream().map(Clazz::getHeadTeacherId).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> teacherNames = htIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(htIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName));
        return ApiResponse.ok(classes.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("gradeId", c.getGradeId());
            m.put("gradeName", gradeNames.get(c.getGradeId()));
            m.put("name", c.getName());
            m.put("headTeacherId", c.getHeadTeacherId());
            m.put("headTeacherName", teacherNames.get(c.getHeadTeacherId()));
            return m;
        }).toList());
    }

    @Data
    public static class ClassReq {
        @NotNull(message = "gradeId 不能为空")
        private Long gradeId;
        @NotBlank(message = "name 不能为空")
        private String name;
        private Long headTeacherId;
    }

    @PostMapping("/class")
    public ApiResponse<Map<String, Object>> createClass(@Validated @RequestBody ClassReq req) {
        checkAdmin();
        if (gradeMapper.selectById(req.getGradeId()) == null) {
            throw new BizException(404, "年级不存在");
        }
        Clazz c = new Clazz();
        c.setGradeId(req.getGradeId());
        c.setName(req.getName());
        c.setHeadTeacherId(req.getHeadTeacherId());
        clazzMapper.insert(c);
        return ApiResponse.ok(Map.of("classId", c.getId()));
    }

    @PutMapping("/class/{id}")
    public ApiResponse<Void> updateClass(@PathVariable Long id, @Validated @RequestBody ClassReq req) {
        checkAdmin();
        if (clazzMapper.selectById(id) == null) {
            throw new BizException(404, "班级不存在");
        }
        if (gradeMapper.selectById(req.getGradeId()) == null) {
            throw new BizException(404, "年级不存在");
        }
        clazzMapper.update(null, new LambdaUpdateWrapper<Clazz>()
                .eq(Clazz::getId, id)
                .set(Clazz::getGradeId, req.getGradeId())
                .set(Clazz::getName, req.getName())
                .set(Clazz::getHeadTeacherId, req.getHeadTeacherId()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/class/{id}")
    public ApiResponse<Void> deleteClass(@PathVariable Long id) {
        checkAdmin();
        if (clazzMapper.selectById(id) == null) {
            throw new BizException(404, "班级不存在");
        }
        if (studentMapper.selectCount(new LambdaQueryWrapper<Student>().eq(Student::getClassId, id)) > 0) {
            throw new BizException(400, "该班级下仍有学生，不可删除");
        }
        clazzMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /** 整班调班（升年级/分班）：全班在读学生移入目标班；历史数据按学生+学期关联，不受影响 */
    @PutMapping("/class/{id}/move-students")
    public ApiResponse<Map<String, Object>> moveStudents(@PathVariable Long id,
                                                         @RequestBody Map<String, Long> req) {
        checkAdmin();
        Long target = req.get("targetClassId");
        if (target == null || target.equals(id) || clazzMapper.selectById(target) == null) {
            throw new BizException(400, "目标班级无效");
        }
        if (clazzMapper.selectById(id) == null) {
            throw new BizException(404, "班级不存在");
        }
        int moved = studentMapper.update(null, new LambdaUpdateWrapper<Student>()
                .eq(Student::getClassId, id)
                .eq(Student::getStatus, "在读")
                .set(Student::getClassId, target));
        return ApiResponse.ok(Map.of("moved", moved));
    }

    /** 全班标记状态（毕业/转出）：换届时整届学生一键退场，档案与报告保留可查 */
    @PutMapping("/class/{id}/mark-status")
    public ApiResponse<Map<String, Object>> markClassStatus(@PathVariable Long id,
                                                            @RequestBody Map<String, String> req) {
        checkAdmin();
        String status = req.get("status");
        if (!"毕业".equals(status) && !"转出".equals(status)) {
            throw new BizException(400, "status 仅支持 毕业/转出");
        }
        if (clazzMapper.selectById(id) == null) {
            throw new BizException(404, "班级不存在");
        }
        int updated = studentMapper.update(null, new LambdaUpdateWrapper<Student>()
                .eq(Student::getClassId, id)
                .eq(Student::getStatus, "在读")
                .set(Student::getStatus, status));
        return ApiResponse.ok(Map.of("updated", updated));
    }

    // ───────────────── 学生 ─────────────────

    /** 学生档案列表（分页，含家长信息；status 可筛选在读/转出/毕业，不传=全部） */
    @GetMapping("/student/list")
    public ApiResponse<Map<String, Object>> studentList(@RequestParam(required = false) Long classId,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        checkAdmin();
        var p = studentMapper.selectPage(Page.of(page, Math.min(size, 100)), new LambdaQueryWrapper<Student>()
                .eq(classId != null, Student::getClassId, classId)
                .eq(status != null && !status.isBlank(), Student::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(Student::getName, keyword).or().like(Student::getStudentNo, keyword))
                .orderByAsc(Student::getId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("records", p.getRecords());
        return ApiResponse.ok(data);
    }

    @Data
    public static class StudentReq {
        @NotBlank(message = "studentNo 不能为空")
        private String studentNo;
        @NotBlank(message = "name 不能为空")
        private String name;
        private String gender;
        @NotNull(message = "classId 不能为空")
        private Long classId;
        private java.time.LocalDate enrollDate;
        private String status;
        private String photoUrl;
        private String guardianName;
        private String guardianPhone;
        private String dormBuilding;
        private String dormRoom;
        private String dormBed;
    }

    @PostMapping("/student")
    public ApiResponse<Map<String, Object>> createStudent(@Validated @RequestBody StudentReq req) {
        checkAdmin();
        if (studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, req.getStudentNo())) > 0) {
            throw new BizException(400, "学号已存在");
        }
        if (clazzMapper.selectById(req.getClassId()) == null) {
            throw new BizException(404, "班级不存在");
        }
        Student s = new Student();
        copy(req, s);
        studentMapper.insert(s);
        return ApiResponse.ok(Map.of("studentId", s.getId()));
    }

    @PutMapping("/student/{id}")
    public ApiResponse<Void> updateStudent(@PathVariable Long id, @Validated @RequestBody StudentReq req) {
        checkAdmin();
        Student s = studentMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "学生不存在");
        }
        if (studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, req.getStudentNo()).ne(Student::getId, id)) > 0) {
            throw new BizException(400, "学号已存在");
        }
        if (clazzMapper.selectById(req.getClassId()) == null) {
            throw new BizException(404, "班级不存在");
        }
        copy(req, s);
        studentMapper.updateById(s);
        return ApiResponse.ok();
    }

    @DeleteMapping("/student/{id}")
    public ApiResponse<Void> deleteStudent(@PathVariable Long id) {
        checkAdmin();
        Student s = studentMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "学生不存在");
        }
        String ref = firstReference(id);
        if (ref != null) {
            throw new BizException(400, "该学生已有成长数据（" + ref + "），不可删除");
        }
        if (s.getPhotoUrl() != null && !s.getPhotoUrl().isBlank()) {
            pdfStore.delete(s.getPhotoUrl());
        }
        studentMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /** 新生 Excel 批量导入（管理员，multipart）：逐行校验，合法行入库，问题行返回明细（部分成功） */
    @PostMapping("/student/import")
    public ApiResponse<Map<String, Object>> importStudents(@RequestParam("file") MultipartFile file) {
        checkAdmin();
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择 Excel 文件");
        }
        List<ExcelStudentHelper.StudentRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = excelStudent.read(in);
        } catch (IOException e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (rows.isEmpty()) {
            throw new BizException(400, "Excel 里没有数据行（首行为表头，请从第 2 行开始填写）");
        }
        Map<String, Long> classIds = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getName, Clazz::getId, (a, b) -> a));
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> errors = new java.util.ArrayList<>();
        int inserted = 0;
        for (ExcelStudentHelper.StudentRow r : rows) {
            String reason = null;
            if (r.studentNo().isBlank()) {
                reason = "学号为空";
            } else if (seen.contains(r.studentNo())) {
                reason = "学号在文件内重复";
            } else if (studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                    .eq(Student::getStudentNo, r.studentNo())) > 0) {
                reason = "学号已存在";
            } else if (r.name().isBlank()) {
                reason = "姓名为空";
            } else if (!classIds.containsKey(r.className())) {
                reason = "班级不存在: " + (r.className().isBlank() ? "(空)" : r.className());
            } else if (!r.gender().isBlank() && !"男".equals(r.gender()) && !"女".equals(r.gender())) {
                reason = "性别只能填 男/女: " + r.gender();
            }
            if (reason != null) {
                errors.add(Map.of("row", r.rowNum(), "reason", reason));
                continue;
            }
            seen.add(r.studentNo());
            Student s = new Student();
            s.setStudentNo(r.studentNo());
            s.setName(r.name());
            s.setGender("男".equals(r.gender()) ? "M" : "女".equals(r.gender()) ? "F" : null);
            s.setClassId(classIds.get(r.className()));
            s.setStatus("在读");
            s.setGuardianName(r.guardianName().isBlank() ? null : r.guardianName());
            s.setGuardianPhone(r.guardianPhone().isBlank() ? null : r.guardianPhone());
            s.setDormBuilding(r.dormBuilding().isBlank() ? null : r.dormBuilding());
            s.setDormRoom(r.dormRoom().isBlank() ? null : r.dormRoom());
            s.setDormBed(r.dormBed().isBlank() ? null : r.dormBed());
            studentMapper.insert(s);
            inserted++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        data.put("failed", errors.size());
        data.put("errors", errors);
        return ApiResponse.ok(data);
    }

    /** 新生导入模板下载（仅表头，.xlsx） */
    @GetMapping("/student/import-template")
    public ResponseEntity<byte[]> studentImportTemplate() {
        checkAdmin();
        byte[] bytes = excelStudent.template();
        String filename = URLEncoder.encode("新生导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    /** 上传学生照片（管理员，multipart，jpg/jpeg/png ≤5MB），重复上传覆盖旧对象（功能点 §2） */
    @PostMapping("/student/{id}/photo")
    public ApiResponse<Map<String, Object>> uploadPhoto(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file) {
        checkAdmin();
        Student s = studentMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "学生不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择照片图片");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(400, "照片不能超过 5MB");
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
        if (s.getPhotoUrl() != null && !s.getPhotoUrl().isBlank()) {
            pdfStore.delete(s.getPhotoUrl());
        }
        String objectName = "student/" + id + "/" + UUID.randomUUID() + "." + ext;
        pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length,
                file.getContentType() == null ? "image/jpeg" : file.getContentType());
        studentMapper.update(null, new LambdaUpdateWrapper<Student>()
                .eq(Student::getId, id).set(Student::getPhotoUrl, objectName));
        return ApiResponse.ok(Map.of("photoUrl", objectName));
    }

    /** 学生照片（管理员，档案缩略图用） */
    @GetMapping("/student/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) throws IOException {
        checkAdmin();
        Student s = studentMapper.selectById(id);
        if (s == null || s.getPhotoUrl() == null || s.getPhotoUrl().isBlank()) {
            throw new BizException(404, "照片未上传");
        }
        byte[] bytes;
        try (InputStream in = pdfStore.download(s.getPhotoUrl())) {
            bytes = in.readAllBytes();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                s.getPhotoUrl().endsWith(".png") ? "image/png" : "image/jpeg"));
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private void copy(StudentReq req, Student s) {
        s.setStudentNo(req.getStudentNo());
        s.setName(req.getName());
        s.setGender(req.getGender());
        s.setClassId(req.getClassId());
        s.setEnrollDate(req.getEnrollDate());
        s.setStatus(req.getStatus() == null || req.getStatus().isBlank() ? "在读" : req.getStatus());
        s.setPhotoUrl(req.getPhotoUrl());
        s.setGuardianName(req.getGuardianName());
        s.setGuardianPhone(req.getGuardianPhone());
        s.setDormBuilding(req.getDormBuilding());
        s.setDormRoom(req.getDormRoom());
        s.setDormBed(req.getDormBed());
    }

    /** 学生删除守卫：任一成长数据表有记录即拒删 */
    private String firstReference(Long studentId) {
        if (evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, studentId)) > 0) {
            return "评价";
        }
        if (scoreMapper.selectCount(new LambdaQueryWrapper<Score>()
                .eq(Score::getStudentId, studentId)) > 0) {
            return "成绩";
        }
        if (signupMapper.selectCount(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getStudentId, studentId)) > 0) {
            return "活动";
        }
        if (comprehensiveMapper.selectCount(new LambdaQueryWrapper<Comprehensive>()
                .eq(Comprehensive::getStudentId, studentId)) > 0) {
            return "综评";
        }
        if (commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getStudentId, studentId)) > 0) {
            return "寄语";
        }
        if (honorMapper.selectCount(new LambdaQueryWrapper<Honor>()
                .eq(Honor::getStudentId, studentId)) > 0) {
            return "荣誉";
        }
        return null;
    }
}
