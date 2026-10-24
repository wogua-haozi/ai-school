package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Clazz;
import com.aischool.server.entity.ContentItem;
import com.aischool.server.entity.GradeBinding;
import com.aischool.server.entity.Grade;
import com.aischool.server.entity.Notification;
import com.aischool.server.entity.NoticeRead;
import com.aischool.server.entity.Teach;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.ClazzMapper;
import com.aischool.server.mapper.ContentItemMapper;
import com.aischool.server.mapper.GradeBindingMapper;
import com.aischool.server.mapper.GradeMapper;
import com.aischool.server.mapper.NotificationMapper;
import com.aischool.server.mapper.NoticeReadMapper;
import com.aischool.server.mapper.TeachMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.notify.NotificationService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内容封面预览（inline，登录即可——家长端列表缩略图/详情大图走这里）。
 * 批37 教师端内容接口（/notices：通知公告 NOTICE / 扬长课程 PARENTING，登录即可）。
 * 批66② 校园通知：全体教职工可在 App 发布（STAFF=全体教师 / GRADE=指定年级教师），
 * 发布即推消息中心；老师打开详情打已读回执，发布人/管理员可看回执统计；发布人可删自己的。
 * 高并发要点（家长端可达万人级）：流式转发不整包进堆；ETag=objectName（封面替换即变）+
 * Cache-Control 10 分钟——家长端重复浏览命中缓存/304，MinIO 与本服务带宽近似归零。
 */
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    /** 批66② GRADE 范围全见的角色（与 DataScopeService.visibleClassIds 不受限口径一致） */
    private static final Set<String> UNRESTRICTED_ROLES = Set.of("ADMIN", "LEADER", "SECRETARY", "DIRECTOR", "DORM");

    private final ContentItemMapper contentMapper;
    private final ClazzMapper clazzMapper;
    private final GradeMapper gradeMapper;
    private final TeachMapper teachMapper;
    private final GradeBindingMapper gradeBindingMapper;
    private final UserMapper userMapper;
    private final NoticeReadMapper readMapper;
    private final NotificationMapper notificationMapper;
    private final DataScopeService dataScope;
    private final NotificationService notificationService;
    private final PdfStoreService pdfStore;

    @GetMapping("/file/{id}")
    public ResponseEntity<InputStreamResource> file(@PathVariable Long id) {
        ContentItem it = contentMapper.selectById(id);
        if (it == null || it.getCoverUrl() == null) {
            throw new BizException(404, "封面不存在");
        }
        InputStream in = pdfStore.download(it.getCoverUrl());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeOf(it.getCoverUrl())))
                // If-None-Match 命中时 Spring 自动回 304（body 已吐流也不重发）
                .eTag("\"" + it.getCoverUrl() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new InputStreamResource(in));
    }

    // ────────────────────────── 通知公告 / 扬长课程（批37 教师端入口） ──────────────────────────

    /** 已发布内容（教师/领导 App 内查看）：ALL=全校公告 + STAFF/GRADE=校园通知（批66② 按数据面过滤） */
    @GetMapping("/notices")
    public ApiResponse<List<Map<String, Object>>> notices(@RequestParam(defaultValue = "NOTICE") String type) {
        UserPrincipal user = requireStaff();
        if (!"NOTICE".equals(type) && !"PARENTING".equals(type)) {
            throw new BizException(400, "type 须为 NOTICE 或 PARENTING");
        }
        List<Long> myGrades = myGradeIds(user);
        LambdaQueryWrapper<ContentItem> w = new LambdaQueryWrapper<ContentItem>()
                .eq(ContentItem::getType, type)
                .eq(ContentItem::getStatus, 1);
        if (myGrades == null) { // 领导层/管理员：STAFF/GRADE 全见
            w.in(ContentItem::getScope, "ALL", ContentItem.SCOPE_STAFF, ContentItem.SCOPE_GRADE);
        } else if (myGrades.isEmpty()) { // 无班级数据面的教职工：只看 ALL/STAFF
            w.in(ContentItem::getScope, "ALL", ContentItem.SCOPE_STAFF);
        } else {
            w.and(q -> q.in(ContentItem::getScope, "ALL", ContentItem.SCOPE_STAFF)
                    .or(g -> g.eq(ContentItem::getScope, ContentItem.SCOPE_GRADE)
                            .in(ContentItem::getGradeId, myGrades)));
        }
        List<ContentItem> rows = contentMapper.selectList(w
                .orderByDesc(ContentItem::getPublishTime)
                .orderByDesc(ContentItem::getId)
                .last("LIMIT 100"));
        Map<Long, String> gradeNames = gradeNamesOf(rows);
        return ApiResponse.ok(rows.stream().map(it -> noticeRow(it, false, gradeNames, user.userId())).toList());
    }

    /** 内容详情（全文；仅已发布且本人可见） */
    @GetMapping("/notices/{id}")
    public ApiResponse<Map<String, Object>> noticeDetail(@PathVariable Long id) {
        UserPrincipal user = requireStaff();
        ContentItem it = contentMapper.selectById(id);
        if (it == null || it.getStatus() == null || it.getStatus() != 1
                || !canSee(user, it)
                || (!"NOTICE".equals(it.getType()) && !"PARENTING".equals(it.getType()))) {
            throw new BizException(404, "内容不存在或未发布");
        }
        return ApiResponse.ok(noticeRow(it, true, gradeNamesOf(List.of(it)), user.userId()));
    }

    // ────────────────────────── 批66② 校园通知：App 发布 / 删除 / 已读回执 ──────────────────────────

    /** 发布校园通知（全体教职工；范围=全体教师或指定年级），发布即推消息中心给可见老师 */
    @PostMapping("/notice")
    public ApiResponse<Map<String, Object>> publish(@Validated @RequestBody PublishReq req) {
        UserPrincipal user = requireStaff();
        String title = req.getTitle() == null ? "" : req.getTitle().trim();
        if (title.isEmpty() || title.length() > 200) {
            throw new BizException(400, "标题不能为空且最多 200 字");
        }
        String scope = ContentItem.SCOPE_GRADE.equals(req.getScope())
                ? ContentItem.SCOPE_GRADE : ContentItem.SCOPE_STAFF;
        Long gradeId = null;
        if (ContentItem.SCOPE_GRADE.equals(scope)) {
            if (req.getGradeId() == null) {
                throw new BizException(400, "指定年级时须选择年级");
            }
            if (gradeMapper.selectById(req.getGradeId()) == null) {
                throw new BizException(404, "年级不存在");
            }
            gradeId = req.getGradeId();
        }
        ContentItem it = new ContentItem();
        it.setType(ContentItem.TYPE_NOTICE);
        it.setTitle(title);
        it.setScope(scope);
        it.setGradeId(gradeId);
        it.setContent(req.getContent() == null || req.getContent().isBlank() ? null : req.getContent().trim());
        it.setStatus(1);
        it.setPublishTime(LocalDateTime.now());
        it.setCreateBy(user.userId());
        contentMapper.insert(it);
        markRead(it.getId(), user.userId()); // 发布人自身计已读
        String publisherName = userMapper.selectById(user.userId()).getRealName();
        List<Long> audience = staffAudience(it);
        audience = new ArrayList<>(audience);
        audience.remove(user.userId());
        notificationService.campusNotice(audience, title, publisherName, it.getId());
        return ApiResponse.ok(Map.of("id", it.getId()));
    }

    /** 删除校园通知（发布人删自己的；管理员删任意；连带清封面对象与回执记录） */
    @DeleteMapping("/notice/{id}")
    public ApiResponse<Void> deleteNotice(@PathVariable Long id) {
        UserPrincipal user = requireStaff();
        ContentItem it = requireCampusNotice(id);
        if (!"ADMIN".equals(user.role()) && !user.userId().equals(it.getCreateBy())) {
            throw new BizException(403, "仅发布人或管理员可删除");
        }
        if (it.getCoverUrl() != null && !it.getCoverUrl().isBlank()) {
            pdfStore.delete(it.getCoverUrl());
        }
        readMapper.delete(new LambdaQueryWrapper<NoticeRead>().eq(NoticeRead::getNoticeId, id));
        // 连带清理推送记录（防死链）与回执
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getLink, "/notices/" + id));
        contentMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /** 老师打开校园通知详情：打已读回执（幂等） */
    @PostMapping("/notice/{id}/read")
    public ApiResponse<Void> readMark(@PathVariable Long id) {
        UserPrincipal user = requireStaff();
        ContentItem it = requireCampusNotice(id);
        if (it.getStatus() == null || it.getStatus() != 1 || !canSee(user, it)) {
            throw new BizException(404, "通知不存在或不可见");
        }
        markRead(id, user.userId());
        return ApiResponse.ok();
    }

    /** 回执统计（发布人/管理员）：应读=该通知可见的在职教职工；read=已读；unread=未读名单 */
    @GetMapping("/notice/{id}/read-stats")
    public ApiResponse<Map<String, Object>> readStats(@PathVariable Long id) {
        UserPrincipal user = requireStaff();
        ContentItem it = requireCampusNotice(id);
        if (!"ADMIN".equals(user.role()) && !user.userId().equals(it.getCreateBy())) {
            throw new BizException(403, "仅发布人或管理员可看回执");
        }
        List<Long> audience = staffAudience(it);
        Set<Long> readIds = readMapper.selectList(new LambdaQueryWrapper<NoticeRead>()
                        .eq(NoticeRead::getNoticeId, id))
                .stream().map(NoticeRead::getUserId).collect(Collectors.toSet());
        Map<Long, String> names = audience.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(audience).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        List<Map<String, Object>> unread = new ArrayList<>();
        long readCount = 0;
        for (Long uid : audience) {
            if (readIds.contains(uid)) {
                readCount++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", names.getOrDefault(uid, ""));
            unread.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", it.getTitle());
        out.put("total", audience.size());
        out.put("read", readCount);
        out.put("unread", unread);
        return ApiResponse.ok(out);
    }

    // ────────────────────────── 内部 ──────────────────────────

    /** 列表给 80 字摘要（同 ParentController.brief 口径），详情给全文 */
    private Map<String, Object> noticeRow(ContentItem it, boolean full, Map<Long, String> gradeNames, Long viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("type", it.getType());
        m.put("title", it.getTitle());
        m.put("coverUrl", it.getCoverUrl());
        m.put("videoUrl", it.getVideoUrl());
        String content = it.getContent();
        m.put("content", full || content == null || content.length() <= 80
                ? content : content.substring(0, 80) + "…");
        m.put("scope", it.getScope());
        m.put("gradeId", it.getGradeId());
        m.put("gradeName", ContentItem.SCOPE_GRADE.equals(it.getScope())
                ? gradeNames.getOrDefault(it.getGradeId(), "") : null);
        m.put("publishTime", it.getPublishTime() == null ? null : it.getPublishTime().toString());
        m.put("mine", viewerId != null && viewerId.equals(it.getCreateBy()));
        return m;
    }

    /** 本人有数据面的年级（null=不受限，领导层/管理员；空=无班级数据面） */
    private List<Long> myGradeIds(UserPrincipal user) {
        List<Long> classIds = dataScope.visibleClassIds(user);
        if (classIds == null) {
            return null;
        }
        if (classIds.isEmpty()) {
            return List.of();
        }
        return clazzMapper.selectBatchIds(classIds).stream()
                .map(Clazz::getGradeId).filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** 单条可见性：ALL/STAFF 全员；GRADE=不受限角色或该年级有数据面 */
    private boolean canSee(UserPrincipal user, ContentItem it) {
        if ("ALL".equals(it.getScope()) || ContentItem.SCOPE_STAFF.equals(it.getScope())) {
            return true;
        }
        if (ContentItem.SCOPE_GRADE.equals(it.getScope())) {
            List<Long> g = myGradeIds(user);
            return g == null || (it.getGradeId() != null && g.contains(it.getGradeId()));
        }
        return false; // CLASS 面向家长，不走教师侧
    }

    /**
     * 校园通知应读名单（=推送名单）：
     * STAFF=全部在职教职工；GRADE=不受限角色 ∪ 该年级任课/班主任/级长绑定（与可见口径一致，回执不虚报）。
     * PARENT/GUARD 天然不在（GUARD 另被 JwtAuthFilter 白名单挡在 /api/content 之外）。
     */
    private List<Long> staffAudience(ContentItem it) {
        List<User> staff = userMapper.selectList(new LambdaQueryWrapper<User>()
                .notIn(User::getRole, "PARENT", "GUARD").eq(User::getStatus, 1));
        if (ContentItem.SCOPE_STAFF.equals(it.getScope())) {
            return staff.stream().map(User::getId).toList();
        }
        Set<Long> touching = new HashSet<>();
        List<Long> gradeClassIds = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                        .eq(Clazz::getGradeId, it.getGradeId()))
                .stream().map(Clazz::getId).toList();
        if (!gradeClassIds.isEmpty()) {
            teachMapper.selectList(new LambdaQueryWrapper<Teach>().in(Teach::getClassId, gradeClassIds))
                    .forEach(t -> touching.add(t.getTeacherId()));
            clazzMapper.selectBatchIds(gradeClassIds).stream()
                    .map(Clazz::getHeadTeacherId).filter(java.util.Objects::nonNull).forEach(touching::add);
        }
        gradeBindingMapper.selectList(new LambdaQueryWrapper<GradeBinding>()
                        .eq(GradeBinding::getGradeId, it.getGradeId())
                        .eq(GradeBinding::getDuty, GradeBinding.DUTY_GRADE_LEADER))
                .forEach(gb -> touching.add(gb.getUserId()));
        Set<Long> ids = new LinkedHashSet<>();
        for (User u : staff) {
            if (UNRESTRICTED_ROLES.contains(u.getRole()) || touching.contains(u.getId())) {
                ids.add(u.getId());
            }
        }
        return List.copyOf(ids);
    }

    private void markRead(Long noticeId, Long userId) {
        NoticeRead r = new NoticeRead();
        r.setNoticeId(noticeId);
        r.setUserId(userId);
        try {
            readMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 已读过：幂等
        }
    }

    private UserPrincipal requireStaff() {
        UserPrincipal user = AuthUtil.current();
        if ("PARENT".equals(user.role())) {
            throw new BizException(403, "家长请使用家长端通知");
        }
        return user;
    }

    private ContentItem requireCampusNotice(Long id) {
        ContentItem it = contentMapper.selectById(id);
        if (it == null || !ContentItem.TYPE_NOTICE.equals(it.getType())
                || (!ContentItem.SCOPE_STAFF.equals(it.getScope()) && !ContentItem.SCOPE_GRADE.equals(it.getScope()))) {
            throw new BizException(404, "校园通知不存在");
        }
        return it;
    }

    private Map<Long, String> gradeNamesOf(List<ContentItem> items) {
        List<Long> ids = items.stream().map(ContentItem::getGradeId)
                .filter(g -> g != null).distinct().toList();
        return ids.isEmpty() ? Map.of() : gradeMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
    }

    private String contentTypeOf(String objectName) {
        String ext = objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ext.equals("png") ? "image/png" : "image/jpeg";
    }

    /** 批66② App 发布入参 */
    @Data
    public static class PublishReq {
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多 200 字")
        private String title;
        private String content;
        /** STAFF=全体教师（默认）/ GRADE=指定年级 */
        private String scope;
        private Long gradeId;
    }
}
