package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Activity;
import com.aischool.server.entity.ActivitySignup;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.Student;
import com.aischool.server.mapper.ActivityMapper;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ActivitySignupMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.coin.CoinLedgerService;
import com.aischool.server.service.excel.ExcelActivityHelper;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 扬长课程（原活动管理，批40a 更名/批40e 七类改造）：课程 CRUD（管理员）+ 参与记录，获奖可附扬长币入账；
 *  批73b 选修课跨班：参与面向全校学生开放给全体教师（不再限本班/任教班） */
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
    private final ClazzMapper clazzMapper;
    private final DataScopeService dataScope;
    private final CoinLedgerService coinLedger;
    private final PdfStoreService pdfStore;
    private final ExcelActivityHelper excelActivity;

    @Data
    public static class ActivityReq {
        @NotBlank(message = "title 不能为空")
        private String title;
        private String type;
        private LocalDateTime startTime;
        private String place;
        /** 批72d：课程视频外链（腾讯视频/B站等，点击跳转观看） */
        private String videoUrl;
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
        a.setVideoUrl(videoLink(req.getVideoUrl()));
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
                .set(Activity::getVideoUrl, videoLink(req.getVideoUrl()))
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

    /** 批72d：课程视频外链维护——老师/领导层/管理员可设可清（家长只读，观看走外链平台播放器） */
    @PutMapping("/{id}/video")
    public ApiResponse<Void> setVideo(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if ("PARENT".equals(AuthUtil.current().role())) {
            throw new BizException(403, "家长仅可观看课程视频");
        }
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "课程不存在");
        }
        // 空值=清除：updateById 会忽略 null 字段，须 UpdateWrapper 显式置空
        activityMapper.update(null, new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, id)
                .set(Activity::getVideoUrl, videoLink(body.get("videoUrl"))));
        return ApiResponse.ok(null);
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
        checkSignupStudent(req.getStudentId());
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
        checkSignupStudent(su.getStudentId());
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
        checkSignupStudent(su.getStudentId());
        signupMapper.deleteById(signupId);
        coinLedger.reverse(su.getStudentId(), "活动", signupId);
        return ApiResponse.ok();
    }

    // ───────────────── 批72：Excel 批量导入参与 ─────────────────

    /** 下载参与导入模板（单班名册预填；权限同录参与） */
    @GetMapping("/{id}/import-template")
    public ResponseEntity<byte[]> importTemplate(@PathVariable Long id, @RequestParam Long classId) {
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        dataScope.checkClassOperable(AuthUtil.current(), classId);
        List<Student> roster = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClassId, classId).orderByAsc(Student::getStudentNo));
        byte[] bytes = excelActivity.template(roster);
        String filename = URLEncoder.encode("参与导入模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    /** Excel 批量导入参与（先选课后导入）：学号须为本校在读学号（批73b 跨班放开，防呆改全校匹配）；
     *  已录/非法值逐行 skip 列原因；币入账口径同录参与（币>0 须奖项，入账日期=课程时间） */
    @PostMapping("/{id}/import")
    public ApiResponse<Map<String, Object>> importSignups(@PathVariable Long id,
                                                          @RequestParam("file") MultipartFile file) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(404, "活动不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择 Excel 文件");
        }
        List<ExcelActivityHelper.SignupRow> parsed;
        try (InputStream in = file.getInputStream()) {
            parsed = excelActivity.read(in);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取文件失败: " + e.getMessage());
        }
        Map<String, Student> byNo = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                        .eq(Student::getStatus, "在读")).stream()
                .collect(Collectors.toMap(Student::getStudentNo, Function.identity(), (a, b) -> a));
        List<String> foreignNos = new ArrayList<>();
        for (ExcelActivityHelper.SignupRow r : parsed) {
            if (!byNo.containsKey(r.studentNo())) {
                foreignNos.add(r.studentNo() + (r.name().isBlank() ? "" : "(" + r.name() + ")"));
            }
        }
        if (!foreignNos.isEmpty()) {
            throw new BizException(400, "以下学号不是本校在读学号，为防误导整单已拒绝导入: " + String.join("、", foreignNos));
        }
        // 同课同生只录一次：库里已有的与本次先入的都算已录
        Set<Long> existed = signupMapper.selectList(new LambdaQueryWrapper<ActivitySignup>()
                        .eq(ActivitySignup::getActivityId, id)).stream()
                .map(ActivitySignup::getStudentId).collect(Collectors.toSet());
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<ActivitySignup> toInsert = new ArrayList<>();
        List<BigDecimal> coins = new ArrayList<>();
        for (ExcelActivityHelper.SignupRow r : parsed) {
            Student st = byNo.get(r.studentNo());
            String reason = rowReason(r, existed.contains(st.getId()));
            if (reason != null) {
                skipped.add(Map.of("row", r.rowNum(), "studentNo", r.studentNo(), "name", r.name(), "reason", reason));
                continue;
            }
            ActivitySignup su = new ActivitySignup();
            su.setActivityId(id);
            su.setStudentId(st.getId());
            su.setSignupTime(LocalDateTime.now());
            if (checkinOf(r.checkin())) {
                su.setCheckinTime(LocalDateTime.now());
            }
            su.setAward(r.award().isBlank() ? null : r.award());
            su.setPerformance(r.performance().isBlank() ? null : r.performance());
            toInsert.add(su);
            coins.add(r.coin().isBlank() ? null : new BigDecimal(r.coin()));
            existed.add(st.getId());
        }
        LocalDate date = activity.getStartTime() == null ? null : activity.getStartTime().toLocalDate();
        for (int i = 0; i < toInsert.size(); i++) {
            signupMapper.insert(toInsert.get(i));
            BigDecimal coin = coins.get(i);
            if (coin != null && coin.compareTo(BigDecimal.ZERO) > 0) {
                coinLedger.income(toInsert.get(i).getStudentId(), date, "活动", toInsert.get(i).getId(),
                        "活动-" + activity.getTitle(), coin);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", toInsert.size());
        result.put("skipped", skipped);
        return ApiResponse.ok(result);
    }

    /** 行校验：null=可入；否则为 skip 原因 */
    private String rowReason(ExcelActivityHelper.SignupRow r, boolean existed) {
        if (existed) {
            return "该生已有本课参与记录";
        }
        if (!r.checkin().isBlank() && !checkinOf(r.checkin()) && !"否".equals(r.checkin())) {
            return "签到列应填「是/否」，收到: " + r.checkin();
        }
        if (r.award().length() > 128) {
            return "奖项超过 128 字";
        }
        if (r.performance().length() > 255) {
            return "表现超过 255 字";
        }
        if (!r.coin().isBlank()) {
            BigDecimal coin;
            try {
                coin = new BigDecimal(r.coin());
            } catch (NumberFormatException e) {
                return "扬长币不是数字: " + r.coin();
            }
            if (coin.compareTo(BigDecimal.ZERO) < 0) {
                return "扬长币不能为负";
            }
            if (coin.compareTo(BigDecimal.ZERO) > 0 && r.award().isBlank()) {
                return "获奖才能附扬长币，请填奖项";
            }
        }
        return null;
    }

    /** 签到列取值：「是/√/true」→ 签到，其余（含空、「否」）→ 不签到 */
    private boolean checkinOf(String v) {
        return "是".equals(v) || "√".equals(v) || "true".equalsIgnoreCase(v);
    }

    // ───────────────── 批72d：课程视频外链（在线播放对服务器压力过大，甲方拍板存外链点击跳转） ─────────────────

    /** 外链宽松校验：非空时须 http(s):// 开头且 ≤500 字 */
    private String videoLink(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException(400, "视频链接须以 http:// 或 https:// 开头");
        }
        if (u.length() > 500) {
            throw new BizException(400, "视频链接过长");
        }
        return u;
    }

    // ───────────────── 权限 ─────────────────

    /** 写：管理员或该班班主任（与 AiController.checkWritable 同构） */
    /** 批73b 跨班：录/改/删参与只要求学生在库（选修课面向全体学生），不再校验本班数据面 */
    private void checkSignupStudent(Long studentId) {
        if (studentMapper.selectById(studentId) == null) {
            throw new BizException(404, "学生不存在");
        }
    }

    /** 批73b 跨班选人：全校在读学生搜索（姓名模糊/学号前缀，限 20 条），录参与下拉用 */
    @GetMapping("/{id}/students")
    public ApiResponse<List<Map<String, Object>>> searchStudents(@PathVariable Long id,
                                                                 @RequestParam String keyword) {
        if (activityMapper.selectById(id) == null) {
            throw new BizException(404, "活动不存在");
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        List<Student> list = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getStatus, "在读")
                .and(w -> w.like(Student::getName, kw).or().likeRight(Student::getStudentNo, kw))
                .orderByAsc(Student::getStudentNo)
                .last("LIMIT 20"));
        Map<Long, String> clsNames = clazzMapper.selectList(null).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName));
        return ApiResponse.ok(list.stream().map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", s.getId());
            r.put("studentNo", s.getStudentNo());
            r.put("name", s.getName());
            r.put("className", clsNames.getOrDefault(s.getClassId(), ""));
            return r;
        }).toList());
    }

    private String contentTypeOf(String objectName) {
        String ext = objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
