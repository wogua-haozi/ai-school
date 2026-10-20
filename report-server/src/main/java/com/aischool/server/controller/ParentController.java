package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ContentItem;
import com.aischool.server.entity.Evaluation;
import com.aischool.server.entity.Moment;
import com.aischool.server.entity.MomentStudent;
import com.aischool.server.entity.ParentBinding;
import com.aischool.server.entity.Report;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.Term;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ContentItemMapper;
import com.aischool.server.mapper.EvaluationMapper;
import com.aischool.server.mapper.MomentMapper;
import com.aischool.server.mapper.MomentStudentMapper;
import com.aischool.server.mapper.ParentBindingMapper;
import com.aischool.server.mapper.ReportMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.TermMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.service.moment.MomentService;
import com.aischool.server.service.report.PdfStoreService;
import com.aischool.server.security.AuthUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 家长端（App 内 PARENT 角色分流进入）。
 * 白名单隔离：家长在 DataScopeService 落 default 403，全部教师/管理接口天然不可达，
 * 仅此处按 t_parent_binding 放行绑定孩子的只读信息；批63 成绩放开分数+等级（刻意无排名，见 /children/{id}/scores）。
 */
@RestController
@RequestMapping("/api/parent")
@RequiredArgsConstructor
public class ParentController {

    private final ParentBindingMapper bindingMapper;
    private final StudentMapper studentMapper;
    private final ClazzMapper clazzMapper;
    private final EvaluationMapper evaluationMapper;
    private final UserMapper userMapper;
    private final ContentItemMapper contentMapper;
    private final MomentMapper momentMapper;
    private final MomentStudentMapper momentStudentMapper;
    private final MomentService momentService;
    private final PdfStoreService pdfStore;
    private final com.aischool.server.service.conduct.ParentWalletService parentWalletService;
    private final ReportMapper reportMapper;
    private final TermMapper termMapper;
    private final com.aischool.server.service.score.ParentScoreService parentScoreService;

    private void checkParent() {
        if (!"PARENT".equals(AuthUtil.current().role())) {
            throw new BizException(403, "仅家长账号可访问");
        }
    }

