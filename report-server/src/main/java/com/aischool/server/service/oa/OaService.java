package com.aischool.server.service.oa;

import com.aischool.server.common.BizException;
import com.aischool.server.entity.Goods;
import com.aischool.server.entity.GoodsFlow;
import com.aischool.server.entity.Notification;
import com.aischool.server.entity.OaFlowLog;
import com.aischool.server.entity.OaForm;
import com.aischool.server.entity.SysConfig;
import com.aischool.server.entity.User;
import com.aischool.server.entity.Venue;
import com.aischool.server.mapper.GoodsFlowMapper;
import com.aischool.server.mapper.GoodsMapper;
import com.aischool.server.mapper.OaFlowLogMapper;
import com.aischool.server.mapper.OaFormMapper;
import com.aischool.server.mapper.SysConfigMapper;
import com.aischool.server.mapper.UserMapper;
import com.aischool.server.mapper.VenueMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.security.UserPrincipal;
import com.aischool.server.service.notify.NotificationService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OA 审批引擎（批9）：公章申请固定三级审批（原始需求节点名「一级/二级/三级审批」）、
 * 物资申领级数可配（t_sys_config，默认 1）。审批人配置在管理端（校方自助），
 * ADMIN 可代审任意待审级（审批人休假兜底）。
 */
