package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Subject;
import com.aischool.server.entity.Teach;
import com.aischool.server.entity.TeacherProfile;
import com.aischool.server.entity.User;
import com.aischool.server.entity.UserPermission;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.SubjectMapper;
import com.aischool.server.mapper.TeachMapper;
import com.aischool.server.mapper.TeacherProfileMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.mapper.UserPermissionMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.PermissionService;
import com.aischool.server.service.auth.RoleApprovalService;
import com.aischool.server.entity.RoleRequest;
import com.aischool.server.service.excel.ExcelTeacherHelper;
import com.aischool.server.service.excel.ExcelTeachHelper;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端：教师账号（增删改/重置密码/停用/批量导入）与任课关系（全部仅管理员） */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserMapper userMapper;
    private final TeachMapper teachMapper;
    private final ClazzMapper clazzMapper;
    private final SubjectMapper subjectMapper;
    private final TeacherProfileMapper teacherProfileMapper;
    private final UserPermissionMapper userPermissionMapper;
    private final ExcelTeacherHelper excelTeacher;
    private final ExcelTeachHelper excelTeach;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final RoleApprovalService approvalService;

    /** 批量导入的统一初始密码（导入后教师首登强制改密） */
    public static final String INITIAL_PASSWORD = "Shishi@2026";

    /** 本控制器管理的角色（家长 PARENT 走 AdminParentController；批32 加级长/主任/生活老师/招采；批39④ 加书记；
     *  批63 加后勤中心/维修工——报修三层流转） */
    private static final List<String> ROLES = List.of("ADMIN", "LEADER", "HEAD_TEACHER", "TEACHER", "GUARD",
            "GRADE_LEADER", "DIRECTOR", "DORM", "PROCUREMENT", "SECRETARY", "LOGISTICS", "REPAIRER");

    private void checkAdmin() {
        permissionService.checkAdminAccess("只有管理员可操作系统管理");
    }

    @Data
    public static class UserReq {
        @NotBlank(message = "username 不能为空")
        private String username;
        @NotBlank(message = "password 不能为空")
        @Size(min = 8, message = "password 至少 8 位")
        private String password;
        @NotBlank(message = "realName 不能为空")
        private String realName;
        @NotBlank(message = "role 不能为空")
        private String role;
        private String phone;
        private String stageScope; // 批39③：领导分管学段 PRIMARY/JUNIOR（仅 LEADER 有效）
    }

    @Data
    public static class UserEditReq {
        private String realName;
        private String role;
        private String phone;
        private String username; // 批8.5：换绑登录名（ss 临时号等改名转正）
        private String stageScope; // 批39③：领导分管学段（非领导角色一律清空）
    }

    /** 批39③ 分管学段归一：仅 LEADER 保留 PRIMARY/JUNIOR，其余角色/空值一律 null（=全部） */
    private String normStageScope(String role, String v) {
        if (!"LEADER".equals(role) || v == null || v.isBlank()) {
            return null;
        }
        if (!"PRIMARY".equals(v) && !"JUNIOR".equals(v)) {
            throw new BizException(400, "stageScope 只能是 PRIMARY（小学部）/JUNIOR（初中部）");
        }
        return v;
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
    public static class TeachReq {
        @NotNull(message = "teacherId 不能为空")
        private Long teacherId;
        @NotNull(message = "classId 不能为空")
        private Long classId;
        @NotNull(message = "subjectId 不能为空")
        private Long subjectId;
    }

    /** 教师账号列表（分页，含角色/关键词过滤；不回传密码） */
    @GetMapping("/user/list")
    public ApiResponse<Map<String, Object>> userList(@RequestParam(required = false) String role,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size) {
        checkAdmin();
        // size 上限 1000：教师/行政全量（487+）要进下拉选择器（值班排班/OA 审批人），钳 100 会丢高 id 新号
        var p = userMapper.selectPage(Page.of(page, Math.min(size, 1000)), new LambdaQueryWrapper<User>()
                .in(User::getRole, ROLES) // 家长账号不混入教师列表
                .eq(role != null && !role.isBlank(), User::getRole, role)
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(User::getUsername, keyword).or().like(User::getRealName, keyword))
                .orderByAsc(User::getId));
        List<Map<String, Object>> records = p.getRecords().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName());
            m.put("role", u.getRole());
            m.put("phone", u.getPhone());
            m.put("stageScope", u.getStageScope()); // 批39③ 领导分管学段回显
            m.put("status", u.getStatus());
            return m;
        }).toList();
        // 批49 权限点标志批量回显（TeacherTab 两枚开关渲染免逐行 N+1；ADMIN 天生全权不落表恒 false）
        // 批56 连带修：keyword 查无结果时空集合 .in() 生成 IN () 非法 SQL（selectPage 空页必炸，判空跳过）
        List<Long> pageIds = p.getRecords().stream().map(User::getId).toList();
        Map<Long, Set<String>> permByUser = pageIds.isEmpty() ? Map.of()
                : userPermissionMapper.selectList(new LambdaQueryWrapper<UserPermission>()
                        .in(UserPermission::getUserId, pageIds))
                .stream().collect(Collectors.groupingBy(UserPermission::getUserId,
                        Collectors.mapping(UserPermission::getPermCode, Collectors.toSet())));
        for (int i = 0; i < records.size(); i++) {
            Set<String> codes = permByUser.getOrDefault(p.getRecords().get(i).getId(), Set.of());
            records.get(i).put("adminAccess", codes.contains(PermissionService.PERM_ADMIN_ACCESS));
            records.get(i).put("scoreAll", codes.contains(PermissionService.PERM_SCORE_ALL));
        }
        // 内嵌待审批标记（批2-5）：pendingCreate=新号待启用；pendingUpgradeTo=升级审批中的目标角色
        Map<Long, RoleRequest> pendings = approvalService.pendingByUsers(pageIds);
        for (int i = 0; i < records.size(); i++) {
            User u = p.getRecords().get(i);
            RoleRequest pr = pendings.get(u.getId());
            if (pr == null) {
                continue;
            }
            if (RoleRequest.TYPE_CREATE.equals(pr.getReqType())) {
                records.get(i).put("pendingCreate", pr.getTargetRole());
            } else {
                records.get(i).put("pendingUpgradeTo", pr.getTargetRole());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("records", records);
        return ApiResponse.ok(data);
    }

    @PostMapping("/user")
    public ApiResponse<Map<String, Object>> createUser(@Validated @RequestBody UserReq req) {
        checkAdmin();
        if (!ROLES.contains(req.getRole())) {
            throw new BizException(400, "role 必须是 ADMIN/LEADER/HEAD_TEACHER/TEACHER/GUARD/GRADE_LEADER/DIRECTOR/DORM/PROCUREMENT/SECRETARY/LOGISTICS/REPAIRER");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException(400, "用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        u.setRole(req.getRole());
        u.setPhone(req.getPhone());
        u.setStageScope(normStageScope(req.getRole(), req.getStageScope()));
        // 管理员/领导走双人审批（批2-5）：建号即停用，另一名管理员/领导通过后才启用
        boolean needsApproval = RoleApprovalService.needsApproval(req.getRole());
        u.setStatus(needsApproval ? 0 : 1);
        u.setMustChangePwd(1); // 管理员设的密码非本人自设：首登强制改密
        userMapper.insert(u);
        if (needsApproval) {
            approvalService.submitCreate(u.getId(), req.getRole());
            return ApiResponse.ok(Map.of("userId", u.getId(), "pendingApproval", true));
        }
        return ApiResponse.ok(Map.of("userId", u.getId()));
    }

    /** 教师批量导入（管理员，multipart）：账号+档案+班主任带班一次建；逐行校验部分成功；统一初始密码（教师登录 App 自行修改） */
    @PostMapping("/teacher/import")
    public ApiResponse<Map<String, Object>> importTeachers(@RequestParam("file") MultipartFile file) {
        checkAdmin();
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择 Excel 文件");
        }
        List<ExcelTeacherHelper.TeacherRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = excelTeacher.read(in);
        } catch (IOException e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (rows.isEmpty()) {
            throw new BizException(400, "Excel 里没有数据行（首行为表头，请从第 2 行开始填写）");
        }
        Map<String, Long> subjectIds = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getName, Subject::getId, (a, b) -> a));
        Map<String, Long> classIds = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getName, Clazz::getId, (a, b) -> a));
        Map<String, String> roleMap = java.util.Map.ofEntries(
                java.util.Map.entry("管理员", "ADMIN"), java.util.Map.entry("领导", "LEADER"),
                java.util.Map.entry("班主任", "HEAD_TEACHER"), java.util.Map.entry("教师", "TEACHER"),
                java.util.Map.entry("门卫", "GUARD"), java.util.Map.entry("级长", "GRADE_LEADER"),
                java.util.Map.entry("学成中心主任", "DIRECTOR"), java.util.Map.entry("生活老师", "DORM"),
                java.util.Map.entry("招采", "PROCUREMENT"), java.util.Map.entry("书记", "SECRETARY"),
                java.util.Map.entry("后勤中心", "LOGISTICS"), java.util.Map.entry("水电维修工", "REPAIRER"));
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> errors = new java.util.ArrayList<>();
        int inserted = 0;
        for (ExcelTeacherHelper.TeacherRow r : rows) {
            Integer years = r.teachingYears().isBlank() ? null : parseIntOrNull(r.teachingYears());
            LocalDate hireDate = r.hireDate().isBlank() ? null : parseDateOrNull(r.hireDate());
            boolean hasHeadClass = !r.headClassName().isBlank();
            Clazz headClazz = hasHeadClass && classIds.containsKey(r.headClassName())
                    ? clazzMapper.selectById(classIds.get(r.headClassName())) : null;
            String reason = null;
            if (r.username().isBlank()) {
                reason = "账号为空";
            } else if (seen.contains(r.username())) {
                reason = "账号在文件内重复";
            } else if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, r.username())) > 0) {
                reason = "账号已存在";
            } else if (r.realName().isBlank()) {
                reason = "姓名为空";
            } else if (!roleMap.containsKey(r.role())) {
                reason = "角色只能填 管理员/领导/班主任/教师/门卫/级长/学成中心主任/生活老师/招采: "
                        + (r.role().isBlank() ? "(空)" : r.role());
            } else if (!r.gender().isBlank() && !"男".equals(r.gender()) && !"女".equals(r.gender())) {
                reason = "性别只能填 男/女: " + r.gender();
            } else if (!r.subjectName().isBlank() && !subjectIds.containsKey(r.subjectName())) {
                reason = "任教学科不存在: " + r.subjectName();
            } else if (!r.employeeNo().isBlank() && teacherProfileMapper.selectCount(
                    new LambdaQueryWrapper<TeacherProfile>()
                            .eq(TeacherProfile::getEmployeeNo, r.employeeNo())) > 0) {
                reason = "工号已存在: " + r.employeeNo();
            } else if (years == null && !r.teachingYears().isBlank()) {
                reason = "教龄须为数字: " + r.teachingYears();
            } else if (hireDate == null && !r.hireDate().isBlank()) {
                reason = "入职年月格式须为 2026-08-30: " + r.hireDate();
            } else if (hasHeadClass && !"班主任".equals(r.role())) {
                reason = "只有班主任才能带班";
            } else if (hasHeadClass && headClazz == null) {
                reason = "班主任所带班级不存在: " + r.headClassName();
            } else if (headClazz != null && headClazz.getHeadTeacherId() != null) {
                reason = "该班已有班主任（请先在班级管理中调整）";
            }
            if (reason != null) {
                errors.add(Map.of("row", r.rowNum(), "reason", reason));
                continue;
            }
            seen.add(r.username());
            User u = new User();
            u.setUsername(r.username());
            u.setPasswordHash(passwordEncoder.encode(INITIAL_PASSWORD));
            u.setRealName(r.realName());
            u.setRole(roleMap.get(r.role()));
            u.setPhone(r.phone().isBlank() ? null : r.phone());
            // 管理员/领导行同走双人审批（批2-5）：建号即停用，审批通过后才启用
            boolean needsApproval = RoleApprovalService.needsApproval(roleMap.get(r.role()));
            u.setStatus(needsApproval ? 0 : 1);
            u.setMustChangePwd(1); // 统一初始密码：首登强制改密
            userMapper.insert(u);
            if (needsApproval) {
                approvalService.submitCreate(u.getId(), roleMap.get(r.role()));
            }
            boolean anyProfileField = !r.employeeNo().isBlank() || !r.gender().isBlank()
                    || !r.subjectName().isBlank() || !r.title().isBlank() || !r.duty().isBlank()
                    || years != null || hireDate != null || !r.intro().isBlank();
            if (anyProfileField) {
                TeacherProfile p = new TeacherProfile();
                p.setUserId(u.getId());
                p.setEmployeeNo(blankToNull(r.employeeNo()));
                p.setGender(blankToNull(r.gender()));
                p.setSubjectId(r.subjectName().isBlank() ? null : subjectIds.get(r.subjectName()));
                p.setTitle(blankToNull(r.title()));
                p.setDuty(blankToNull(r.duty()));
                p.setTeachingYears(years);
                p.setHireDate(hireDate);
                p.setIntro(blankToNull(r.intro()));
                teacherProfileMapper.insert(p);
            }
            if (headClazz != null) {
                headClazz.setHeadTeacherId(u.getId());
                clazzMapper.updateById(headClazz);
            }
            inserted++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        data.put("failed", errors.size());
        data.put("errors", errors);
        data.put("initialPassword", INITIAL_PASSWORD);
        data.put("pendingApprovals", approvalService.pendingCount()); // 含本次导入的管理员/领导号
        return ApiResponse.ok(data);
    }

    /** 教师导入模板下载（仅表头，.xlsx） */
    @GetMapping("/teacher/import-template")
    public ResponseEntity<byte[]> teacherImportTemplate() {
        checkAdmin();
        byte[] bytes = excelTeacher.template();
        String filename = URLEncoder.encode("教师导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDateOrNull(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @PutMapping("/user/{id}")
    public ApiResponse<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody UserEditReq req) {
        checkAdmin();
        User u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "账号不存在");
        }
        if (AuthUtil.current().userId().equals(id)
                && req.getRole() != null && !req.getRole().equals(u.getRole())) {
            throw new BizException(400, "不能修改自己的角色");
        }
        if (req.getRole() != null && !ROLES.contains(req.getRole())) {
            throw new BizException(400, "role 必须是 ADMIN/LEADER/HEAD_TEACHER/TEACHER/GUARD/GRADE_LEADER/DIRECTOR/DORM/PROCUREMENT/SECRETARY/LOGISTICS/REPAIRER");
        }
        String newRole = req.getRole() != null ? req.getRole() : u.getRole();
        // 换绑登录名（批8.5）：非空才改；唯一性+格式校验，改名后原 token 不受影响（按 userId 鉴权）
        String newUsername = req.getUsername() == null || req.getUsername().isBlank()
                ? u.getUsername() : req.getUsername().trim();
        if (!newUsername.equals(u.getUsername())) {
            if (newUsername.length() < 2 || newUsername.length() > 32 || newUsername.matches(".*\\s.*")) {
                throw new BizException(400, "登录名须 2-32 位且不含空格");
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, newUsername)) > 0) {
                throw new BizException(400, "登录名已存在");
            }
        }
        // 教师原地升入 ADMIN/LEADER 走双人审批（批2-5）：本次不改角色，另一名管理员/领导通过后才生效。
        // 已是 ADMIN/LEADER 互转（已过一次审批）与降级不触发。
        boolean upgradeApproval = RoleApprovalService.needsApproval(newRole)
                && !RoleApprovalService.needsApproval(u.getRole())
                && !newRole.equals(u.getRole());
        if (upgradeApproval) {
            approvalService.submitUpgrade(id, u.getRole(), newRole);
            // 其余字段（姓名/手机/登录名）照常生效；角色保持原值
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, id)
                    .set(User::getRealName, req.getRealName() != null ? req.getRealName() : u.getRealName())
                    .set(User::getPhone, req.getPhone())
                    .set(!newUsername.equals(u.getUsername()), User::getUsername, newUsername));
            return ApiResponse.ok(Map.of("pendingApproval", true));
        }
        // 批39③ 分管学段：显式传值按传值归一；字段缺失时按新角色归一原值（改角色自动清、同角色保留）
        String newStage = req.getStageScope() == null
                ? ("LEADER".equals(newRole) ? u.getStageScope() : null)
                : normStageScope(newRole, req.getStageScope());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getRealName, req.getRealName() != null ? req.getRealName() : u.getRealName())
                .set(User::getRole, newRole)
                .set(User::getPhone, req.getPhone())
                .set(User::getStageScope, newStage)
                .set(!newUsername.equals(u.getUsername()), User::getUsername, newUsername));
        // 角色变更清权限点，防 LEADER 改角后遗留管理员级权限
        if (!newRole.equals(u.getRole())) {
            userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, id));
        }
        return ApiResponse.ok(Map.of("pendingApproval", false));
    }

    /** 权限点查询（批1 ADMIN_ACCESS / 批49 SCORE_ALL；TeacherTab 权限开关回显） */
    @GetMapping("/user/{id}/perms")
    public ApiResponse<Map<String, Object>> userPerms(@PathVariable Long id) {
        checkAdmin();
        User u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "账号不存在");
        }
        List<String> perms = userPermissionMapper.selectList(new LambdaQueryWrapper<UserPermission>()
                        .eq(UserPermission::getUserId, id))
                .stream().map(UserPermission::getPermCode).toList();
        return ApiResponse.ok(Map.of("role", u.getRole(), "perms", perms));
    }

    /** 授予/收回权限点（批1 ADMIN_ACCESS 仅领导可授；批49 SCORE_ALL 全校成绩查看=教学角色可授；code 缺省 ADMIN_ACCESS 兼容老调用） */
    @PutMapping("/user/{id}/perm")
    public ApiResponse<Void> grantPerm(@PathVariable Long id, @Validated @RequestBody PermReq req) {
        checkAdmin();
        User u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "账号不存在");
        }
        String code = req.getCode() == null || req.getCode().isBlank()
                ? PermissionService.PERM_ADMIN_ACCESS : req.getCode();
        if (PermissionService.PERM_ADMIN_ACCESS.equals(code)) {
            if (!"LEADER".equals(u.getRole())) {
                throw new BizException(400, "仅领导账号可授予管理员级权限");
            }
        } else if (PermissionService.PERM_SCORE_ALL.equals(code)) {
            if (!List.of("TEACHER", "HEAD_TEACHER", "LEADER", "DIRECTOR", "GRADE_LEADER").contains(u.getRole())) {
                throw new BizException(400, "全校成绩查看权限仅教学角色可授予");
            }
        } else {
            throw new BizException(400, "未知权限点: " + code);
        }
        if (Boolean.TRUE.equals(req.getGranted())) {
            if (userPermissionMapper.selectCount(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, id)
                    .eq(UserPermission::getPermCode, code)) == 0) {
                UserPermission p = new UserPermission();
                p.setUserId(id);
                p.setPermCode(code);
                p.setGrantedBy(AuthUtil.current().userId());
                userPermissionMapper.insert(p);
            }
        } else {
            userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, id)
                    .eq(UserPermission::getPermCode, code));
        }
        return ApiResponse.ok();
    }

    @Data
    public static class PermReq {
        @NotNull(message = "granted 不能为空")
        private Boolean granted;
        private String code;
    }

    /** 重置密码（重置后该账号下次登录强制改密） */
    @PutMapping("/user/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Validated @RequestBody PasswordReq req) {
        checkAdmin();
        if (userMapper.selectById(id) == null) {
            throw new BizException(404, "账号不存在");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getPasswordHash, passwordEncoder.encode(req.getPassword()))
                .set(User::getMustChangePwd, 1));
        return ApiResponse.ok();
    }

    /** 停用/启用（不可停用自己） */
    @PutMapping("/user/{id}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable Long id, @Validated @RequestBody StatusReq req) {
        checkAdmin();
        if (req.getStatus() != 0 && req.getStatus() != 1) {
            throw new BizException(400, "status 必须是 0/1");
        }
        if (AuthUtil.current().userId().equals(id) && req.getStatus() == 0) {
            throw new BizException(400, "不能停用自己");
        }
        if (userMapper.selectById(id) == null) {
            throw new BizException(404, "账号不存在");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id).set(User::getStatus, req.getStatus()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/user/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        checkAdmin();
        if (AuthUtil.current().userId().equals(id)) {
            throw new BizException(400, "不能删除自己");
        }
        if (userMapper.selectById(id) == null) {
            throw new BizException(404, "账号不存在");
        }
        if (teachMapper.selectCount(new LambdaQueryWrapper<Teach>().eq(Teach::getTeacherId, id)) > 0) {
            throw new BizException(400, "该教师仍有任课关系，请先删除任课记录");
        }
        if (clazzMapper.selectCount(new LambdaQueryWrapper<Clazz>().eq(Clazz::getHeadTeacherId, id)) > 0) {
            throw new BizException(400, "该教师仍是某班班主任，请先调整班级");
        }
        userMapper.deleteById(id);
        userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                .eq(UserPermission::getUserId, id)); // 顺带清权限点
        approvalService.withdrawByUser(id);          // 连带撤回待审批请求（批2-5）
        return ApiResponse.ok();
    }

    // ───────── 批量操作（批15：批量操作全面覆盖收口，镜像家长账号批量） ─────────

    @Data
    public static class UserBatchStatusReq {
        @NotEmpty(message = "ids 不能为空")
        private List<Long> ids;
        @NotNull(message = "status 不能为空")
        private Integer status;
    }

    @Data
    public static class UserBatchIdsReq {
        @NotEmpty(message = "ids 不能为空")
        private List<Long> ids;
    }

    /** 批量启停（不可含自己停用） */
    @PutMapping("/user/batch/status")
    public ApiResponse<Void> batchUpdateUserStatus(@Validated @RequestBody UserBatchStatusReq req) {
        checkAdmin();
        if (req.getStatus() != 0 && req.getStatus() != 1) {
            throw new BizException(400, "status 必须是 0/1");
        }
        if (req.getStatus() == 0 && req.getIds().contains(AuthUtil.current().userId())) {
            throw new BizException(400, "不能停用自己");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .in(User::getId, req.getIds()).set(User::getStatus, req.getStatus()));
        return ApiResponse.ok();
    }

    /** 批量重置为统一初始密码（重置后全部强制改密） */
    @PutMapping("/user/batch/reset-password")
    public ApiResponse<Map<String, Object>> batchResetPassword(@Validated @RequestBody UserBatchIdsReq req) {
        checkAdmin();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .in(User::getId, req.getIds())
                .set(User::getPasswordHash, passwordEncoder.encode(INITIAL_PASSWORD))
                .set(User::getMustChangePwd, 1));
        return ApiResponse.ok(Map.of("initialPassword", INITIAL_PASSWORD));
    }

    /** 批量删除（逐个走单删护栏：任课/班主任前置；失败名单返回，其余成功） */
    @DeleteMapping("/user/batch")
    public ApiResponse<Map<String, Object>> batchDeleteUser(@Validated @RequestBody UserBatchIdsReq req) {
        checkAdmin();
        List<Map<String, String>> failed = new java.util.ArrayList<>();
        int ok = 0;
        for (Long id : req.getIds()) {
            User u = userMapper.selectById(id);
            if (u == null) {
                failed.add(failRow(id, "", "账号不存在"));
                continue;
            }
            if (AuthUtil.current().userId().equals(id)) {
                failed.add(failRow(id, u.getRealName(), "不能删除自己"));
                continue;
            }
            if (teachMapper.selectCount(new LambdaQueryWrapper<Teach>().eq(Teach::getTeacherId, id)) > 0) {
                failed.add(failRow(id, u.getRealName(), "仍有任课关系，请先删除任课记录"));
                continue;
            }
            if (clazzMapper.selectCount(new LambdaQueryWrapper<Clazz>().eq(Clazz::getHeadTeacherId, id)) > 0) {
                failed.add(failRow(id, u.getRealName(), "仍是某班班主任，请先调整班级"));
                continue;
            }
            userMapper.deleteById(id);
            userPermissionMapper.delete(new LambdaQueryWrapper<UserPermission>()
                    .eq(UserPermission::getUserId, id)); // 顺带清权限点
            approvalService.withdrawByUser(id);          // 连带撤回待审批请求（批2-5）
            ok++;
        }
        return ApiResponse.ok(Map.of("deleted", ok, "failed", failed));
    }

    private Map<String, String> failRow(Long id, String name, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("name", name == null ? "" : name);
        m.put("reason", reason);
        return m;
    }

    /** 任课关系列表（teacherId 可选，附教师/班级/学科名） */
    @GetMapping("/teach/list")
    public ApiResponse<List<Map<String, Object>>> teachList(@RequestParam(required = false) Long teacherId) {
        checkAdmin();
        List<Teach> rows = teachMapper.selectList(new LambdaQueryWrapper<Teach>()
                .eq(teacherId != null, Teach::getTeacherId, teacherId)
                .orderByAsc(Teach::getId));
        Map<Long, String> userNames = names(rows.stream().map(Teach::getTeacherId).toList(),
                ids -> userMapper.selectBatchIds(ids), User::getId, User::getRealName);
        Map<Long, String> classNames = names(rows.stream().map(Teach::getClassId).toList(),
                ids -> clazzMapper.selectBatchIds(ids), Clazz::getId, Clazz::getName);
        Map<Long, String> subjectNames = names(rows.stream().map(Teach::getSubjectId).toList(),
                ids -> subjectMapper.selectBatchIds(ids), Subject::getId, Subject::getName);
        List<Map<String, Object>> out = rows.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("teacherId", t.getTeacherId());
            m.put("teacherName", userNames.get(t.getTeacherId()));
            m.put("classId", t.getClassId());
            m.put("className", classNames.get(t.getClassId()));
            m.put("subjectId", t.getSubjectId());
            m.put("subjectName", subjectNames.get(t.getSubjectId()));
            return m;
        }).toList();
        return ApiResponse.ok(out);
    }

    private <T> Map<Long, String> names(List<Long> ids, Function<List<Long>, List<T>> loader,
                                        Function<T, Long> key, Function<T, String> value) {
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return loader.apply(distinct).stream().collect(Collectors.toMap(key, value, (a, b) -> a));
    }

    @PostMapping("/teach")
    public ApiResponse<Map<String, Object>> createTeach(@Validated @RequestBody TeachReq req) {
        checkAdmin();
        if (teachMapper.selectCount(new LambdaQueryWrapper<Teach>()
                .eq(Teach::getTeacherId, req.getTeacherId())
                .eq(Teach::getClassId, req.getClassId())
                .eq(Teach::getSubjectId, req.getSubjectId())) > 0) {
            throw new BizException(400, "该任课关系已存在");
        }
        if (userMapper.selectById(req.getTeacherId()) == null) {
            throw new BizException(404, "教师不存在");
        }
        if (clazzMapper.selectById(req.getClassId()) == null) {
            throw new BizException(404, "班级不存在");
        }
        if (subjectMapper.selectById(req.getSubjectId()) == null) {
            throw new BizException(404, "学科不存在");
        }
        Teach t = new Teach();
        t.setTeacherId(req.getTeacherId());
        t.setClassId(req.getClassId());
        t.setSubjectId(req.getSubjectId());
        teachMapper.insert(t);
        return ApiResponse.ok(Map.of("teachId", t.getId()));
    }

    @DeleteMapping("/teach/{id}")
    public ApiResponse<Void> deleteTeach(@PathVariable Long id) {
        checkAdmin();
        if (teachMapper.selectById(id) == null) {
            throw new BizException(404, "任课关系不存在");
        }
        teachMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * 任课关系批量导入（管理员，multipart）：教师列填登录账号或姓名（重名须改填账号）。
     * mode=append（默认）逐行校验部分成功；mode=replace 覆盖模式（换课表）：任一行有误整体拒绝，
     * 事务内清空现有任课后按本表全量重插，不残留旧关系。
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/teach/import")
    public ApiResponse<Map<String, Object>> importTeaches(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "append") String mode) {
        checkAdmin();
        boolean replace = "replace".equals(mode);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择 Excel 文件");
        }
        List<ExcelTeachHelper.TeachRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = excelTeach.read(in);
        } catch (IOException e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (rows.isEmpty()) {
            throw new BizException(400, "Excel 里没有数据行（首行为表头，请从第 2 行开始填写）");
        }
        // 教师按账号或姓名匹配（仅教工四类）；班级/学科按名称精确匹配
        List<User> staff = userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getRole, ROLES));
        Map<String, User> byUsername = staff.stream()
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));
        Map<String, List<User>> byName = staff.stream()
                .collect(Collectors.groupingBy(User::getRealName));
        Map<String, Long> classIds = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getName, Clazz::getId, (a, b) -> a));
        Map<String, Long> subjectIds = subjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Subject::getName, Subject::getId, (a, b) -> a));
        Set<String> existing = teachMapper.selectList(null).stream()
                .map(t -> t.getTeacherId() + "#" + t.getClassId() + "#" + t.getSubjectId())
                .collect(Collectors.toSet());
        // 先全量校验（append 部分成功；replace 任一行有误整体拒绝，避免清空后导不进）
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> errors = new java.util.ArrayList<>();
        List<Teach> targets = new java.util.ArrayList<>();
        int dupSkipped = 0;
        for (ExcelTeachHelper.TeachRow r : rows) {
            User teacher = byUsername.get(r.teacher());
            String reason = null;
            if (teacher == null) {
                List<User> named = byName.get(r.teacher());
                if (named == null) {
                    reason = "教师不存在（账号或姓名都未匹配到）: " + r.teacher();
                } else if (named.size() > 1) {
                    reason = "姓名重名，请改填登录账号: " + r.teacher();
                } else {
                    teacher = named.get(0);
                }
            }
            Long classId = null;
            Long subjectId = null;
            if (reason == null && !classIds.containsKey(r.className())) {
                reason = "班级不存在: " + r.className();
            } else {
                classId = classIds.get(r.className());
            }
            if (reason == null && !subjectIds.containsKey(r.subjectName())) {
                reason = "学科不存在（须与管理端学科名完全一致）: " + r.subjectName();
            } else {
                subjectId = subjectIds.get(r.subjectName());
            }
            if (reason != null) {
                errors.add(Map.of("row", r.rowNum(), "reason", reason));
                continue;
            }
            String key = teacher.getId() + "#" + classId + "#" + subjectId;
            if (seen.contains(key)) { // 表内重复行去重（两模式同口径）
                dupSkipped++;
                continue;
            }
            seen.add(key);
            Teach t = new Teach();
            t.setTeacherId(teacher.getId());
            t.setClassId(classId);
            t.setSubjectId(subjectId);
            targets.add(t);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (replace) {
            if (!errors.isEmpty()) {
                Map<String, Object> first = errors.get(0);
                throw new BizException(400, "覆盖模式任一行有误则整体不导入（第 " + first.get("row")
                        + " 行：" + first.get("reason") + "），共 " + errors.size() + " 行问题");
            }
            long removedOld = teachMapper.selectCount(null);
            teachMapper.delete(null); // 清空旧任课，本表即新课表全量
            for (Teach t : targets) {
                teachMapper.insert(t);
            }
            data.put("mode", "replace");
            data.put("removedOld", removedOld);
            data.put("inserted", targets.size());
            data.put("skipped", dupSkipped);
            data.put("failed", 0);
            return ApiResponse.ok(data);
        }
        int inserted = 0;
        int skipped = dupSkipped;
        for (Teach t : targets) {
            String key = t.getTeacherId() + "#" + t.getClassId() + "#" + t.getSubjectId();
            if (existing.contains(key)) {
                skipped++; // 已有的任课关系静默跳过，不算失败
                continue;
            }
            teachMapper.insert(t);
            existing.add(key);
            inserted++;
        }
        data.put("inserted", inserted);
        data.put("skipped", skipped);
        data.put("failed", errors.size());
        data.put("errors", errors);
        return ApiResponse.ok(data);
    }

    /** 任课导入模板下载（仅表头，.xlsx） */
    @GetMapping("/teach/import-template")
    public ResponseEntity<byte[]> teachImportTemplate() {
        checkAdmin();
        byte[] bytes = excelTeach.template();
        String filename = URLEncoder.encode("任课导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }
}
