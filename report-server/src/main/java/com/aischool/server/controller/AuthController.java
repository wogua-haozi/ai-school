package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.security.AuditFilter;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.aischool.server.mapper.UserMapper userMapper;

    @Data
    public static class LoginReq {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class ChangePwdReq {
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;
        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }

    @Data
    public static class ChangePhoneReq {
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "新手机号不能为空")
        private String newPhone;
    }

    /** 修改自己的密码（登录态）：成功换发新 token（清除待改密态） */
    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> changePassword(@Validated @RequestBody ChangePwdReq req) {
        String token = authService.changePassword(
                AuthUtil.current().userId(), req.getOldPassword(), req.getNewPassword());
        return ApiResponse.ok(Map.of("token", token));
    }

    /** 自助换绑手机号（批8.5）：密码确认即换；登录名即手机号的账号（家长）同步改登录名 */
    @PutMapping("/phone")
    public ApiResponse<Map<String, Object>> changePhone(@Validated @RequestBody ChangePhoneReq req) {
        String token = authService.changePhone(AuthUtil.current().userId(), req.getPassword(), req.getNewPhone());
        return ApiResponse.ok(Map.of("token", token));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Validated @RequestBody LoginReq req, HttpServletRequest request) {
        Map<String, Object> result = authService.login(req.username, req.getPassword());
        // 认证完成时 SecurityContext 尚无用户，审计行身份会是空——成功后回填给审计过滤器
        request.setAttribute(AuditFilter.ATTR_USER_ID, ((Number) ((Map<?, ?>) result.get("user")).get("id")).longValue());
        request.setAttribute(AuditFilter.ATTR_USERNAME, req.username);
        return ApiResponse.ok(result);
    }

    @Data
    public static class ParentRegisterReq {
        @NotBlank(message = "手机号不能为空")
        private String phone;
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "学号不能为空")
        private String studentNo;
        @NotBlank(message = "邀请码不能为空")
        private String inviteCode;
        private String realName;
        private String relation;
    }

    /** 家长自助注册（批8.6，免登录）：手机号+密码+学号+邀请码，绑定成功即登录 */
    @PostMapping("/parent/register")
    public ApiResponse<Map<String, Object>> registerParent(@Validated @RequestBody ParentRegisterReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> result = authService.registerParent(req.getPhone(), req.getPassword(),
                req.getStudentNo(), req.getInviteCode(), req.getRealName(), req.getRelation());
        request.setAttribute(AuditFilter.ATTR_USER_ID, ((Number) ((Map<?, ?>) result.get("user")).get("id")).longValue());
        request.setAttribute(AuditFilter.ATTR_USERNAME, req.getPhone());
        return ApiResponse.ok(result);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        var user = AuthUtil.current();
        // 批53⑥ 联系电话预填（场地预约确认层读）；Map.of 不容 null，phone 缺失给 ""
        com.aischool.server.entity.User u = userMapper.selectById(user.userId());
        return ApiResponse.ok(Map.of(
                "id", user.userId(), "username", user.username(),
                "realName", user.realName(), "role", user.role(),
                "phone", u == null || u.getPhone() == null ? "" : u.getPhone(),
                "mustChangePassword", user.mustChangePwd()));
    }

    @Data
    public static class TeacherRegisterReq {
        @NotBlank(message = "工号不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "姓名不能为空")
        private String realName;
        private String phone;
    }

    /** 教师自助注册（批28，免登录）：提交后待管理员/领导审批，通过即可登录 */
    @PostMapping("/teacher/register")
    public ApiResponse<Map<String, Object>> registerTeacher(@Validated @RequestBody TeacherRegisterReq req,
                                                            HttpServletRequest request) {
        Map<String, Object> result = authService.registerTeacher(
                req.getUsername().trim(), req.getPassword(), req.getRealName(), req.getPhone());
        request.setAttribute(AuditFilter.ATTR_USER_ID, ((Number) result.get("userId")).longValue());
        request.setAttribute(AuditFilter.ATTR_USERNAME, req.getUsername().trim());
        return ApiResponse.ok(result);
    }
}