@Service
@RequiredArgsConstructor
public class OaService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_SUBMIT = "提交";
    private static final long PHOTO_MAX_SIZE = 10L * 1024 * 1024;

    private final OaFormMapper formMapper;
    private final OaFlowLogMapper logMapper;
    private final GoodsMapper goodsMapper;
    private final GoodsFlowMapper goodsFlowMapper;
    private final SysConfigMapper sysConfigMapper;
    private final UserMapper userMapper;
    private final VenueMapper venueMapper;
    private final NotificationService notificationService;
    private final PdfStoreService pdfStore;

    // ---- 配置 ----

    private String cfg(String key) {
        SysConfig c = sysConfigMapper.selectById(key);
        return c == null || c.getCfgValue() == null ? "" : c.getCfgValue().trim();
    }

    public void setCfg(String key, String value) {
        SysConfig c = new SysConfig();
        c.setCfgKey(key);
        c.setCfgValue(value);
        sysConfigMapper.updateById(c); // 行由 V22 种子保证存在
    }

    /** 配置读取（管理端回显用；空配置返回 ""） */
    public String cfgOf(String key) {
        return cfg(key);
    }

    /** 物资/请假审批级数（1-3，默认 1，键 oa_{type}_levels）；公章固定 3；采购固定 5（批43①） */
    public int levels(String formType) {
        if (OaForm.TYPE_SEAL.equals(formType)) {
            return 3;
        }
        if (OaForm.TYPE_PURCHASE.equals(formType)) {
            return 5;
        }
        try {
            return Math.max(1, Math.min(3, Integer.parseInt(cfg("oa_" + formType.toLowerCase() + "_levels"))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 第 level 级审批人 user_id 列表（批43① 或签）：配置值为逗号分隔 id（"5,12"），任一人通过即过级。
     * 单人配置 "5" 与既有四类型完全兼容。
     */
    private List<Long> approverIds(String formType, int level) {
        String v = cfg("oa_" + formType.toLowerCase() + "_l" + level);
        if (v.isEmpty()) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::parseLong).distinct().toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    /** 单据感知的审批人解析（待办/审批/详情/流转通知一律走此口径）：
     *  批49①：采购第 5 级=该单验收人（detail.acceptorIds，默认申请人可改），配置值为兜底。 */
    private List<Long> approverIds(OaForm form, int level) {
        if (OaForm.TYPE_PURCHASE.equals(form.getFormType()) && level == 5) {
            List<Long> ids = detailAcceptorIds(form);
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return approverIds(form.getFormType(), level);
    }

    /** 采购单验收人（detail JSON acceptorIds；旧单/解析失败=空走配置兜底） */
    private List<Long> detailAcceptorIds(OaForm form) {
        try {
            var node = JSON.readTree(form.getDetail()).get("acceptorIds");
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Long> ids = new java.util.ArrayList<>();
            node.forEach(n -> {
                if (n.canConvertToLong()) {
                    ids.add(n.longValue());
                }
            });
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 过滤出账号仍存在的审批人（被删账号配成审批人会让单据卡死在无人可审的级） */
    private List<Long> validUserIds(List<Long> ids) {
        return ids.isEmpty() ? ids
                : ids.stream().filter(id -> userMapper.selectById(id) != null).toList();
    }

    /** 提交前校验所用级数的审批人全部配齐（否则单据卡死在无人可审的级） */
    private void checkApproversConfigured(String formType) {
        int n = levels(formType);
        for (int i = 1; i <= n; i++) {
            if (OaForm.TYPE_PURCHASE.equals(formType) && i == 5) {
                continue; // 批49①：验收人=单据级（默认申请人，提交时可改），无需全局配置
            }
            if (validUserIds(approverIds(formType, i)).isEmpty()) {
                throw new BizException(400, "管理员尚未配齐「" + typeName(formType) + "」第 " + i
                        + " 级（" + nodeName(formType, i) + "）审批人，请联系管理员配置后再提交");
            }
        }
    }

    public static String typeName(String formType) {
        if (OaForm.TYPE_GOODS.equals(formType)) {
            return "物资申领";
        }
        if (OaForm.TYPE_LEAVE.equals(formType)) {
            return "教师请假";
        }
        if (OaForm.TYPE_PURCHASE.equals(formType)) {
            return "采购申请";
        }
        return OaForm.TYPE_VENUE.equals(formType) ? "场地申请" : "公章使用申请";
    }

    // ---- 提交 ----

    @Data
    public static class SubmitReq {
        private String formType;
        private String title;
        private String reason;
        private String useDate;
        private List<GoodsLine> goodsLines;
        private Long venueId; // 批11：场地申请（t_venue）
        // 批43①：采购申请（钉钉流程移植）
        private String expectDate; // 期望交付日期
        private String place; // 交付地点
        private List<String> photos; // 附件 objectName（先传 /purchase/photo 预上传拿 key）
        private List<PurchaseItem> items; // 采购明细（多组）
        private List<Long> acceptorIds; // 批49①：采购验收人（默认=申请人，提交时可改）
    }

    @Data
    public static class GoodsLine {
        private Long goodsId;
        private Integer qty;
    }

    /** 批43① 采购明细行（甲方表单原样：名称/型号规格/数量为文本，无金额字段） */
    @Data
    public static class PurchaseItem {
        private String name;
        private String spec;
        private String qty;
        private String note;
    }

    public OaForm submit(SubmitReq req) {
        var user = AuthUtil.current();
        if ("PARENT".equals(user.role())) {
            throw new BizException(403, "家长账号无需使用行政办公审批");
        }
        // 批45 追加①：教师请假下线（教师请假走线下流程；存量单据仍可在列表/详情查看）
        if (OaForm.TYPE_LEAVE.equals(req.getFormType())) {
            throw new BizException(400, "教师请假已下线，请按学校线下流程办理");
        }
        String type = OaForm.TYPE_GOODS.equals(req.getFormType()) ? OaForm.TYPE_GOODS
                : OaForm.TYPE_VENUE.equals(req.getFormType()) ? OaForm.TYPE_VENUE
                : OaForm.TYPE_PURCHASE.equals(req.getFormType()) ? OaForm.TYPE_PURCHASE : OaForm.TYPE_SEAL;
        checkApproversConfigured(type);
        OaForm form = new OaForm();
        form.setFormType(type);
        form.setApplicantId(user.userId());
        form.setStatus(OaForm.PENDING);
        form.setCurrentLevel(1);
        if (type.equals(OaForm.TYPE_SEAL)) {
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                throw new BizException(400, "请填写申请事由");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", req.getTitle());
            detail.put("useDate", req.getUseDate());
            form.setTitle(req.getTitle().trim());
            form.setDetail(toJson(detail));
        } else if (type.equals(OaForm.TYPE_VENUE)) {
            if (req.getVenueId() == null || req.getUseDate() == null || req.getUseDate().isBlank()) {
                throw new BizException(400, "请选择场地与使用日期");
            }
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                throw new BizException(400, "请填写申请事由");
            }
            Venue v = venueMapper.selectById(req.getVenueId());
            if (v == null || v.getStatus() == null || v.getStatus() != 1) {
                throw new BizException(400, "场地不存在或已停用");
            }
            checkVenueFree(v.getId(), req.getUseDate());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("venueId", v.getId());
            detail.put("venueName", v.getName());
            detail.put("useDate", req.getUseDate());
            detail.put("reason", req.getTitle().trim());
            form.setTitle(v.getName() + "·" + req.getUseDate());
            form.setDetail(toJson(detail));
        } else if (type.equals(OaForm.TYPE_PURCHASE)) {
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                throw new BizException(400, "请填写申请事由");
            }
            if (req.getTitle().length() > 200) {
                throw new BizException(400, "申请事由不能超过 200 字");
            }
            if (req.getExpectDate() == null || req.getExpectDate().isBlank()) {
                throw new BizException(400, "请选择期望交付日期");
            }
            if (req.getPlace() == null || req.getPlace().isBlank()) {
                throw new BizException(400, "请填写交付地点");
            }
            List<PurchaseItem> items = req.getItems();
            if (items == null || items.isEmpty()) {
                throw new BizException(400, "请至少填写一项采购明细");
            }
            for (int i = 0; i < items.size(); i++) {
                PurchaseItem it = items.get(i);
                boolean blankRow = (it.getName() == null || it.getName().isBlank())
                        && (it.getSpec() == null || it.getSpec().isBlank())
                        && (it.getQty() == null || it.getQty().isBlank());
                if (blankRow) {
                    continue; // 全空行丢弃（前端「复制/添加」产生的空行）
                }
                if (it.getName() == null || it.getName().isBlank()
                        || it.getSpec() == null || it.getSpec().isBlank()
                        || it.getQty() == null || it.getQty().isBlank()) {
                    throw new BizException(400, "采购明细第 " + (i + 1) + " 项须填写物品名称、型号规格与数量");
                }
            }
            long filled = items.stream().filter(it -> it.getName() != null && !it.getName().isBlank()).count();
            if (filled == 0) {
                throw new BizException(400, "请至少填写一项采购明细");
            }
            if (req.getPhotos() != null && req.getPhotos().size() > 3) {
                throw new BizException(400, "附件最多 3 张");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", req.getTitle().trim());
            detail.put("expectDate", req.getExpectDate());
            detail.put("place", req.getPlace().trim());
            // 批49①：验收人默认=申请人本人，提交时可改（甲方口径）；空/未传即回退默认
            List<Long> acceptors = req.getAcceptorIds() == null ? List.<Long>of()
                    : req.getAcceptorIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
            detail.put("acceptorIds", acceptors.isEmpty() ? List.of(user.userId()) : acceptors);
            detail.put("photos", req.getPhotos() == null ? List.of() : req.getPhotos());
            detail.put("items", items.stream()
                    .filter(it -> it.getName() != null && !it.getName().isBlank())
                    .map(it -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", it.getName().trim());
                        m.put("spec", it.getSpec().trim());
                        m.put("qty", it.getQty().trim());
                        m.put("note", it.getNote() == null ? "" : it.getNote().trim());
                        return m;
                    }).toList());
            form.setTitle(req.getTitle().trim());
            form.setDetail(toJson(detail));
        } else {
            List<GoodsLine> lines = req.getGoodsLines();
            if (lines == null || lines.isEmpty()) {
                throw new BizException(400, "请至少选择一项物资");
            }
            List<Map<String, Object>> detail = new ArrayList<>();
            StringBuilder title = new StringBuilder();
            for (GoodsLine l : lines) {
                Goods g = goodsMapper.selectById(l.getGoodsId());
                if (g == null || g.getStatus() == null || g.getStatus() != 1) {
                    throw new BizException(400, "物资不存在或已停用");
                }
                if (l.getQty() == null || l.getQty() <= 0) {
                    throw new BizException(400, "申领数量须大于 0");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("goodsId", g.getId());
                row.put("name", g.getName());
                row.put("qty", l.getQty());
                row.put("unit", g.getUnit());
                row.put("location", g.getLocation() == null ? "" : g.getLocation());
                detail.add(row);
                if (title.length() > 0) {
                    title.append("、");
                }
                title.append(g.getName()).append("×").append(l.getQty()).append(g.getUnit());
            }
            form.setTitle(title.toString());
            form.setDetail(toJson(detail));
        }
        formMapper.insert(form);
        OaFlowLog log = new OaFlowLog();
        log.setFormId(form.getId());
        log.setAction(OaFlowLog.SUBMIT);
        log.setLevel(0);
        log.setNodeName(NODE_SUBMIT);
        log.setOperatorId(user.userId());
        logMapper.insert(log);
        // 批29：通知第一级审批人（App 通知中心 + 企微群）；批43① 或签=逐人通知、群合并一条
        List<Long> first = validUserIds(approverIds(type, 1));
        if (!first.isEmpty()) {
            notificationService.oaTodoAny(first, typeName(type), form.getTitle(), form.getId(), user.realName());
        }
        return form;
    }

    // ---- 查询 ----

    /** 我的申请 */
    public List<Map<String, Object>> myList(String status) {
        var user = AuthUtil.current();
        return toRows(formMapper.selectList(new LambdaQueryWrapper<OaForm>()
                .eq(OaForm::getApplicantId, user.userId())
                .eq(status != null && !status.isBlank(), OaForm::getStatus, status)
                .orderByDesc(OaForm::getId)));
    }

    /** 待我审批：当前待审级配置人含本人 的 PENDING 单（或签=列表任一人；PARENT 不会成为审批人，天然空） */
    public List<Map<String, Object>> todoList() {
        var user = AuthUtil.current();
        Long uid = user.userId();
        List<OaForm> all = formMapper.selectList(new LambdaQueryWrapper<OaForm>()
                .eq(OaForm::getStatus, OaForm.PENDING));
        List<OaForm> mine = all.stream()
                .filter(f -> approverIds(f, f.getCurrentLevel()).contains(uid))
                .collect(Collectors.toList());
        return toRows(mine);
    }

    /** 管理端全量 */
    public List<Map<String, Object>> allList(String formType, String status) {
        return toRows(formMapper.selectList(new LambdaQueryWrapper<OaForm>()
                .eq(formType != null && !formType.isBlank(), OaForm::getFormType, formType)
                .eq(status != null && !status.isBlank(), OaForm::getStatus, status)
                .orderByDesc(OaForm::getId)));
    }

    /** 单据详情：单+明细+流转日志（申请人与审批人可见；教师只能看自己的+待我审的） */
    public Map<String, Object> detail(Long id) {
        var user = AuthUtil.current();
        OaForm form = requireForm(id);
        if (!canView(form, user)) {
            throw new BizException(403, "仅申请人与当前审批人可查看该单据");
        }
        List<OaFlowLog> logs = logMapper.selectList(new LambdaQueryWrapper<OaFlowLog>()
                .eq(OaFlowLog::getFormId, id).orderByAsc(OaFlowLog::getId));
        Map<Long, String> names = userNames(form, logs);
        Map<String, Object> out = rowOf(form, names);
        // 明细原文（SEAL={reason,useDate}，GOODS=[{goodsId,name,qty,unit,location}]），前端按类型 parse
        out.put("detail", form.getDetail());
        out.put("levels", levels(form.getFormType()));
        out.put("logs", logs.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", l.getAction());
            m.put("nodeName", l.getNodeName());
            m.put("operatorName", names.getOrDefault(l.getOperatorId(), ""));
            m.put("note", l.getNote() == null ? "" : l.getNote());
            m.put("createTime", l.getCreateTime());
            return m;
        }).toList());
        // 批43① 采购：附件流回 URL + 库存参考（库存确认节点辅助）+ 抄送人展示
        if (OaForm.TYPE_PURCHASE.equals(form.getFormType())) {
            Map<String, Object> d = parseObj(form.getDetail());
            List<String> photoUrls = new ArrayList<>();
            if (d.get("photos") instanceof List<?> ph) {
                for (int i = 0; i < ph.size(); i++) {
                    photoUrls.add("/api/oa/purchase/file/" + form.getId() + "?idx=" + i);
                }
            }
            out.put("photoUrls", photoUrls);
            // 批49① 验收人姓名回显（详情页展示用；旧单无 acceptorIds 不下发）
            List<Long> acc = detailAcceptorIds(form);
            if (!acc.isEmpty()) {
                Map<Long, String> nm = userMapper.selectBatchIds(acc).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
                out.put("acceptorNames", acc.stream().map(accId -> nm.getOrDefault(accId, ""))
                        .filter(s -> !s.isBlank()).toList());
            }
            List<Map<String, Object>> matches = new ArrayList<>();
            if (d.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    if (!(o instanceof Map<?, ?> it) || it.get("name") == null) {
                        continue;
                    }
                    String name = String.valueOf(it.get("name"));
                    goodsMapper.selectList(new LambdaQueryWrapper<Goods>()
                                    .like(Goods::getName, name).eq(Goods::getStatus, 1).last("LIMIT 3"))
                            .forEach(g -> matches.add(Map.of(
                                    "item", name, "name", g.getName(),
                                    "stock", g.getStock(), "unit", g.getUnit(),
                                    "location", g.getLocation() == null ? "" : g.getLocation())));
                }
            }
            out.put("stockMatches", matches);
            List<Long> cc = ccIds();
            if (!cc.isEmpty()) {
                Map<Long, String> nm = userMapper.selectBatchIds(cc).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
                out.put("ccNames", cc.stream().map(ccId -> nm.getOrDefault(ccId, ""))
                        .filter(s -> !s.isBlank()).toList());
            }
        }
        return out;
    }

    /** 可见性：管理员全量；申请人本人；当前待审级或签成员；招采看物资单（批33 核销工作台） */
    private boolean canView(OaForm form, UserPrincipal user) {
        if ("ADMIN".equals(user.role())) {
            return true;
        }
        boolean applicant = form.getApplicantId().equals(user.userId());
        boolean approver = form.getStatus().equals(OaForm.PENDING)
                && approverIds(form, form.getCurrentLevel()).contains(user.userId());
        boolean procurement = OaForm.TYPE_GOODS.equals(form.getFormType())
                && "PROCUREMENT".equals(user.role());
        return applicant || approver || procurement;
    }

    // ---- 审批动作 ----

    @Data
    public static class HandleReq {
        private String action; // AGREE / REJECT / REVOKE
        private String note;
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Long formId, HandleReq req) {
        var user = AuthUtil.current();
        String action = req.getAction();
        OaForm form = requireForm(formId);
        boolean admin = "ADMIN".equals(user.role());
        if (OaFlowLog.REVOKE.equals(action)) {
            if (!form.getApplicantId().equals(user.userId())) {
                throw new BizException(403, "仅申请人本人可撤回");
            }
            if (!form.getStatus().equals(OaForm.PENDING)) {
                throw new BizException(400, "单据已流转结束，无法撤回");
            }
            writeLog(form, OaFlowLog.REVOKE, user.userId(), req.getNote());
            finish(form, OaForm.REVOKED);
            return;
        }
        if (!form.getStatus().equals(OaForm.PENDING)) {
            throw new BizException(400, "单据已流转结束");
        }
        List<Long> approvers = approverIds(form, form.getCurrentLevel());
        boolean currentApprover = approvers.contains(user.userId());
        if (!admin && !currentApprover) {
            throw new BizException(403, "当前节点由「" + nodeName(form.getFormType(), form.getCurrentLevel()) + "」审批人处理");
        }
        if (OaFlowLog.REJECT.equals(action)) {
            writeLog(form, OaFlowLog.REJECT, user.userId(), req.getNote());
            finish(form, OaForm.REJECTED);
            notificationService.oaResult(form.getApplicantId(), typeName(form.getFormType()), form.getTitle(), false, req.getNote());
            ccPurchase(form, false, req.getNote(), user);
            return;
        }
        if (!OaFlowLog.AGREE.equals(action)) {
            throw new BizException(400, "未知审批动作");
        }
        writeLog(form, OaFlowLog.AGREE, user.userId(), req.getNote());
        if (form.getCurrentLevel() >= levels(form.getFormType())) {
            // 批33 两段式：物资末级通过=待领取，招采核销时才扣库存出库（见 issue()）
            finish(form, OaForm.APPROVED);
            String note = OaForm.TYPE_GOODS.equals(form.getFormType())
                    ? "审批通过，请到招采部门领取，领取后由招采核销出库" : req.getNote();
            notificationService.oaResult(form.getApplicantId(), typeName(form.getFormType()), form.getTitle(), true, note);
            // 批43① 采购验收通过：入库由招采线下手动处理（不走物资台账自动入库）
            ccPurchase(form, true, note, user);
        } else {
            form.setCurrentLevel(form.getCurrentLevel() + 1);
            formMapper.updateById(form);
            // 批29：流转到下一级，通知下一级审批人；批43① 或签=逐人通知、群合并一条
            List<Long> next = validUserIds(approverIds(form, form.getCurrentLevel()));
            if (!next.isEmpty()) {
                notificationService.oaTodoAny(next, typeName(form.getFormType()), form.getTitle(), form.getId(), user.realName());
            }
        }
    }

    /**
     * 物资核销出库（批33 两段式第二段）：APPROVED（待领取）单在申请人领取后由招采/管理员核销，
     * 此时才扣库存+写 OUT 流水；库存不足抛错整体回滚，单据停在待领取，招采入库后可重新核销。
     */
    @Transactional(rollbackFor = Exception.class)
    public void issue(Long formId) {
        var user = AuthUtil.current();
        if (!"PROCUREMENT".equals(user.role()) && !"ADMIN".equals(user.role())) {
            throw new BizException(403, "仅招采部门可核销出库");
        }
        OaForm form = requireForm(formId);
        if (!OaForm.TYPE_GOODS.equals(form.getFormType())) {
            throw new BizException(400, "仅物资申领单需要核销出库");
        }
        if (!OaForm.APPROVED.equals(form.getStatus())) {
            throw new BizException(400, "仅审批通过（待领取）的单据可核销");
        }
        issueGoods(form, user.userId());
        OaFlowLog log = new OaFlowLog();
        log.setFormId(form.getId());
        log.setAction(OaFlowLog.ISSUE);
        log.setLevel(form.getCurrentLevel());
        log.setNodeName("核销出库");
        log.setOperatorId(user.userId());
        logMapper.insert(log);
        finish(form, OaForm.ISSUED);
        notificationService.goodsIssued(form.getApplicantId(), form.getTitle(), user.realName());
    }

    /** 招采工作台列表（批33）：GOODS 单按状态筛（APPROVED 待领取 / ISSUED 已核销；空=全部） */
    public List<Map<String, Object>> procurementList(String status) {
        return toRows(formMapper.selectList(new LambdaQueryWrapper<OaForm>()
                .eq(OaForm::getFormType, OaForm.TYPE_GOODS)
                .eq(status != null && !status.isBlank(), OaForm::getStatus, status)
                .orderByDesc(OaForm::getId)));
    }

    /** 核销出库：逐行原子扣减+流水（「谁/何时/哪里/拿走了什么」全落在 OUT 行） */
    private void issueGoods(OaForm form, Long operatorId) {
        for (Map<String, Object> line : parseDetail(form.getDetail())) {
            Long goodsId = ((Number) line.get("goodsId")).longValue();
            int qty = ((Number) line.get("qty")).intValue();
            if (goodsMapper.deductStock(goodsId, qty) == 0) {
                Goods g = goodsMapper.selectById(goodsId);
                throw new BizException(409, "「" + line.get("name") + "」库存不足（剩 " + (g == null ? 0 : g.getStock()) + "），请先入库补足后再核销");
            }
            GoodsFlow flow = new GoodsFlow();
            flow.setGoodsId(goodsId);
            flow.setGoodsName((String) line.get("name"));
            flow.setQty(qty);
            flow.setDirection(GoodsFlow.OUT);
            flow.setLocation((String) line.get("location"));
            flow.setFormId(form.getId());
            flow.setApplicantId(form.getApplicantId());
            flow.setOperatorId(operatorId);
            goodsFlowMapper.insert(flow);
        }
    }

    // ---- 工具 ----

    private OaForm requireForm(Long id) {
        OaForm form = formMapper.selectById(id);
        if (form == null) {
            throw new BizException(404, "单据不存在");
        }
        return form;
    }

    public static String nodeName(String formType, int level) {
        if (OaForm.TYPE_PURCHASE.equals(formType)) {
            return switch (level) {
                case 1 -> "部门负责人审批";
                case 2 -> "库存确认";
                case 3 -> "主管校领导审批";
                case 4 -> "招采中心确认";
                case 5 -> "采购验收";
                default -> "提交";
            };
        }
        return switch (level) {
            case 1 -> "一级审批";
            case 2 -> "二级审批";
            case 3 -> "三级审批";
            default -> "提交";
        };
    }

    private void writeLog(OaForm form, String action, Long operatorId, String note) {
        OaFlowLog log = new OaFlowLog();
        log.setFormId(form.getId());
        log.setAction(action);
        log.setLevel(OaFlowLog.SUBMIT.equals(action) ? 0 : form.getCurrentLevel());
        log.setNodeName(OaFlowLog.SUBMIT.equals(action) ? NODE_SUBMIT : nodeName(form.getFormType(), form.getCurrentLevel()));
        log.setOperatorId(operatorId);
        log.setNote(note);
        logMapper.insert(log);
    }

    private void finish(OaForm form, String status) {
        form.setStatus(status);
        form.setFinishTime(java.time.LocalDateTime.now());
        formMapper.updateById(form);
    }

    private List<Map<String, Object>> toRows(List<OaForm> forms) {
        Map<Long, String> names = forms.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(forms.stream().map(OaForm::getApplicantId).distinct().toList())
                        .stream().collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return forms.stream().map(f -> rowOf(f, names)).collect(Collectors.toList());
    }

    private Map<String, Object> rowOf(OaForm f, Map<Long, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("formType", f.getFormType());
        m.put("typeName", typeName(f.getFormType()));
        m.put("title", f.getTitle());
        m.put("applicantName", names.getOrDefault(f.getApplicantId(), ""));
        m.put("status", f.getStatus());
        m.put("currentLevel", f.getCurrentLevel());
        m.put("nodeName", f.getStatus().equals(OaForm.PENDING) ? nodeName(f.getFormType(), f.getCurrentLevel()) : "");
        m.put("createTime", f.getCreateTime());
        return m;
    }

    private Map<Long, String> userNames(OaForm form, List<OaFlowLog> logs) {
        List<Long> ids = new ArrayList<>(logs.stream().map(OaFlowLog::getOperatorId).distinct().toList());
        ids.add(form.getApplicantId());
        return userMapper.selectBatchIds(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(500, "明细序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDetail(String detail) {
        try {
            Object o = JSON.readValue(detail, Object.class);
            return o instanceof List ? (List<Map<String, Object>>) o : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObj(String detail) {
        try {
            Object o = JSON.readValue(detail == null ? "{}" : detail, Object.class);
            return o instanceof Map ? (Map<String, Object>) o : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 同场地同日已被 PENDING/APPROVED 单据占用则拒绝（撤回/驳回后释放，可重提；场地申请量小，Java 层过滤 detail JSON） */
    private void checkVenueFree(Long venueId, String useDate) {
        List<OaForm> held = formMapper.selectList(new LambdaQueryWrapper<OaForm>()
                .eq(OaForm::getFormType, OaForm.TYPE_VENUE)
                .in(OaForm::getStatus, List.of(OaForm.PENDING, OaForm.APPROVED)));
        for (OaForm f : held) {
            Map<String, Object> d = parseObj(f.getDetail());
            Object vid = d.get("venueId");
            if (vid instanceof Number n && n.longValue() == venueId && useDate.equals(d.get("useDate"))) {
                throw new BizException(400, "「" + d.get("venueName") + "」在 " + useDate + " 已有申请单（#" + f.getId() + "），请改期或联系管理员");
            }
        }
    }

    // ───────── 批43① 采购：抄送 / 附件 ─────────

    /** 抄送人配置（oa_purchase_cc 逗号分隔；管理员预设不可删，同钉钉） */
    private List<Long> ccIds() {
        String v = cfg("oa_purchase_cc");
        if (v.isEmpty()) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::parseLong).distinct().toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    /** 终态抄送（通过/驳回后通知预设抄送人；采购单专用，不写流转日志） */
    private void ccPurchase(OaForm form, boolean approved, String note, UserPrincipal operator) {
        if (!OaForm.TYPE_PURCHASE.equals(form.getFormType())) {
            return;
        }
        String applicantName = userNames(form, List.of()).getOrDefault(form.getApplicantId(), "");
        for (Long id : validUserIds(ccIds())) {
            notificationService.send(id, Notification.OA_RESULT, "抄送：采购申请" + (approved ? "已通过" : "已被驳回"),
                    applicantName + " 提交的「" + form.getTitle() + "」"
                            + (approved ? "已审批通过（" + operator.realName() + " 验收）" : "已被驳回")
                            + (note == null || note.isBlank() ? "" : "，意见：" + note), "/oa");
        }
    }

    /** 采购附件预上传（multipart→MinIO oa/ 前缀；submit 时只带 objectName 列表，同报修凭证模式） */
    public Map<String, Object> uploadPurchasePhoto(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "附件为空");
        }
        if (file.getSize() > PHOTO_MAX_SIZE) {
            throw new BizException(400, "附件不能超过 10MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) {
            throw new BizException(400, "仅支持 jpg/jpeg/png 格式");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(400, "读取附件失败");
        }
        String objectName = "oa/" + UUID.randomUUID() + "." + ext;
        pdfStore.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());
        return Map.of("photo", objectName);
    }

    /** 附件对象名（详情流回用；可见性同 detail） */
    public String purchasePhotoObject(Long formId, int idx) {
        OaForm form = requireForm(formId);
        if (!canView(form, AuthUtil.current())) {
            throw new BizException(403, "仅申请人与当前审批人可查看该单据");
        }
        if (!(parseObj(form.getDetail()).get("photos") instanceof List<?> list)
                || idx < 0 || idx >= list.size()) {
            throw new BizException(404, "附件不存在");
        }
        return String.valueOf(list.get(idx));
    }
}
