package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.excel.ExcelScoreHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端：家长账号管理（t_user role=PARENT + t_parent_binding 绑定）。
 * 主路径=按班批量生成（学生表 guardianPhone 即账号名）；单个建/绑定管理/批量启停重置删/导出。
 */
@RestController
@RequestMapping("/api/admin/parent")
@RequiredArgsConstructor
public class AdminParentController {

    private final UserMapper userMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final ParentBindingMapper bindingMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final ExcelScoreHelper excel;

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作系统管理");
    }

    // ────────────────────────── 列表 / 导出 ──────────────────────────

    /** 家长账号分页列表（keyword 匹配账号/姓名/手机；附绑定孩子摘要） */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        checkAdmin();
        var p = userMapper.selectPage(Page.of(page, Math.min(size, 100)), new LambdaQueryWrapper<User>()
                .eq(User::getRole, "PARENT")
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(User::getUsername, keyword)
                                .or().like(User::getRealName, keyword)
                                .or().like(User::getPhone, keyword))
                .orderByDesc(User::getId));
        List<Map<String, Object>> records = p.getRecords().stream()
                .map(u -> row(u, childrenSummary(u.getId()))).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("records", records);
        return ApiResponse.ok(data);
    }

    /** 导出家长账号 Excel（当前筛选全量，最多 5000 行） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword) {
        checkAdmin();
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "PARENT")
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(User::getUsername, keyword)
                                .or().like(User::getRealName, keyword)
                                .or().like(User::getPhone, keyword))
                .orderByDesc(User::getId)
                .last("LIMIT 5000"));
        Map<Long, List<ParentBinding>> byParent = bindingMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(ParentBinding::getParentUserId));
        Map<Long, Student> students = studentsOf(byParent);
        List<Object[]> rows = users.stream().map(u -> new Object[]{
                u.getUsername(), u.getRealName(), u.getPhone() == null ? "" : u.getPhone(),
                u.getStatus() != null && u.getStatus() == 1 ? "启用" : "停用",
                childrenText(byParent.get(u.getId()), students),
                u.getCreateTime() == null ? "" : u.getCreateTime().toLocalDate().toString()
        }).collect(Collectors.toList());
        byte[] bytes = excel.export("家长账号",
                new String[]{"账号", "姓名", "手机", "状态", "绑定孩子", "创建时间"}, rows);
        String filename = URLEncoder.encode("家长账号.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    // ────────────────────────── 单个建 / 按班批量生成 ──────────────────────────

    /** 单个建家长账号（username 通常= guardian 手机号；可选顺带绑定学生） */
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@Validated @RequestBody ParentCreateReq req) {
        checkAdmin();
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException(400, "用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        u.setRole("PARENT");
        u.setPhone(req.getPhone());
        u.setStatus(1);
        u.setMustChangePwd(1); // 管理员发初始密码：首登强制改密（同教师机制）
        userMapper.insert(u);
        int bound = 0;
        if (req.getStudentIds() != null) {
            for (Long sid : req.getStudentIds()) {
                bound += bind(u.getId(), sid, "家长") ? 1 : 0;
            }
        }
        return ApiResponse.ok(Map.of("userId", u.getId(), "bound", bound));
    }

    /**
     * 按班批量生成：遍历班内在读学生的 guardianPhone 建号+绑定。
     * 同手机号多名学生=一号绑多孩；账号已存在（PARENT）则只补绑定；无 guardianPhone 跳过并报告。
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@Validated @RequestBody GenerateReq req) {
        checkAdmin();
        if (clazzMapper.selectById(req.getClassId()) == null) {
            throw new BizException(404, "班级不存在");
        }
        List<Student> students = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, req.getClassId())
                .and(q -> q.isNull(Student::getStatus).or().notIn(Student::getStatus, "毕业", "转出"))
                .orderByAsc(Student::getStudentNo));
        // 已有账号按手机号索引（空列表须转哨兵：.in(空集合) 生成 IN () 非法 SQL——同 MetaController 教训）
        List<String> phones = students.stream().map(Student::getGuardianPhone)
                .filter(ph -> ph != null && !ph.isBlank()).map(String::trim).distinct().toList();
        Map<String, User> byPhone = phones.isEmpty() ? Map.of()
                : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getUsername, phones))
                        .stream().collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));
        int created = 0;
        int bound = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Student s : students) {
            if (s.getGuardianPhone() == null || s.getGuardianPhone().isBlank()) {
                errors.add(Map.of("student", s.getName(), "reason", "未填监护人手机号"));
                continue;
            }
            String username = s.getGuardianPhone().trim();
            User u = byPhone.get(username);
            if (u == null) {
                u = new User();
                u.setUsername(username);
                u.setPasswordHash(passwordEncoder.encode(AdminUserController.INITIAL_PASSWORD));
                u.setRealName(s.getGuardianName() == null || s.getGuardianName().isBlank()
                        ? s.getName() + "家长" : s.getGuardianName());
                u.setRole("PARENT");
                u.setPhone(username);
                u.setStatus(1);
                u.setMustChangePwd(1);
                userMapper.insert(u);
                byPhone.put(username, u);
                created++;
            } else if (!"PARENT".equals(u.getRole())) {
                errors.add(Map.of("student", s.getName(), "reason", "手机号已被非家长账号占用: " + username));
                continue;
            }
            bound += bind(u.getId(), s.getId(), "家长") ? 1 : 0;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("bound", bound);
        data.put("skipped", errors.size());
        data.put("errors", errors);
        data.put("initialPassword", AdminUserController.INITIAL_PASSWORD);
        return ApiResponse.ok(data);
    }

    // ────────────────────────── 绑定管理 ──────────────────────────

    /** 某家长的绑定明细 */
    @GetMapping("/{id}/bindings")
    public ApiResponse<List<Map<String, Object>>> bindings(@PathVariable Long id) {
        checkAdmin();
        findParent(id);
        List<ParentBinding> rows = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, id).orderByAsc(ParentBinding::getId));
        Map<Long, Student> students = studentsOf(Map.of(id, rows));
        return ApiResponse.ok(rows.stream().map(b -> {
            Student s = students.get(b.getStudentId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", b.getStudentId());
            m.put("relation", b.getRelation());
            m.put("studentName", s == null ? "(已删除)" : s.getName());
            m.put("studentNo", s == null ? null : s.getStudentNo());
            m.put("className", s == null ? null : classNameOf(s.getClassId()));
            return m;
        }).toList());
    }

    /** 加绑定（同一家长同一学生幂等拒绝） */
    @PostMapping("/{id}/binding")
    public ApiResponse<Void> addBinding(@PathVariable Long id, @Validated @RequestBody BindingReq req) {
        checkAdmin();
        findParent(id);
        if (!bind(id, req.getStudentId(), req.getRelation() == null || req.getRelation().isBlank()
                ? "家长" : req.getRelation().trim())) {
            throw new BizException(400, "该绑定已存在");
        }
        return ApiResponse.ok();
    }

    /** 解绑（保留账号） */
    @DeleteMapping("/{id}/binding/{studentId}")
    public ApiResponse<Void> removeBinding(@PathVariable Long id, @PathVariable Long studentId) {
        checkAdmin();
        findParent(id);
        bindingMapper.delete(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, id)
                .eq(ParentBinding::getStudentId, studentId));
        return ApiResponse.ok();
    }

    // ────────────────────────── 账号操作（单个+批量） ──────────────────────────

    /** 重置密码（重置后首登强制改密） */
    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Validated @RequestBody PasswordReq req) {
        checkAdmin();
        findParent(id);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getPasswordHash, passwordEncoder.encode(req.getPassword()))
                .set(User::getMustChangePwd, 1));
        return ApiResponse.ok();
    }

    /** 停用/启用 */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @Validated @RequestBody StatusReq req) {
        checkAdmin();
        findParent(id);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id).set(User::getStatus, req.getStatus()));
        return ApiResponse.ok();
    }

    /** 编辑基础信息（批69）：改手机号时若账号=原手机号则账号同步改——按班生成按 username=手机号 去重，须保持一致 */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> edit(@PathVariable Long id, @RequestBody EditReq req) {
        checkAdmin();
        User u = findParent(id);
        String newPhone = req.getPhone() != null && !req.getPhone().isBlank()
                ? req.getPhone().trim() : u.getPhone();
        String newName = req.getRealName() == null || req.getRealName().isBlank()
                ? u.getRealName() : req.getRealName().trim();
        boolean phoneChanged = newPhone == null ? u.getPhone() != null : !newPhone.equals(u.getPhone());
        if (phoneChanged) {
            if (!newPhone.matches("^1\\d{10}$")) {
                throw new BizException(400, "手机号格式不正确");
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .ne(User::getId, id)
                    .and(q -> q.eq(User::getPhone, newPhone).or().eq(User::getUsername, newPhone))) > 0) {
                throw new BizException(400, "该手机号已被使用");
            }
        }
        boolean syncUsername = phoneChanged && u.getPhone() != null && u.getPhone().equals(u.getUsername());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getRealName, newName)
                .set(User::getPhone, newPhone)
                .set(syncUsername, User::getUsername, newPhone));
        return ApiResponse.ok(Map.of("usernameSynced", syncUsername));
    }

    /** 删除账号（连带清绑定；家长无业务数据引用，可直接删） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        checkAdmin();
        findParent(id);
        userMapper.deleteById(id);
        bindingMapper.delete(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, id));
        return ApiResponse.ok();
    }

    /** 批量启停 */
    @PutMapping("/batch/status")
    public ApiResponse<Void> batchStatus(@Validated @RequestBody BatchStatusReq req) {
        checkAdmin();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .in(User::getId, req.getIds()).eq(User::getRole, "PARENT")
                .set(User::getStatus, req.getStatus()));
        return ApiResponse.ok();
    }

    /** 批量重置为统一初始密码 */
    @PutMapping("/batch/reset-password")
    public ApiResponse<Map<String, Object>> batchResetPassword(@Validated @RequestBody BatchIdsReq req) {
        checkAdmin();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .in(User::getId, req.getIds()).eq(User::getRole, "PARENT")
                .set(User::getPasswordHash, passwordEncoder.encode(AdminUserController.INITIAL_PASSWORD))
                .set(User::getMustChangePwd, 1));
        return ApiResponse.ok(Map.of("initialPassword", AdminUserController.INITIAL_PASSWORD));
    }

    /** 批量删除（连带清绑定） */
    @DeleteMapping("/batch")
    public ApiResponse<Void> batchDelete(@Validated @RequestBody BatchIdsReq req) {
        checkAdmin();
        List<User> parents = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, req.getIds()).eq(User::getRole, "PARENT"));
        for (User u : parents) {
            userMapper.deleteById(u.getId());
            bindingMapper.delete(new LambdaQueryWrapper<ParentBinding>()
                    .eq(ParentBinding::getParentUserId, u.getId()));
        }
        return ApiResponse.ok();
    }

    // ────────────────────────── helpers ──────────────────────────

    /** upsert 一条绑定；已存在返回 false */
    private boolean bind(Long parentUserId, Long studentId, String relation) {
        if (studentMapper.selectById(studentId) == null) {
            throw new BizException(404, "学生不存在: " + studentId);
        }
        if (bindingMapper.selectCount(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, parentUserId)
                .eq(ParentBinding::getStudentId, studentId)) > 0) {
            return false;
        }
        ParentBinding b = new ParentBinding();
        b.setParentUserId(parentUserId);
        b.setStudentId(studentId);
        b.setRelation(relation);
        bindingMapper.insert(b);
        return true;
    }

    private User findParent(Long id) {
        User u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "账号不存在");
        }
        if (!"PARENT".equals(u.getRole())) {
            throw new BizException(400, "非家长账号");
        }
        return u;
    }

    private Map<String, Object> row(User u, String children) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("realName", u.getRealName());
        m.put("phone", u.getPhone());
        m.put("status", u.getStatus());
        m.put("children", children);
        m.put("createTime", u.getCreateTime() == null ? null : u.getCreateTime().toString());
        return m;
    }

    /** 家长绑定孩子摘要「张三(三1班)、李四(三2班)」 */
    private String childrenSummary(Long parentUserId) {
        List<ParentBinding> rows = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, parentUserId));
        return childrenText(rows, studentsOf(Map.of(parentUserId, rows)));
    }

    private String childrenText(List<ParentBinding> rows, Map<Long, Student> students) {
        if (rows == null || rows.isEmpty()) {
            return "（未绑定）";
        }
        return rows.stream().map(b -> {
            Student s = students.get(b.getStudentId());
            if (s == null) {
                return "(已删除)";
            }
            String cls = classNameOf(s.getClassId());
            return cls == null ? s.getName() : s.getName() + "(" + cls + ")";
        }).collect(Collectors.joining("、"));
    }

    private Map<Long, Student> studentsOf(Map<Long, List<ParentBinding>> byParent) {
        List<Long> ids = byParent.values().stream().flatMap(List::stream)
                .map(ParentBinding::getStudentId).distinct().toList();
        return ids.isEmpty() ? Map.of() : studentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Student::getId, s -> s));
    }

    private String classNameOf(Long classId) {
        if (classId == null) {
            return null;
        }
        Clazz c = clazzMapper.selectById(classId);
        return c == null ? null : c.getName();
    }

    // ────────────────────────── req ──────────────────────────

    @Data
    public static class ParentCreateReq {
        @NotBlank(message = "username 不能为空")
        private String username;
        @NotBlank(message = "password 不能为空")
        @Size(min = 8, message = "password 至少 8 位")
        private String password;
        @NotBlank(message = "realName 不能为空")
        private String realName;
        private String phone;
        private List<Long> studentIds;
    }

    @Data
    public static class GenerateReq {
        @NotNull(message = "classId 不能为空")
        private Long classId;
    }

    @Data
    public static class EditReq {
        private String realName;
        private String phone;
    }

    @Data
    public static class BindingReq {
        @NotNull(message = "studentId 不能为空")
        private Long studentId;
        private String relation;
    }

    @Data
    public static class PasswordReq {
        @NotBlank(message = "password 不能为空")
        @Size(min = 8, message = "password 至少 8 位")
        private String password;
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
