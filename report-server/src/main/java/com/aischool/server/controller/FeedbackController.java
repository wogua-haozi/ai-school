package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Feedback;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.FeedbackMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 意见反馈（批8.5）：所有登录角色（含家长）可提交、查自己的；管理端处理走 AdminFeedbackController */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;

    @Data
    public static class FeedbackReq {
        @NotBlank(message = "反馈内容不能为空")
        @Size(max = 1000, message = "反馈内容最多 1000 字")
        private String content;
        @Size(max = 64, message = "联系方式最多 64 字")
        private String contact;
    }

    @PostMapping("/api/feedback")
    public ApiResponse<Map<String, Object>> submit(@Validated @RequestBody FeedbackReq req) {
        var me = AuthUtil.current();
        String content = req.getContent().trim();
        if (content.isBlank()) {
            throw new BizException(400, "反馈内容不能为空");
        }
        Feedback f = new Feedback();
        f.setUserId(me.userId());
        f.setRole(me.role());
        f.setContent(content);
        // 联系方式默认取账号手机号；家长账号手机号即登录名，方便回访
        String contact = req.getContact() == null ? null : req.getContact().trim();
        if (contact == null || contact.isBlank()) {
            User u = userMapper.selectById(me.userId());
            contact = u == null ? null : u.getPhone();
        }
        f.setContact(contact == null || contact.isBlank() ? null : contact);
        f.setStatus(0);
        feedbackMapper.insert(f);
        return ApiResponse.ok(Map.of("id", f.getId()));
    }

    /** 我的反馈（近 50 条，含处理进度） */
    @GetMapping("/api/feedback/mine")
    public ApiResponse<List<Map<String, Object>>> mine() {
        List<Feedback> rows = feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getUserId, AuthUtil.current().userId())
                .orderByDesc(Feedback::getId)
                .last("LIMIT 50"));
        return ApiResponse.ok(rows.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("content", f.getContent());
            m.put("contact", f.getContact());
            m.put("status", f.getStatus());
            m.put("handleNote", f.getHandleNote());
            m.put("createTime", f.getCreateTime() == null ? null : f.getCreateTime().toString());
            return m;
        }).toList());
    }

    /** 撤回自己的反馈（批67 补口：限本人不限时，待处理/已处理均可，管理员兜底走管理端删除） */
    @DeleteMapping("/api/feedback/{id}")
    public ApiResponse<Void> withdraw(@PathVariable Long id) {
        Feedback f = feedbackMapper.selectById(id);
        if (f == null) {
            throw new BizException(404, "反馈不存在");
        }
        if (!f.getUserId().equals(AuthUtil.current().userId())) {
            throw new BizException(403, "只能撤回自己的反馈");
        }
        feedbackMapper.deleteById(id);
        return ApiResponse.ok();
    }
}
