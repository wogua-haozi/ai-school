package com.aischool.server.service.repair;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Repair;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.entity.User;
import com.aischool.server.mapper.RepairMapper;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.notify.NotificationService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 报修单（批10；批63 三层流转）：文字描述+拍照凭证（≤3 张，MinIO repair/ 前缀，同微光照片模式）。
 * 提交 PENDING → 后勤中心（LOGISTICS）任一人审批 → APPROVED（全部维修工 REPAIRER 可见，任一人抢单 CLAIMED，
 * 条件更新防双抢）→ 接单人完成 DONE → 通知报修人+抄送 repair_cc_uids；拒绝 REJECTED；报修人 PENDING/APPROVED 可撤回。
 * 管理端 handle 兜底：非终态可直办（三层流转未走通时）。
 */
@Service
@RequiredArgsConstructor
public class RepairService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final RepairMapper repairMapper;
    private final UserMapper userMapper;
    private final SysConfigMapper sysConfigMapper;
    private final PdfStoreService pdfStore;
    private final NotificationService notificationService;

    public Map<String, Object> create(UserPrincipal user, String location, String description,
                                      List<MultipartFile> photos) {
        if (location == null || location.isBlank()) {
            throw new BizException(400, "请填写故障地点或设施");
        }
        if (description == null || description.isBlank()) {
            throw new BizException(400, "请填写故障描述");
        }
        if (description.length() > 500) {
            throw new BizException(400, "故障描述不能超过 500 字");
        }
        List<String> objects = new ArrayList<>();
        if (photos != null) {
            if (photos.size() > 3) {
                throw new BizException(400, "凭证照片最多 3 张");
            }
            for (MultipartFile p : photos) {
                if (p == null || p.isEmpty()) {
                    continue;
                }
                if (p.getSize() > MAX_SIZE) {
                    throw new BizException(400, "照片不能超过 10MB");
                }
                String original = p.getOriginalFilename() == null ? "" : p.getOriginalFilename();
                String ext = original.contains(".")
                        ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
                    throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
                }
                byte[] bytes;
                try {
                    bytes = p.getBytes();
                } catch (Exception e) {
                    throw new BizException(400, "读取照片失败");
                }
                String objectName = "repair/" + UUID.randomUUID() + "." + ext;
                pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, p.getContentType());
                objects.add(objectName);
            }
        }
        Repair r = new Repair();
        r.setReporterId(user.userId());
        r.setLocation(location.trim());
        r.setDescription(description.trim());
        r.setPhotos(objects.isEmpty() ? null : toJson(objects));
        r.setStatus(Repair.PENDING);
        repairMapper.insert(r);
        notificationService.repairTodo("LOGISTICS", "待审批：报修单",
                realName(user.userId()) + " 提交的「" + r.getLocation() + "」报修单等待您审批（单号 #" + r.getId() + "）");
        return Map.of("repairId", r.getId());
    }

    /** 批63 后勤审批：PENDING → APPROVED（通知全部维修工抢单）/ REJECTED（通知报修人） */
    public void approve(Long id, UserPrincipal user, boolean approved, String note) {
        if (!"LOGISTICS".equals(user.role())) {
            throw new BizException(403, "仅后勤中心可审批报修单");
        }
        Repair r = require(id);
        if (!Repair.PENDING.equals(r.getStatus())) {
            throw new BizException(400, "该报修单不在待审批状态");
        }
        if (note != null && note.length() > 300) {
            throw new BizException(400, "审批意见不能超过 300 字");
        }
        r.setStatus(approved ? Repair.APPROVED : Repair.REJECTED);
        r.setApproverId(user.userId());
        r.setApproveTime(LocalDateTime.now());
        r.setApproveNote(note);
        repairMapper.updateById(r);
        if (approved) {
            notificationService.repairTodo("REPAIRER", "待接单：报修单",
                    "「" + r.getLocation() + "」报修单已审批通过，等待接单（单号 #" + r.getId() + "）");
        } else {
            notificationService.repairResult(r.getReporterId(), "报修单未通过审批",
                    "您提交的「" + r.getLocation() + "」报修单未通过审批"
                            + (note == null || note.isBlank() ? "" : "，意见：" + note));
        }
    }

    /** 批63 维修工接单（抢单）：条件更新 WHERE status=APPROVED 防双抢，0 行=已被他人接走 */
    public void claim(Long id, UserPrincipal user) {
        if (!"REPAIRER".equals(user.role())) {
            throw new BizException(403, "仅维修工可接单");
        }
        Repair r = require(id);
        Repair upd = new Repair();
        upd.setStatus(Repair.CLAIMED);
        upd.setClaimerId(user.userId());
        upd.setClaimTime(LocalDateTime.now());
        int rows = repairMapper.update(upd, new LambdaUpdateWrapper<Repair>()
                .eq(Repair::getId, id).eq(Repair::getStatus, Repair.APPROVED));
        if (rows == 0) {
            throw new BizException(400, "该报修单已被他人接单或状态已变化");
        }
        notificationService.repairResult(r.getReporterId(), "报修已接单",
                "您提交的「" + r.getLocation() + "」报修单已由 " + realName(user.userId()) + " 接单维修");
    }

    /** 批63 接单人完成：CLAIMED → DONE（handler=接单人本人）；通知报修人+逐个抄送 repair_cc_uids */
    public void complete(Long id, UserPrincipal user, String note) {
        Repair r = require(id);
        if (!user.userId().equals(r.getClaimerId())) {
            throw new BizException(403, "仅接单维修工本人可标记完成");
        }
        if (!Repair.CLAIMED.equals(r.getStatus())) {
            throw new BizException(400, "该报修单不在维修中状态");
        }
        if (note != null && note.length() > 300) {
            throw new BizException(400, "处理说明不能超过 300 字");
        }
        r.setStatus(Repair.DONE);
        r.setHandlerId(user.userId());
        r.setHandleNote(note);
        r.setHandleTime(LocalDateTime.now());
        repairMapper.updateById(r);
        String who = realName(user.userId());
        notificationService.repairResult(r.getReporterId(), "报修已完成",
                "您提交的「" + r.getLocation() + "」报修单已由 " + who + " 完成维修"
                        + (note == null || note.isBlank() ? "" : "，说明：" + note));
        for (Long ccId : ccUids()) {
            if (!ccId.equals(user.userId())) {
                notificationService.repairResult(ccId, "报修完成抄送",
                        "「" + r.getLocation() + "」报修单（单号 #" + r.getId() + "）已由 " + who + " 完成维修，请知悉");
            }
        }
    }

    /** 批63 工作台（按角色分流）：LOGISTICS=待审批；REPAIRER=待接单抢单池+我的维修中 */
    public Map<String, Object> workbench(UserPrincipal user) {
        Map<String, Object> data = new LinkedHashMap<>();
        if ("LOGISTICS".equals(user.role())) {
            data.put("todo", toRows(repairMapper.selectList(new LambdaQueryWrapper<Repair>()
                    .eq(Repair::getStatus, Repair.PENDING).orderByAsc(Repair::getId))));
        } else if ("REPAIRER".equals(user.role())) {
            data.put("open", toRows(repairMapper.selectList(new LambdaQueryWrapper<Repair>()
                    .eq(Repair::getStatus, Repair.APPROVED).orderByAsc(Repair::getId))));
            data.put("mine", toRows(repairMapper.selectList(new LambdaQueryWrapper<Repair>()
                    .eq(Repair::getClaimerId, user.userId())
                    .eq(Repair::getStatus, Repair.CLAIMED).orderByAsc(Repair::getId))));
        } else {
            throw new BizException(403, "报修工作台仅后勤中心与维修工使用");
        }
        return data;
    }

    /** 我的报修 */
    public List<Map<String, Object>> my(UserPrincipal user, String status) {
        return toRows(repairMapper.selectList(new LambdaQueryWrapper<Repair>()
                .eq(Repair::getReporterId, user.userId())
                .eq(status != null && !status.isBlank(), Repair::getStatus, status)
                .orderByDesc(Repair::getId)));
    }

    /** 管理端全量 */
    public List<Map<String, Object>> list(String status) {
        return toRows(repairMapper.selectList(new LambdaQueryWrapper<Repair>()
                .eq(status != null && !status.isBlank(), Repair::getStatus, status)
                .orderByDesc(Repair::getId)));
    }

    /** 详情（报修人本人/后勤/维修工/管理员） */
    public Map<String, Object> detail(Long id, UserPrincipal user) {
        Repair r = require(id);
        checkVisible(r, user);
        Map<Long, String> names = userNames(java.util.stream.Stream.of(
                        r.getReporterId(), r.getHandlerId(), r.getApproverId(), r.getClaimerId())
                .filter(java.util.Objects::nonNull).distinct().toList()); // 未处理时为 null，List.of 会 NPE
        Map<String, Object> m = rowOf(r, names);
        m.put("description", r.getDescription());
        m.put("handleNote", r.getHandleNote() == null ? "" : r.getHandleNote());
        m.put("handlerName", r.getHandlerId() == null ? "" : names.getOrDefault(r.getHandlerId(), ""));
        m.put("handleTime", r.getHandleTime());
        m.put("approveNote", r.getApproveNote() == null ? "" : r.getApproveNote());
        return m;
    }

    /** 管理端兜底处理（批63 放宽到非终态）：DONE/REJECTED；三层流转走不通时管理员直办 */
    public void handle(Long id, UserPrincipal admin, String status, String note) {
        Repair r = require(id);
        if (Repair.DONE.equals(r.getStatus()) || Repair.REJECTED.equals(r.getStatus())
                || Repair.CANCELED.equals(r.getStatus())) {
            throw new BizException(400, "该报修单已处理结束");
        }
        if (!Repair.DONE.equals(status) && !Repair.REJECTED.equals(status)) {
            throw new BizException(400, "处理动作须为 DONE（已完成）或 REJECTED（不予受理）");
        }
        if (note != null && note.length() > 300) {
            throw new BizException(400, "处理说明不能超过 300 字");
        }
        r.setStatus(status);
        r.setHandlerId(admin.userId());
        r.setHandleNote(note);
        r.setHandleTime(LocalDateTime.now());
        repairMapper.updateById(r);
        notificationService.repairResult(r.getReporterId(), Repair.DONE.equals(status) ? "报修已处理" : "报修不予受理",
                "您提交的「" + r.getLocation() + "」报修单已由管理员"
                        + (Repair.DONE.equals(status) ? "办结" : "标记不予受理")
                        + (note == null || note.isBlank() ? "" : "，说明：" + note));
    }

    /** 批40e 撤回普适（批63 放宽）：发起人撤回报修单（PENDING/APPROVED 未接单时可撤；凭证照片对象一并清理） */
    public void cancel(Long id, UserPrincipal user) {
        Repair r = require(id);
        if (!r.getReporterId().equals(user.userId())) {
            throw new BizException(403, "只有报修人本人可撤回报修单");
        }
        if (!Repair.PENDING.equals(r.getStatus()) && !Repair.APPROVED.equals(r.getStatus())) {
            throw new BizException(400, "该报修单已被接单或已结束，不能撤回");
        }
        for (String objectName : parsePhotos(r.getPhotos())) {
            pdfStore.delete(objectName);
        }
        r.setPhotos(null);
        r.setStatus(Repair.CANCELED);
        repairMapper.updateById(r);
    }

    /** 逐张凭证照片回图（照片内容=故障现场，报修人本人与管理员可见） */
    public String photoObject(Long id, int idx, UserPrincipal user) {
        Repair r = require(id);
        checkVisible(r, user);
        List<String> objects = parsePhotos(r.getPhotos());
        if (idx < 0 || idx >= objects.size()) {
            throw new BizException(404, "照片不存在");
        }
        return objects.get(idx);
    }

    private void checkVisible(Repair r, UserPrincipal user) {
        String role = user.role();
        if ("ADMIN".equals(role) || "LOGISTICS".equals(role) || "REPAIRER".equals(role)
                || r.getReporterId().equals(user.userId())) { // 批63 放行后勤/维修工（审批与接单要看详情+照片）
            return;
        }
        throw new BizException(403, "仅报修人、后勤中心、维修工与管理员可查看该报修单");
    }

    Repair require(Long id) {
        Repair r = repairMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "报修单不存在");
        }
        return r;
    }

    private List<Map<String, Object>> toRows(List<Repair> rows) {
        Map<Long, String> names = userNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.getReporterId(), r.getHandlerId(),
                        r.getApproverId(), r.getClaimerId()))
                .filter(java.util.Objects::nonNull).distinct().toList());
        return rows.stream().map(r -> rowOf(r, names)).collect(Collectors.toList());
    }

    private Map<String, Object> rowOf(Repair r, Map<Long, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("location", r.getLocation());
        m.put("description", r.getDescription());
        m.put("status", r.getStatus());
        m.put("reporterName", names.getOrDefault(r.getReporterId(), ""));
        m.put("approverName", r.getApproverId() == null ? "" : names.getOrDefault(r.getApproverId(), ""));
        m.put("approveTime", r.getApproveTime());
        m.put("claimerName", r.getClaimerId() == null ? "" : names.getOrDefault(r.getClaimerId(), ""));
        m.put("claimTime", r.getClaimTime());
        m.put("handlerName", r.getHandlerId() == null ? "" : names.getOrDefault(r.getHandlerId(), ""));
        m.put("handleNote", r.getHandleNote() == null ? "" : r.getHandleNote());
        m.put("handleTime", r.getHandleTime());
        m.put("createTime", r.getCreateTime());
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < parsePhotos(r.getPhotos()).size(); i++) {
            urls.add("/api/repair/file/" + r.getId() + "?idx=" + i); // 前端 fetchBlob 直取（同封面/微光照片模式）
        }
        m.put("photoCount", urls.size());
        m.put("photoUrls", urls);
        return m;
    }

    private String realName(Long userId) {
        User u = userMapper.selectById(userId);
        return u == null ? "" : u.getRealName();
    }

    /** 批63 抄送名单：t_sys_config repair_cc_uids（逗号分隔用户 id；生产填 4 后勤+冯荣森+杜伟滨） */
    private List<Long> ccUids() {
        SysConfig c = sysConfigMapper.selectById("repair_cc_uids");
        if (c == null || c.getCfgValue() == null || c.getCfgValue().isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String s : c.getCfgValue().split(",")) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private Map<Long, String> userNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return distinct.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(distinct).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
    }

    private List<String> parsePhotos(String photos) {
        if (photos == null || photos.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(photos, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(500, "照片清单序列化失败");
        }
    }
}
