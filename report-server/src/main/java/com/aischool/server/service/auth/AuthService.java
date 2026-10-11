package com.aischool.server.service.auth;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.InviteCode;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.InviteCodeMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.JwtService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** 登录失败防护：同用户名 15 分钟内 5 次失败 → 锁 10 分钟（单实例内存实现，重启即解） */
    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000;
    private static final long LOCK_MS = 10 * 60 * 1000;

    /** 滑动窗口内失败计数（可变） */
    private static final class FailCount {
        int count;
        long windowStart;
    }

    private final Map<String, FailCount> loginFails = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RoleApprovalService roleApprovalService;
    private final InviteCodeMapper inviteCodeMapper;
    private final StudentMapper studentMapper;
    private final ParentBindingMapper parentBindingMapper;
    private final PermissionService permissionService;

    /** 家长自助注册（批8.6）：学号+邀请码绑定孩子；自助绑定每生上限 2 个（管理端/班主任绑定不受限）。
     *  手机号即登录名；密码自设（≥8 位，无强制改密）。成功即登录，返回与 login 相同结构。 */
    public Map<String, Object> registerParent(String phone, String password, String studentNo,
                                              String inviteCode, String realName, String relation) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new BizException(400, "手机号格式不正确");
        }
        if (password == null || password.length() < 8) {
            throw new BizException(400, "密码至少 8 位");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .and(q -> q.eq(User::getPhone, phone).or().eq(User::getUsername, phone))) > 0) {
            throw new BizException(400, "该手机号已被使用，如忘记密码请联系班主任重置");
        }
        if (studentNo == null || studentNo.isBlank() || inviteCode == null || inviteCode.isBlank()) {
            throw new BizException(400, "请填写孩子学号与邀请码");
        }
        Student student = studentMapper.selectOne(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, studentNo.trim()).last("LIMIT 1"));
        if (student == null) {
            throw new BizException(400, "学号不存在，请向班主任核对");
        }
        if ("毕业".equals(student.getStatus()) || "转出".equals(student.getStatus())) {
            throw new BizException(400, "该学生已不在校，请联系学校管理员");
        }
        InviteCode code = inviteCodeMapper.selectOne(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getCode, inviteCode.trim()).last("LIMIT 1"));
        if (code == null || !student.getId().equals(code.getStudentId())) {
            throw new BizException(400, "邀请码不正确（一码对应一位学生，请向班主任核对）");
        }
        if (code.getStatus() != InviteCode.UNUSED) {
            throw new BizException(400, "邀请码已使用或已作废，请向班主任重新获取");
        }
        // 自助上限 2/生（管理端/班主任绑定不占此限，爷爷奶奶兜底走管理端）
        long selfBound = parentBindingMapper.selectCount(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getStudentId, student.getId())
                .eq(ParentBinding::getSource, 1));
        if (selfBound >= 2) {
            throw new BizException(400, "该学生的自助注册名额已满（上限 2 位），其余家长请联系班主任绑定");
        }
        // 原子占用邀请码（status 0→1 条件 UPDATE）：防两位家长持同一码并发注册——
        // 先读后写竞态会把一码绑两人并穿透自助上限。占码后建号失败则码作废，班主任可一键重发。
        int taken = inviteCodeMapper.update(null, new LambdaUpdateWrapper<InviteCode>()
                .eq(InviteCode::getId, code.getId())
                .eq(InviteCode::getStatus, InviteCode.UNUSED)
                .set(InviteCode::getStatus, InviteCode.USED));
        if (taken == 0) {
            throw new BizException(400, "邀请码已被使用，请向班主任重新获取");
        }
        User u = new User();
        u.setUsername(phone);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRealName(realName == null || realName.isBlank() ? student.getName() + "家长" : realName.trim());
        u.setRole("PARENT");
        u.setPhone(phone);
        u.setStatus(1);
        u.setMustChangePwd(0);
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) { // 并发撞手机号/登录名（前置查重在竞态窗口外的兜底）
            throw new BizException(400, "该手机号已被使用，如忘记密码请联系班主任重置");
        }
        ParentBinding b = new ParentBinding();
        b.setParentUserId(u.getId());
        b.setStudentId(student.getId());
        b.setRelation(relation == null || relation.isBlank() ? "家长" : relation.trim());
        b.setSource(1);
        parentBindingMapper.insert(b);
        inviteCodeMapper.update(null, new LambdaUpdateWrapper<InviteCode>()
                .eq(InviteCode::getId, code.getId())
                .set(InviteCode::getUsedBy, u.getId()));

        String token = jwtService.issue(u.getId(), u.getUsername(), u.getRealName(), u.getRole(), false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        Map<String, Object> us = new LinkedHashMap<>();
        us.put("id", u.getId());
        us.put("username", u.getUsername());
        us.put("realName", u.getRealName());
        us.put("role", u.getRole());
        us.put("mustChangePassword", false);
        us.put("perms", permissionService.permsOf(u.getId()));
        m.put("user", us);
        return m;
    }

    public Map<String, Object> login(String username, String password) {
        Long locked = lockUntil.get(username);
        if (locked != null && locked > System.currentTimeMillis()) {
            long min = (locked - System.currentTimeMillis()) / 60000 + 1;
            throw new BizException(429, "失败次数过多，请 " + min + " 分钟后再试");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            recordFail(username);
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            // 管理员/领导新号双人审批中（批2-5）：给出准确提示而非笼统「已停用」
            if (roleApprovalService.hasPending(user.getId())) {
                throw new BizException(403, "账号待审批，通过后即可使用");
            }
            throw new BizException(403, "账号已停用");
        }
        loginFails.remove(username);
        lockUntil.remove(username);
        boolean mustChangePwd = user.getMustChangePwd() != null && user.getMustChangePwd() == 1;
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRealName(), user.getRole(), mustChangePwd);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", user.getId());
        u.put("username", user.getUsername());
        u.put("realName", user.getRealName());
        u.put("role", user.getRole());
        u.put("mustChangePassword", mustChangePwd);
        u.put("perms", permissionService.permsOf(user.getId())); // 批49：权限点随登录下发，前端门控用
        m.put("user", u);
        return m;
    }

    /** 修改自己的密码：验证旧密码后更新（新密码至少 8 位，与管理员设密口径统一/NIST SP 800-63B）。
     *  成功后清「待改密」标志并换发新 token（旧 token 的 mcp claim 仍为 1，自然失效于拦截）。 */
    public String changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "账号不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(400, "旧密码不正确");
        }
        if (newPassword.length() < 8) {
            throw new BizException(400, "新密码至少 8 位");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePwd(0);
        userMapper.updateById(user);
        return jwtService.issue(user.getId(), user.getUsername(), user.getRealName(), user.getRole(), false);
    }

    /** 自助换绑手机号（批8.5）：密码确认即换。登录名即手机号的账号（家长）同步改登录名；
     *  换发新 token（token 携带 username）。待改密态被 JwtAuthFilter 拦在门外，无需放行。 */
    public String changePhone(Long userId, String password, String newPhone) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "账号不存在");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(400, "密码不正确");
        }
        if (newPhone == null || !newPhone.matches("^1\\d{10}$")) {
            throw new BizException(400, "手机号格式不正确");
        }
        if (newPhone.equals(user.getPhone()) && newPhone.equals(user.getUsername())) {
            return jwtService.issue(user.getId(), user.getUsername(), user.getRealName(), user.getRole(), false);
        }
        // 唯一性：新手机号不能已被其他账号用作登录名或联系手机（避免登录歧义与联系错人）
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .ne(User::getId, userId)
                .and(q -> q.eq(User::getPhone, newPhone).or().eq(User::getUsername, newPhone))) > 0) {
            throw new BizException(400, "该手机号已被其他账号使用");
        }
        // 登录名形如手机号（家长账号惯例）→ 登录名一并换绑；工号登录的教师只换联系手机号
        boolean renameLogin = user.getUsername() != null && user.getUsername().matches("^1\\d{10}$");
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPhone, newPhone)
                .set(renameLogin, User::getUsername, newPhone));
        String username = renameLogin ? newPhone : user.getUsername();
        return jwtService.issue(user.getId(), username, user.getRealName(), user.getRole(), false);
    }

    /** 教师自助注册（批28）：工号+姓名+手机+密码，建 TEACHER 账号 status=0 并登记待审批；
     *  管理员/领导审批通过后启用（拒绝=删号）。提交后未审批登录提示「账号待审批」。 */
    public Map<String, Object> registerTeacher(String username, String password,
                                               String realName, String phone) {
        if (username == null || !username.matches("^[A-Za-z0-9@._-]{3,64}$")) {
            throw new BizException(400, "工号须为 3-64 位字母/数字/符号(@._-)");
        }
        if (password == null || password.length() < 8) {
            throw new BizException(400, "密码至少 8 位");
        }
        if (realName == null || realName.isBlank()) {
            throw new BizException(400, "请填写真实姓名");
        }
        if (phone != null && !phone.isBlank() && !phone.matches("^1\\d{10}$")) {
            throw new BizException(400, "手机号格式不正确");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0) {
            throw new BizException(400, "该工号已存在，若是自己账号请联系管理员找回");
        }
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRealName(realName.trim());
        u.setRole("TEACHER");
        u.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        u.setStatus(0); // 待审批：通过后启用（RoleApprovalService.approve CREATE 分支）
        u.setMustChangePwd(0);
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "该工号已存在，若是自己账号请联系管理员找回");
        }
        roleApprovalService.submitCreate(u.getId(), "TEACHER", u.getId());
        return Map.of("userId", u.getId(), "username", u.getUsername());
    }

    private void recordFail(String username) {
        long now = System.currentTimeMillis();
        FailCount f = loginFails.compute(username, (k, v) -> {
            if (v == null || now - v.windowStart > WINDOW_MS) {
                FailCount n = new FailCount();
                n.windowStart = now;
                return n;
            }
            return v;
        });
        f.count++;
        if (f.count >= MAX_FAILS) {
            lockUntil.put(username, now + LOCK_MS);
            loginFails.remove(username);
        }
    }
}
