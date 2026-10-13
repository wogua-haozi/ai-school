package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.InviteCode;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.InviteCodeMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.DataScopeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 家长绑定邀请码（批8.6）：班主任/级长/管理员按班生成，一码一学生；
 * 家长凭「学号+邀请码」自助注册绑定（AuthController.registerParent）。
 */
@RestController
@RequestMapping("/api/invite")
@RequiredArgsConstructor
public class InviteController {

    /** 去除易混字符（0/O、1/I/L）的码表 */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final InviteCodeMapper inviteMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final UserMapper userMapper;
    private final ParentBindingMapper bindingMapper;
    private final DataScopeService dataScope;

    @Data
    public static class GenerateReq {
        private Long studentId;
        private Long classId;
    }

    /** 单个/按班生成：classId 携带时生成一个班级码（全班共用不消费，旧班级码作废换新）；
     *  studentId 携带时生成学生级码（转学生等个别场景，一码一学生，注册消费置 USED） */
    @PostMapping("/generate")
    public ApiResponse<List<Map<String, Object>>> generate(@RequestBody GenerateReq req) {
        var user = AuthUtil.current();
        List<Map<String, Object>> out = new ArrayList<>();
        if (req.getClassId() != null) {
            dataScope.checkClassOperable(user, req.getClassId());
            if (clazzMapper.selectById(req.getClassId()) == null) {
                throw new BizException(404, "班级不存在");
            }
            out.add(Map.of(
                    "classId", req.getClassId(),
                    "code", issueClass(req.getClassId())));
        } else if (req.getStudentId() != null) {
            Student s = studentMapper.selectById(req.getStudentId());
            if (s == null) {
                throw new BizException(404, "学生不存在");
            }
            dataScope.checkClassOperable(user, s.getClassId());
            out.add(Map.of(
                    "studentId", s.getId(),
                    "studentNo", s.getStudentNo() == null ? "" : s.getStudentNo(),
                    "name", s.getName(),
                    "code", issue(s.getId())));
        } else {
            throw new BizException(400, "须携带 studentId 或 classId");
        }
        return ApiResponse.ok(out);
    }

    /** 班内码况：班级码（每行同值，前端取 rows[0].classCode）+ 每生绑定/注册状态 + 学生级码（转学生个别发） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam Long classId) {
        var user = AuthUtil.current();
        dataScope.checkClassOperable(user, classId);
        List<Student> students = classStudents(classId);
        Map<Long, InviteCode> codes = inviteMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                        .in(InviteCode::getStudentId, students.stream().map(Student::getId).toList())
                        .eq(InviteCode::getStatus, InviteCode.UNUSED))
                .stream().collect(Collectors.toMap(InviteCode::getStudentId, c -> c, (a, b) -> a));
        Map<Long, Long> boundCount = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                        .in(ParentBinding::getStudentId, students.stream().map(Student::getId).toList()))
                .stream().collect(Collectors.groupingBy(ParentBinding::getStudentId, Collectors.counting()));
        Set<Long> usedCodeStudents = inviteMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                        .in(InviteCode::getStudentId, students.stream().map(Student::getId).toList())
                        .eq(InviteCode::getStatus, InviteCode.USED))
                .stream().map(InviteCode::getStudentId).collect(Collectors.toSet());
        String classCode = inviteMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                        .eq(InviteCode::getClassId, classId).eq(InviteCode::getStatus, InviteCode.UNUSED)
                        .orderByDesc(InviteCode::getId).last("LIMIT 1"))
                .stream().findFirst().map(InviteCode::getCode).orElse("");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Student s : students) {
            InviteCode c = codes.get(s.getId());
            out.add(Map.of(
                    "studentId", s.getId(),
                    "studentNo", s.getStudentNo() == null ? "" : s.getStudentNo(),
                    "name", s.getName(),
                    "code", c == null ? "" : c.getCode(),
                    "classCode", classCode,
                    "registered", usedCodeStudents.contains(s.getId()),
                    "boundCount", boundCount.getOrDefault(s.getId(), 0L)));
        }
        return ApiResponse.ok(out);
    }

    /** 生成该生新码：旧未用码作废（家长手里旧码即失效，防扩散） */
    private String issue(Long studentId) {
        inviteMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                        .eq(InviteCode::getStudentId, studentId).eq(InviteCode::getStatus, InviteCode.UNUSED))
                .forEach(c -> {
                    c.setStatus(InviteCode.VOID);
                    inviteMapper.updateById(c);
                });
        return insert(studentId, null);
    }

    /** 生成班级新码：旧班级码作废换新；全班共用不消费（status 恒 UNUSED，可反复注册） */
    private String issueClass(Long classId) {
        inviteMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                        .eq(InviteCode::getClassId, classId).eq(InviteCode::getStatus, InviteCode.UNUSED))
                .forEach(c -> {
                    c.setStatus(InviteCode.VOID);
                    inviteMapper.updateById(c);
                });
        return insert(null, classId);
    }

    private String insert(Long studentId, Long classId) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        InviteCode c = new InviteCode();
        c.setStudentId(studentId);
        c.setClassId(classId);
        c.setCode(sb.toString());
        c.setStatus(InviteCode.UNUSED);
        inviteMapper.insert(c);
        return c.getCode();
    }

    private List<Student> classStudents(Long classId) {
        if (clazzMapper.selectById(classId) == null) {
            throw new BizException(404, "班级不存在");
        }
        return studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId)
                .and(q -> q.isNull(Student::getStatus).or().notIn(Student::getStatus, "毕业", "转出"))
                .orderByAsc(Student::getStudentNo));
    }
}
