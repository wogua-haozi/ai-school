package com.aischool.server.service.moment;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Moment;
import com.aischool.server.entity.MomentStudent;
import com.aischool.server.entity.MomentTag;
import com.aischool.server.entity.Student;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.MomentMapper;
import com.aischool.server.mapper.MomentStudentMapper;
import com.aischool.server.mapper.MomentTagMapper;
import com.aischool.server.mapper.StudentMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.auth.DataScopeService;
import com.aischool.server.service.coin.CoinLedgerService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 微光时刻：教师随手拍 → MinIO → 关联多名学生。
 * 露出：班级页「本周微光」轮播 / 学生详情「TA的闪光时刻」/ 成长记录流（FeedService 混排）。
 */
@Service
@RequiredArgsConstructor
public class MomentService {

    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final MomentMapper momentMapper;
    private final MomentStudentMapper momentStudentMapper;
    private final MomentTagMapper momentTagMapper;
    private final StudentMapper studentMapper;
    private final UserMapper userMapper;
    private final DataScopeService dataScope;
    private final PdfStoreService pdfStore;
    private final CoinLedgerService coinLedger;

    /** 创建一条微光（批62 多图）：照片存 MinIO（moment/{classId}/{uuid}.{ext}，1~9 张，
        photo_url 逗号分隔）+ 主表 + 学生关联 */
    @Transactional
    public Map<String, Object> create(UserPrincipal user, Long classId, List<Long> studentIds,
                                      String sceneTag, String note, List<MultipartFile> photos) {
        List<MultipartFile> valid = photos == null ? List.of()
                : photos.stream().filter(p -> p != null && !p.isEmpty()).toList();
        if (valid.isEmpty()) {
            throw new BizException(400, "请先拍照或选择照片");
        }
        if (valid.size() > 9) {
            throw new BizException(400, "一次最多上传 9 张照片");
        }
        for (MultipartFile photo : valid) {
            if (photo.getSize() > MAX_SIZE) {
                throw new BizException(400, "照片不能超过 10MB");
            }
            String original = photo.getOriginalFilename() == null ? "" : photo.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
                throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
            }
        }
        if (sceneTag == null || sceneTag.isBlank()) {
            throw new BizException(400, "请选择场景标签");
        }
        if (note != null && note.length() > 500) {
            throw new BizException(400, "备注不能超过 500 字");
        }
        if (studentIds == null || studentIds.isEmpty()) {
            throw new BizException(400, "请选择至少一名学生");
        }
        // 批54① 微光开放全体老师：任课∪班主任∪年级绑定（与班级下拉同源口径）；家长仍拒
        rejectParent(user);
        dataScope.checkClassTeachable(user, classId);
        List<Student> students = studentMapper.selectBatchIds(studentIds);
        if (students.size() != studentIds.size()
                || students.stream().anyMatch(s -> !classId.equals(s.getClassId()))) {
            throw new BizException(400, "存在不属于该班级的学生");
        }

