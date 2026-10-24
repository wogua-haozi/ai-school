package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Activity;
import com.aischool.server.entity.ActivitySignup;
import com.aischool.server.entity.Student;
import com.aischool.server.mapper.ActivityMapper;
import com.aischool.server.mapper.ActivitySignupMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.coin.CoinLedgerService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 扬长课程（原活动管理，批40a 更名/批40e 七类改造）：课程 CRUD（管理员）+ 参与记录（管理员/本班班主任），获奖可附扬长币入账 */
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    /** 批40e：扬长课程七类（校方拍板固定；「其他」收纳临时性活动=原活动管理用法） */
    public static final List<String> CATEGORIES = List.of(
            "德育课程", "扬长选修", "体艺特训队", "跨学科学习", "做中学", "社会实践", "其他");

    private final ActivityMapper activityMapper;
    private final ActivitySignupMapper signupMapper;
    private final StudentMapper studentMapper;
    private final DataScopeService dataScope;
    private final CoinLedgerService coinLedger;
    private final PdfStoreService pdfStore;

    @Data
    public static class ActivityReq {
        @NotBlank(message = "title 不能为空")
        private String title;
        private String type;
        private LocalDateTime startTime;
        private String place;
        private String intro;
    }

    /** 批40e：课程类别固定七类 */
    private void checkCategory(String type) {
        if (type == null || !CATEGORIES.contains(type)) {
            throw new BizException(400, "课程类别必须是：" + String.join("/", CATEGORIES));
        }
    }

    @Data
    public static class SignupReq {
        @NotNull(message = "studentId 不能为空")
        private Long studentId;
        /** 报名时即签到 */
        private Boolean checkin;
        private String award;
        private String performance;
        /** 扬长币（>0 须填 award，入账一次） */
        private BigDecimal coin;
    }

    @Data
    public static class SignupEditReq {
        private String award;
        private String performance;
        private Boolean checkin;
    }

    /** 活动列表（start_time 倒序） */
    @GetMapping("/list")
    public ApiResponse<List<Activity>> list() {
        return ApiResponse.ok(activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .orderByDesc(Activity::getStartTime)));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Validated @RequestBody ActivityReq req) {
        var user = AuthUtil.current();
        if (!"ADMIN".equals(user.role())) {
            throw new BizException(403, "只有管理员可管理活动");
        }
        checkCategory(req.getType());
        Activity a = new Activity();
        a.setTitle(req.getTitle());
        a.setType(req.getType());
        a.setStartTime(req.getStartTime());
        a.setPlace(req.getPlace());
        a.setIntro(req.getIntro());
        a.setCreatorId(user.userId());
        activityMapper.insert(a);
        return ApiResponse.ok(Map.of("activityId", a.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody ActivityReq req) {
        if (!"ADMIN".equals(AuthUtil.current().role())) {
            throw new BizException(403, "只有管理员可管理活动");
        }
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        checkCategory(req.getType());
        activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, id)
                .set(Activity::getTitle, req.getTitle())
                .set(Activity::getType, req.getType())
                .set(Activity::getStartTime, req.getStartTime())
                .set(Activity::getPlace, req.getPlace())
                .set(Activity::getIntro, req.getIntro()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!"ADMIN".equals(AuthUtil.current().role())) {
            throw new BizException(403, "只有管理员可管理活动");
        }
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        if (signupMapper.selectCount(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getActivityId, id)) > 0) {
            throw new BizException(400, "该活动已有参与记录（含扬长币入账），不可删除");
        }
        Activity a = activityMapper.selectById(id);
        if (a != null && a.getCoverUrl() != null && !a.getCoverUrl().isBlank()) {
            pdfStore.delete(a.getCoverUrl());
        }
        activityMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /** 上传活动封面（管理员，multipart，jpg/jpeg/png ≤5MB），重复上传覆盖 */
    @PostMapping("/{id}/cover")
    public ApiResponse<Map<String, Object>> uploadCover(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file) {
        if (!"ADMIN".equals(AuthUtil.current().role())) {
            throw new BizException(403, "只有管理员可管理活动");
        }
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择封面图片");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(400, "封面图片不能超过 5MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
            throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(400, "读取上传文件失败");
        }
        String contentType = file.getContentType() == null ? "image/jpeg" : file.getContentType();
        String objectName = "activity/" + id + "/" + UUID.randomUUID() + "." + ext;
        // 批67 C7：覆盖上传先删旧封面对象，防 MinIO 孤儿（删除活动时已有同款清理）
        Activity old = activityMapper.selectById(id);
        if (old != null && old.getCoverUrl() != null && !old.getCoverUrl().isBlank()) {
            pdfStore.delete(old.getCoverUrl());
        }
        pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, contentType);
        activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, id).set(Activity::getCoverUrl, objectName));
        return ApiResponse.ok(Map.of("coverUrl", objectName));
    }

    /** 封面图（任意登录可看，列表缩略图用） */
    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable Long id) throws IOException {
        Activity a = activityMapper.selectById(id);
        if (a == null || a.getCoverUrl() == null || a.getCoverUrl().isBlank()) {
            throw new BizException(404, "封面未上传");
        }
        byte[] bytes;
        try (InputStream in = pdfStore.download(a.getCoverUrl())) {
            bytes = in.readAllBytes();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentTypeOf(a.getCoverUrl())));
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** 某活动的参与记录（按数据权限过滤，仅见本班/可见班学生） */
    @GetMapping("/{id}/signups")
    public ApiResponse<List<Map<String, Object>>> signups(@PathVariable Long id) {
        var user = AuthUtil.current();
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        List<ActivitySignup> signups = signupMapper.selectList(new LambdaQueryWrapper<ActivitySignup>()
                .eq(ActivitySignup::getActivityId, id)
                .orderByDesc(ActivitySignup::getSignupTime));
        List<Long> sids = signups.stream().map(ActivitySignup::getStudentId).toList();
        Map<Long, Student> students = sids.isEmpty() ? Map.of()
                : studentMapper.selectBatchIds(sids).stream()
                        .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<Long> visible = dataScope.visibleClassIds(user);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActivitySignup su : signups) {
            Student st = students.get(su.getStudentId());
            if (st == null || (visible != null && !visible.contains(st.getClassId()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("signupId", su.getId());
            row.put("studentId", st.getId());
            row.put("studentName", st.getName());
            row.put("classId", st.getClassId());
            row.put("signupTime", su.getSignupTime());
            row.put("checkinTime", su.getCheckinTime());
            row.put("award", su.getAward());
            row.put("performance", su.getPerformance());
            rows.add(row);
        }
        return ApiResponse.ok(rows);
    }

    /** 录参与（报名/签到/获奖/表现），获奖可附扬长币；币只在新增时入账一次 */
    @PostMapping("/{id}/signup")
    public ApiResponse<Map<String, Object>> addSignup(@PathVariable Long id,
                                                      @Validated @RequestBody SignupReq req) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(404, "活动不存在");
        }
        checkWritable(req.getStudentId());
        boolean withCoin = req.getCoin() != null && req.getCoin().compareTo(BigDecimal.ZERO) > 0;
        if (withCoin && (req.getAward() == null || req.getAward().isBlank())) {
            throw new BizException(400, "获奖才能附扬长币，请先填奖项");
        }
        ActivitySignup su = new ActivitySignup();
        su.setActivityId(id);
        su.setStudentId(req.getStudentId());
        su.setSignupTime(LocalDateTime.now());
        if (Boolean.TRUE.equals(req.getCheckin())) {
            su.setCheckinTime(LocalDateTime.now());
        }
        su.setAward(req.getAward());
        su.setPerformance(req.getPerformance());
        signupMapper.insert(su);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("signupId", su.getId());
        data.put("termId", null);
        if (withCoin) {
            LocalDate date = activity.getStartTime() == null ? null : activity.getStartTime().toLocalDate();
            data.put("termId", coinLedger.income(req.getStudentId(), date, "活动", su.getId(),
                    "活动-" + activity.getTitle(), req.getCoin()));
        }
        return ApiResponse.ok(data);
    }

    /** 改参与记录（只动签到/奖项/表现；扬长币改动请删除重录） */
    @PutMapping("/{id}/signup/{signupId}")
    public ApiResponse<Void> updateSignup(@PathVariable Long id, @PathVariable Long signupId,
                                          @RequestBody SignupEditReq req) {
        ActivitySignup su = signupMapper.selectById(signupId);
        if (su == null || !su.getActivityId().equals(id)) {
            throw new BizException(404, "参与记录不存在");
        }
        checkWritable(su.getStudentId());
        signupMapper.update(null, new LambdaUpdateWrapper<ActivitySignup>()
                .eq(ActivitySignup::getId, signupId)
                .set(ActivitySignup::getAward, req.getAward())
                .set(ActivitySignup::getPerformance, req.getPerformance())
                .set(ActivitySignup::getCheckinTime, Boolean.TRUE.equals(req.getCheckin())
                        ? LocalDateTime.now() : null));
        return ApiResponse.ok();
    }

    /** 批40e 撤回普适：删除参与记录（撤回误录）；带扬长币入账的自动冲正（负流水留痕+账户扣回） */
    @DeleteMapping("/{id}/signup/{signupId}")
    public ApiResponse<Void> deleteSignup(@PathVariable Long id, @PathVariable Long signupId) {
        ActivitySignup su = signupMapper.selectById(signupId);
        if (su == null || !su.getActivityId().equals(id)) {
            throw new BizException(404, "参与记录不存在");
        }
        checkWritable(su.getStudentId());
        signupMapper.deleteById(signupId);
        coinLedger.reverse(su.getStudentId(), "活动", signupId);
        return ApiResponse.ok();
    }

    // ───────────────── 权限 ─────────────────

    /** 写：管理员或该班班主任（与 AiController.checkWritable 同构） */
    private void checkWritable(Long studentId) {
        var user = AuthUtil.current();
        Student student = dataScope.checkStudentAccess(user, studentId);
        if (!"ADMIN".equals(user.role())) {
            dataScope.checkClassOperable(user, student.getClassId());
        }
    }

    private String contentTypeOf(String objectName) {
        String ext = objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