    /** 我的孩子列表（一号可绑多名学生） */
    @GetMapping("/children")
    public ApiResponse<List<Map<String, Object>>> children() {
        checkParent();
        List<ParentBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, AuthUtil.current().userId())
                .orderByAsc(ParentBinding::getId));
        if (bindings.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        List<Long> studentIds = bindings.stream().map(ParentBinding::getStudentId).toList();
        Map<Long, Student> students = studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, s -> s));
        Map<Long, String> classNames = clazzMapper.selectBatchIds(students.values().stream()
                        .map(Student::getClassId).filter(c -> c != null).distinct().toList()).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName));
        return ApiResponse.ok(bindings.stream().map(b -> {
            Student s = students.get(b.getStudentId());
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("studentId", b.getStudentId());
            m.put("relation", b.getRelation());
            m.put("name", s == null ? "(学生已删除)" : s.getName());
            m.put("studentNo", s == null ? null : s.getStudentNo());
            m.put("gender", s == null ? null : s.getGender());
            m.put("className", s == null || s.getClassId() == null ? null : classNames.get(s.getClassId()));
            return m;
        }).toList());
    }

    /** 孩子最近评价（行为评价不含成绩；仅绑定孩子可查。批39⑦：家长侧只显示加分记录） */
    @GetMapping("/children/{studentId}/evaluations")
    public ApiResponse<List<Map<String, Object>>> evaluations(@PathVariable Long studentId,
            @RequestParam(defaultValue = "5") int limit) {
        checkParent();
        requireBound(studentId);
        List<Evaluation> rows = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getStudentId, studentId)
                .gt(Evaluation::getScore, 0)
                .orderByDesc(Evaluation::getEvalTime)
                .orderByDesc(Evaluation::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        // teacherId 可空：过滤后可能空列表，selectBatchIds(空) 生成 IN () 非法 SQL
        List<Long> teacherIds = rows.stream().map(Evaluation::getTeacherId)
                .filter(t -> t != null).distinct().toList();
        Map<Long, String> teacherNames = teacherIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(teacherIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName));
        return ApiResponse.ok(rows.stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("title", e.getTitle());
            m.put("score", e.getScore());
            m.put("remark", e.getRemark());
            m.put("evalTime", e.getEvalTime() == null ? null : e.getEvalTime().toString());
            m.put("teacherName", e.getTeacherId() == null ? null : teacherNames.get(e.getTeacherId()));
            return m;
        }).toList());
    }

    // ────────────────────────── 成长报告（批5 家长版：方案A 去成绩板块） ──────────────────────────

    /** 孩子最新家长版报告元信息（data=null 表示尚未生成；只认 parent_file_url，教师版文件不可达） */
    @GetMapping("/children/{studentId}/report")
    public ApiResponse<Map<String, Object>> latestReport(@PathVariable Long studentId) {
        checkParent();
        requireBound(studentId);
        Report report = latestParentReport(studentId);
        if (report == null) {
            return ApiResponse.ok(null);
        }
        Term term = termMapper.selectById(report.getTermId());
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("reportId", report.getId());
        m.put("studentId", report.getStudentId());
        m.put("termName", term == null ? null : term.getName());
        // 批26：学年/在校报告也走 parentFileUrl，前端据此显示正确标题
        m.put("scopeType", report.getScopeType() == null ? "TERM" : report.getScopeType());
        m.put("genTime", report.getGenTime() == null ? null : report.getGenTime().toString());
        return ApiResponse.ok(m);
    }

    /** 家长版报告 PDF（inline 预览 / attachment 下载；整体读入同步返回，同 ReportController 口径） */
    @GetMapping("/children/{studentId}/report/file")
    public ResponseEntity<byte[]> reportFile(@PathVariable Long studentId,
            @RequestParam(defaultValue = "inline") String disposition) throws IOException {
        checkParent();
        requireBound(studentId);
        Report report = latestParentReport(studentId);
        if (report == null) {
            throw new BizException(404, "报告尚未生成，请等待班主任生成后再查看");
        }
        byte[] bytes;
        try (InputStream in = pdfStore.download(report.getParentFileUrl())) {
            bytes = in.readAllBytes();
        }
        String fileName = "report-parent-" + studentId + "-" + report.getId() + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(bytes.length);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                ("download".equals(disposition) ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** 该生最新的已归档家长版报告（家长版渲染失败时 parent_file_url 为空，不出现于此） */
    private Report latestParentReport(Long studentId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<Report>()
                .eq(Report::getStudentId, studentId)
                .eq(Report::getStatus, "成功")
                .isNotNull(Report::getParentFileUrl)
                .orderByDesc(Report::getGenTime).orderByDesc(Report::getId)
                .last("LIMIT 1"));
    }

    // ───────── 批42：分期归档——往期报告按学期分组可见 ─────────

    /**
     * 孩子全部家长版报告，按学期分组（新→旧）。每格（学期×类型×期次）只显最新一份——
     * 重生成的旧版本留库不展示。期内排序：学期·期末 → 学期·期中 → 学年 → 在校。
     */
    @GetMapping("/children/{studentId}/report/list")
    public ApiResponse<List<Map<String, Object>>> reportList(@PathVariable Long studentId) {
        checkParent();
        requireBound(studentId);
        List<Report> rows = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getStudentId, studentId)
                .eq(Report::getStatus, "成功")
                .isNotNull(Report::getParentFileUrl)
                .orderByDesc(Report::getGenTime).orderByDesc(Report::getId));
        // 每格保留最新（rows 已按生成时间倒序 → 首见即最新）
        Map<String, Report> latest = new java.util.LinkedHashMap<>();
        for (Report r : rows) {
            String key = r.getTermId() + ":" + (r.getScopeType() == null ? "TERM" : r.getScopeType())
                    + ":" + ("MID".equals(r.getPeriod()) ? "MID" : "FINAL");
            latest.putIfAbsent(key, r);
        }
        Map<Long, Term> terms = latest.isEmpty() ? Map.of()
                : termMapper.selectBatchIds(latest.values().stream().map(Report::getTermId).distinct().toList())
                        .stream().collect(java.util.stream.Collectors.toMap(Term::getId, t -> t, (a, b) -> a));
        Map<Long, List<Map<String, Object>>> byTerm = new java.util.LinkedHashMap<>();
        for (Report r : latest.values()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("reportId", r.getId());
            m.put("scopeType", r.getScopeType() == null ? "TERM" : r.getScopeType());
            m.put("period", "MID".equals(r.getPeriod()) ? "MID" : "FINAL");
            m.put("genTime", r.getGenTime() == null ? null : r.getGenTime().toString());
            byTerm.computeIfAbsent(r.getTermId(), k -> new ArrayList<>()).add(m);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        byTerm.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        terms.getOrDefault(b.getKey(), new Term()).getId(),
                        terms.getOrDefault(a.getKey(), new Term()).getId()))
                .forEach(e -> {
                    e.getValue().sort(java.util.Comparator.comparingInt(m -> itemOrder(
                            String.valueOf(m.get("scopeType")), String.valueOf(m.get("period")))));
                    Map<String, Object> g = new java.util.LinkedHashMap<>();
                    g.put("termId", e.getKey());
                    Term t = terms.get(e.getKey());
                    g.put("termName", t == null ? "" : t.getName());
                    g.put("items", e.getValue());
                    groups.add(g);
                });
        return ApiResponse.ok(groups);
    }

    /** 指定往期报告的家长版 PDF（inline 预览 / attachment 下载；须属于绑定孩子） */
    @GetMapping("/children/{studentId}/report/{reportId}/file")
    public ResponseEntity<byte[]> reportFileOf(@PathVariable Long studentId, @PathVariable Long reportId,
            @RequestParam(defaultValue = "inline") String disposition) throws IOException {
        checkParent();
        requireBound(studentId);
        Report report = reportMapper.selectById(reportId);
        if (report == null || !studentId.equals(report.getStudentId())
                || report.getParentFileUrl() == null || !"成功".equals(report.getStatus())) {
            throw new BizException(404, "报告不存在或尚未生成");
        }
        byte[] bytes;
        try (InputStream in = pdfStore.download(report.getParentFileUrl())) {
            bytes = in.readAllBytes();
        }
        String fileName = "report-parent-" + studentId + "-T" + report.getTermId()
                + ("MID".equals(report.getPeriod()) ? "-期中" : "") + "-" + reportId + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(bytes.length);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                ("download".equals(disposition) ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** 期内排序权重：学期·期末 0 → 学期·期中 1 → 学年 2 → 在校 3 */
    private static int itemOrder(String scopeType, String period) {
        if ("YEAR".equals(scopeType)) {
            return 2;
        }
        if ("SCHOOL".equals(scopeType)) {
            return 3;
        }
        return "MID".equals(period) ? 1 : 0;
    }

    // ────────────────────────── 内容：通知公告 / 育儿课堂（批2） ──────────────────────────

    /** 已发布内容列表（全校 + 绑定孩子所在班级；按发布时间倒序，最多 50 条） */
    @GetMapping("/contents")
    public ApiResponse<List<Map<String, Object>>> contents(@RequestParam String type) {
        checkParent();
        if (!"NOTICE".equals(type) && !"PARENTING".equals(type)) {
            throw new BizException(400, "type 须为 NOTICE 或 PARENTING");
        }
        List<Long> classIds = boundClassIds();
        List<ContentItem> rows = contentMapper.selectList(new LambdaQueryWrapper<ContentItem>()
                .eq(ContentItem::getType, type)
                .eq(ContentItem::getStatus, 1)
                .and(q -> q.eq(ContentItem::getScope, "ALL")
                        .or().in(!classIds.isEmpty(), ContentItem::getClassId, classIds))
                .orderByDesc(ContentItem::getPublishTime)
                .orderByDesc(ContentItem::getId)
                .last("LIMIT 50"));
        Map<Long, String> classNames = classNamesOf(rows);
        return ApiResponse.ok(rows.stream().map(it -> contentRow(it, classNames, false)).toList());
    }

    /** 内容详情（仅已发布且范围可见；列表卡片点进全文阅读） */
    @GetMapping("/contents/{id}")
    public ApiResponse<Map<String, Object>> contentDetail(@PathVariable Long id) {
        checkParent();
        ContentItem it = contentMapper.selectById(id);
        if (it == null || it.getStatus() == null || it.getStatus() != 1) {
            throw new BizException(404, "内容不存在或未发布");
        }
        if ("CLASS".equals(it.getScope())
                && !boundClassIds().contains(it.getClassId())) {
            throw new BizException(403, "该内容未对您开放");
        }
        Map<Long, String> classNames = classNamesOf(List.of(it));
        return ApiResponse.ok(contentRow(it, classNames, true));
    }

    /** 绑定孩子所在班级（去重；无绑定=空列表） */
    private List<Long> boundClassIds() {
        List<ParentBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, AuthUtil.current().userId()));
        List<Long> studentIds = bindings.stream().map(ParentBinding::getStudentId).toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return studentMapper.selectBatchIds(studentIds).stream()
                .map(Student::getClassId).filter(c -> c != null).distinct().toList();
    }

    private Map<Long, String> classNamesOf(List<ContentItem> items) {
        List<Long> ids = items.stream().map(ContentItem::getClassId)
                .filter(c -> c != null).distinct().toList();
        return ids.isEmpty() ? Map.of() : clazzMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Clazz::getId, Clazz::getName));
    }

    private Map<String, Object> contentRow(ContentItem it, Map<Long, String> classNames, boolean full) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("type", it.getType());
        m.put("title", it.getTitle());
        m.put("coverUrl", it.getCoverUrl());
        m.put("videoUrl", it.getVideoUrl());
        m.put("content", full ? it.getContent() : brief(it.getContent()));
        m.put("scope", it.getScope());
        m.put("className", it.getClassId() == null ? null : classNames.get(it.getClassId()));
        m.put("publishTime", it.getPublishTime() == null ? null : it.getPublishTime().toString());
        return m;
    }

    /** 列表摘要：正文前 80 字（详情接口给全文） */
    private String brief(String content) {
        if (content == null || content.length() <= 80) {
            return content;
        }
        return content.substring(0, 80) + "…";
    }

    // ────────────────────────── 微光时刻（批2-3 方案A：仅进孩子成长档案） ──────────────────────────

    /** 家长上传微光：进该孩子成长档案（家长+班主任可见），不进班级公开墙、不进 AI 报告素材、免审核 */
    @PostMapping("/moment")
    public ApiResponse<Map<String, Object>> createMoment(@RequestParam Long studentId,
            @RequestParam(required = false) String note,
            @RequestParam("photo") MultipartFile photo) {
        checkParent();
        requireBound(studentId);
        Student s = studentMapper.selectById(studentId);
        if (s == null || s.getClassId() == null) {
            throw new BizException(404, "学生或所在班级不存在");
        }
        if (photo == null || photo.isEmpty()) {
            throw new BizException(400, "请先拍照或选择照片");
        }
        if (photo.getSize() > 10L * 1024 * 1024) {
            throw new BizException(400, "照片不能超过 10MB");
        }
        String original = photo.getOriginalFilename() == null ? "" : photo.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
        if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
            throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
        }
        if (note != null && note.length() > 500) {
            throw new BizException(400, "备注不能超过 500 字");
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (Exception e) {
            throw new BizException(400, "读取照片失败");
        }
        String objectName = "moment/" + s.getClassId() + "/" + java.util.UUID.randomUUID() + "." + ext;
        pdfStore.upload(objectName, new java.io.ByteArrayInputStream(bytes), bytes.length,
                photo.getContentType() == null ? "image/jpeg" : photo.getContentType());

        Moment m = new Moment();
        m.setTeacherId(AuthUtil.current().userId()); // 记录人=家长账号（assemble 以此取记录人姓名）
        m.setClassId(s.getClassId());
        m.setPhotoUrl(objectName);
        m.setSceneTag("亲子分享");
        m.setNote(note == null ? null : note.trim());
        m.setSource("PARENT");
        momentMapper.insert(m);
        MomentStudent ms = new MomentStudent();
        ms.setMomentId(m.getId());
        ms.setStudentId(studentId);
        momentStudentMapper.insert(ms);
        return ApiResponse.ok(Map.of("momentId", m.getId()));
    }

    /** 孩子的微光流（教师随手拍+家长上传都在，孩子成长档案的一部分；仅绑定孩子可查） */
    @GetMapping("/children/{studentId}/moments")
    public ApiResponse<List<Map<String, Object>>> childMoments(@PathVariable Long studentId,
            @RequestParam(defaultValue = "20") int limit) {
        checkParent();
        requireBound(studentId);
        List<Long> momentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getStudentId, studentId))
                .stream().map(MomentStudent::getMomentId).toList();
        if (momentIds.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        List<Moment> moments = momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                .in(Moment::getId, momentIds)
                .orderByDesc(Moment::getCreateTime).orderByDesc(Moment::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
        // assemble 返回 Map.of 不可变行，包可变副本以便补 own 标记
        long me = AuthUtil.current().userId();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> row : momentService.assemble(moments)) {
            Map<String, Object> r = new java.util.LinkedHashMap<>(row);
            // own=本人上传的家长微光（前端据此显示删除入口；删除权限仍由服务端校验）
            r.put("own", "PARENT".equals(r.get("source"))
                    && r.get("teacherId") instanceof Number n && n.longValue() == me);
            rows.add(r);
        }
        return ApiResponse.ok(rows);
    }

    /** 删除微光：仅本人上传的（家长删自己的；教师上传的家长不可删） */
    @DeleteMapping("/moment/{id}")
    public ApiResponse<Void> deleteMoment(@PathVariable Long id) {
        checkParent();
        Moment m = momentMapper.selectById(id);
        if (m == null) {
            throw new BizException(404, "微光记录不存在");
        }
        if (!"PARENT".equals(m.getSource()) || !m.getTeacherId().equals(AuthUtil.current().userId())) {
            throw new BizException(403, "仅可删除自己上传的微光");
        }
        momentMapper.deleteById(id);
        momentStudentMapper.delete(new LambdaQueryWrapper<MomentStudent>()
                .eq(MomentStudent::getMomentId, id));
        pdfStore.delete(m.getPhotoUrl());
        return ApiResponse.ok();
    }

    private void requireBound(Long studentId) {
        if (bindingMapper.selectCount(new LambdaQueryWrapper<ParentBinding>()
                .eq(ParentBinding::getParentUserId, AuthUtil.current().userId())
                .eq(ParentBinding::getStudentId, studentId)) == 0) {
            throw new BizException(403, "该学生未绑定当前家长账号");
        }
    }

    /** 孩子双账本（批3）：操行分余额+等级、扬长币余额、最近流水各 5 条 */
    @GetMapping("/children/{studentId}/wallet")
    public ApiResponse<Map<String, Object>> wallet(@PathVariable Long studentId) {
        checkParent();
        requireBound(studentId);
        return ApiResponse.ok(parentWalletService.walletOf(studentId));
    }

    // ────────────────────────── 批63：成绩（分数+等级，无排名） ──────────────────────────

    /** 孩子历次成绩：按考试倒序分组，每科分数+满分+等级（批63c 等级=校方上传）；白名单输出，无任何名次字段 */
    @GetMapping("/children/{studentId}/scores")
    public ApiResponse<Map<String, Object>> scores(@PathVariable Long studentId) {
        checkParent();
        requireBound(studentId);
        return ApiResponse.ok(Map.of("exams", parentScoreService.scoresOf(studentId)));
    }
}