        List<String> objectNames = new ArrayList<>();
        for (MultipartFile photo : valid) {
            byte[] bytes;
            try {
                bytes = photo.getBytes();
            } catch (Exception e) {
                throw new BizException(400, "读取照片失败");
            }
            String original = photo.getOriginalFilename() == null ? "" : photo.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String objectName = "moment/" + classId + "/" + UUID.randomUUID() + "." + ext;
            pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, photo.getContentType());
            objectNames.add(objectName);
        }

        Moment m = new Moment();
        m.setTeacherId(user.userId());
        m.setClassId(classId);
        m.setPhotoUrl(String.join(",", objectNames));
        m.setSceneTag(sceneTag.trim());
        m.setNote(note == null ? null : note.trim());
        m.setSource("TEACHER");
        momentMapper.insert(m);
        for (Long sid : studentIds) {
            MomentStudent ms = new MomentStudent();
            ms.setMomentId(m.getId());
            ms.setStudentId(sid);
            momentStudentMapper.insert(ms);
        }
        // 批48（成长银行方案）：微光信箱被表扬一次+1 扬长币/人，仅手动发布（EVAL_SYNC 联动不重复发，评价已按 1分=1币 入账）
        for (Long sid : studentIds) {
            coinLedger.income(sid, LocalDate.now(), "微光", m.getId(), "微光信箱", BigDecimal.ONE);
        }
        return Map.of("momentId", m.getId());
    }

    /** 加分同步微光（批6 漏项D）：正向评价自动生成的无照片微光（source=EVAL_SYNC）。
        仅进学生档案/家长孩子流（消费侧按 source 过滤班级墙与照片渲染），删除入口同随手拍 */
    public void createSynced(UserPrincipal user, Long classId, Long studentId, String sceneTag, String note) {
        Moment m = new Moment();
        m.setTeacherId(user.userId());
        m.setClassId(classId);
        m.setPhotoUrl(null);
        m.setSceneTag(sceneTag);
        m.setNote(note != null && note.length() > 500 ? note.substring(0, 500) : note);
        m.setSource("EVAL_SYNC");
        momentMapper.insert(m);
        MomentStudent ms = new MomentStudent();
        ms.setMomentId(m.getId());
        ms.setStudentId(studentId);
        momentStudentMapper.insert(ms);
    }

    /** 场景标签字典（批6 漏项H）：种子+教师自建，按 sort 排序 */
    public List<Map<String, Object>> listTags(UserPrincipal user) {
        rejectParent(user);
        return momentTagMapper.selectList(new LambdaQueryWrapper<MomentTag>()
                        .orderByAsc(MomentTag::getSort).orderByAsc(MomentTag::getId))
                .stream().map(t -> Map.<String, Object>of("id", t.getId(), "name", t.getName())).toList();
    }

    /** 教师自建标签（≤32 字，重名拒绝） */
    public Map<String, Object> createTag(UserPrincipal user, String name) {
        rejectParent(user);
        if (name == null || name.isBlank()) {
            throw new BizException(400, "标签名不能为空");
        }
        String n = name.trim();
        if (n.length() > 32) {
            throw new BizException(400, "标签名不能超过 32 字");
        }
        if (momentTagMapper.selectCount(new LambdaQueryWrapper<MomentTag>()
                .eq(MomentTag::getName, n)) > 0) {
            throw new BizException(400, "标签已存在");
        }
        MomentTag t = new MomentTag();
        t.setName(n);
        t.setSort(99);                       // 自建标签排种子之后
        t.setCreateUserId(user.userId());
        try {
            momentTagMapper.insert(t);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(400, "标签已存在");
        }
        return Map.of("id", t.getId(), "name", t.getName());
    }

    private void rejectParent(UserPrincipal user) {
        if ("PARENT".equals(user.role())) {
            throw new BizException(403, "仅教师可访问");
        }
    }

    /** 班级最近微光（班级页轮播；含关联学生姓名与记录教师。只显教师随手拍——家长上传仅进孩子档案，方案A） */
    public List<Map<String, Object>> listByClass(UserPrincipal user, Long classId, int limit) {
        List<Long> visible = dataScope.visibleClassIds(user);
        if (visible != null && !visible.contains(classId)) {
            throw new BizException(403, "无该班级数据权限");
        }
        List<Moment> moments = momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                .eq(Moment::getClassId, classId)
                .eq(Moment::getSource, "TEACHER")
                .orderByDesc(Moment::getCreateTime).orderByDesc(Moment::getId)
                .last("LIMIT " + Math.min(limit, 50)));
        return assemble(moments);
    }

    /** 某学生的微光（学生详情「TA的闪光时刻」） */
    public List<Map<String, Object>> listByStudent(UserPrincipal user, Long studentId, int limit) {
        dataScope.checkStudentAccess(user, studentId);
        List<Long> momentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getStudentId, studentId))
                .stream().map(MomentStudent::getMomentId).toList();
        if (momentIds.isEmpty()) {
            return List.of();
        }
        List<Moment> moments = momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                .in(Moment::getId, momentIds)
                .orderByDesc(Moment::getCreateTime).orderByDesc(Moment::getId)
                .last("LIMIT " + Math.min(limit, 50)));
        return assemble(moments);
    }

    /** 删除：仅记录教师本人或管理员；连带删学生关联与 MinIO 对象 */
    @Transactional
    public void delete(UserPrincipal user, Long id) {
        Moment m = momentMapper.selectById(id);
        if (m == null) {
            throw new BizException(404, "微光记录不存在");
        }
        if (!m.getTeacherId().equals(user.userId()) && !"ADMIN".equals(user.role())) {
            throw new BizException(403, "仅记录教师本人或管理员可删除");
        }
        List<Long> studentIds = momentStudentMapper.selectList(new LambdaQueryWrapper<MomentStudent>()
                        .eq(MomentStudent::getMomentId, id))
                .stream().map(MomentStudent::getStudentId).toList();
        momentMapper.deleteById(id);
        momentStudentMapper.delete(new LambdaQueryWrapper<MomentStudent>()
                .eq(MomentStudent::getMomentId, id));
        if (m.getPhotoUrl() != null) {   // EVAL_SYNC（加分同步）无照片对象可删；批62 多图逐个删
            for (String objectName : m.getPhotoUrl().split(",")) {
                pdfStore.delete(objectName);
            }
        }
        // 批48：删手动微光连带冲正已发的 1 扬长币（reverse 无行=本批之前的老数据没发过币，自然不冲）
        if (!"EVAL_SYNC".equals(m.getSource())) {
            for (Long sid : studentIds) {
                coinLedger.reverse(sid, "微光", id);
            }
        }
    }

    /** 批量组装视图：students[{id,name}] + teacherName + 可直接访问的 photoUrl */
    public List<Map<String, Object>> assemble(List<Moment> moments) {
        if (moments.isEmpty()) {
            return List.of();
        }
        List<Long> ids = moments.stream().map(Moment::getId).toList();
        Map<Long, List<MomentStudent>> byMoment = momentStudentMapper.selectList(
                        new LambdaQueryWrapper<MomentStudent>().in(MomentStudent::getMomentId, ids))
                .stream().collect(Collectors.groupingBy(MomentStudent::getMomentId));
        List<Long> studentIds = byMoment.values().stream().flatMap(List::stream)
                .map(MomentStudent::getStudentId).distinct().toList();
        // selectBatchIds 空集合生成 IN () 非法 SQL——全部微光都无学生关联时直接跳过
        Map<Long, Student> stuById = studentIds.isEmpty() ? Map.of()
                : studentMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> teacherNames = userMapper.selectBatchIds(
                        moments.stream().map(Moment::getTeacherId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Moment m : moments) {
            List<Map<String, Object>> students = byMoment.getOrDefault(m.getId(), List.of()).stream()
                    .map(ms -> stuById.get(ms.getStudentId()))
                    .filter(s -> s != null)
                    .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName()))
                    .toList();
            // EVAL_SYNC（加分同步）无照片：photoUrl 下发 null，前端渲染文字卡
            // 批62 多图：photoUrl=首张（兼容单图消费端），photoUrls=全量（?i= 索引）
            List<String> parts = m.getPhotoUrl() == null || m.getPhotoUrl().isBlank()
                    ? List.of() : List.of(m.getPhotoUrl().split(","));
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                urls.add("/api/moment/file/" + m.getId() + "?i=" + i);
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("note", m.getNote() == null ? "" : m.getNote());
            row.put("sceneTag", m.getSceneTag());
            row.put("source", m.getSource() == null ? "TEACHER" : m.getSource());
            row.put("createTime", m.getCreateTime());
            row.put("teacherId", m.getTeacherId());
            row.put("teacherName", teacherNames.getOrDefault(m.getTeacherId(), ""));
            row.put("students", students);
            row.put("photoUrl", urls.isEmpty() ? null : urls.get(0));
            row.put("photoUrls", urls);
            out.add(row);
        }
        return out;
    }
}
