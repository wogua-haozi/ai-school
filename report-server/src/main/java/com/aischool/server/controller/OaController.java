package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Goods;
import com.aischool.server.mapper.GoodsMapper;
import com.aischool.server.service.oa.OaService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 行政办公审批（批9）：公章使用申请（三级）/物资申领（级数可配）。
 * 教师/班主任/领导发起；审批人由管理端配置（ADMIN 可代审，见 OaService.handle）。
 */
@RestController
@RequestMapping("/api/oa")
@RequiredArgsConstructor
public class OaController {

    private final OaService oaService;
    private final GoodsMapper goodsMapper;
    private final PdfStoreService pdfStore;

    @PostMapping("/submit")
    public ApiResponse<Map<String, Object>> submit(@RequestBody OaService.SubmitReq req) {
        return ApiResponse.ok(rowOf(oaService.submit(req)));
    }

    /** 我的申请（status 可选筛选） */
    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my(@RequestParam(required = false) String status) {
        return ApiResponse.ok(oaService.myList(status));
    }

    /** 待我审批（当前节点审批人=本人） */
    @GetMapping("/todo")
    public ApiResponse<List<Map<String, Object>>> todo() {
        return ApiResponse.ok(oaService.todoList());
    }

    /** 单据详情（申请人与当前审批人可见；ADMIN 全量） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(oaService.detail(id));
    }

    /** 审批动作：AGREE/REJECT（当前级审批人或 ADMIN）；REVOKE 仅申请人本人 */
    @PostMapping("/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id, @RequestBody OaService.HandleReq req) {
        oaService.handle(id, req);
        return ApiResponse.ok();
    }

    /** 招采工作台：物资单列表（批33 两段式；status=APPROVED 待领取 / ISSUED 已核销，空=全部） */
    @GetMapping("/procurement/list")
    public ApiResponse<List<Map<String, Object>>> procurementList(@RequestParam(required = false) String status) {
        return ApiResponse.ok(oaService.procurementList(status));
    }

    /** 物资核销出库（批33）：招采/管理员在申请人领取后核销，此时才扣库存出库 */
    @PutMapping("/{id}/issue")
    public ApiResponse<Void> issue(@PathVariable Long id) {
        oaService.issue(id);
        return ApiResponse.ok();
    }

    /** 公章一审审批人候选池（批52①：申请人提交时从行政候选名单自选 1~多人或签；名单=管理端 oa_seal_l1 配置） */
    @GetMapping("/seal/candidates")
    public ApiResponse<List<Map<String, Object>>> sealCandidates() {
        if ("PARENT".equals(com.aischool.server.security.AuthUtil.current().role())) {
            throw new BizException(403, "家长账号无需使用行政办公审批");
        }
        return ApiResponse.ok(oaService.sealCandidates());
    }

    /** 发起页物资下拉（可申领字典；库存实时） */
    @GetMapping("/goods")
    public ApiResponse<List<Map<String, Object>>> goods() {
        if ("PARENT".equals(com.aischool.server.security.AuthUtil.current().role())) {
            throw new BizException(403, "家长账号无需申领物资");
        }
        List<Goods> rows = goodsMapper.selectList(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getStatus, 1).orderByAsc(Goods::getName));
        return ApiResponse.ok(rows.stream().<Map<String, Object>>map(g -> Map.of(
                "id", g.getId(),
                "name", g.getName(),
                "unit", g.getUnit(),
                "stock", g.getStock(),
                "location", g.getLocation() == null ? "" : g.getLocation())).toList());
    }

    // ───────── 批43① 采购附件 ─────────

    /** 附件预上传（multipart 单文件→MinIO oa/ 前缀；submit 时只带返回的 objectName） */
    @PostMapping("/purchase/photo")
    public ApiResponse<Map<String, Object>> purchasePhoto(@RequestParam("photo") MultipartFile photo) {
        return ApiResponse.ok(oaService.uploadPurchasePhoto(photo));
    }

    /** 附件预览（inline 流式+ETag+缓存，同报修凭证模式；可见性同单据详情） */
    @GetMapping("/purchase/file/{id}")
    public ResponseEntity<InputStreamResource> purchaseFile(@PathVariable Long id,
                                                            @RequestParam(defaultValue = "0") int idx) {
        String objectName = oaService.purchasePhotoObject(id, idx);
        java.io.InputStream in = pdfStore.download(objectName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeOf(objectName)))
                .eTag("\"" + objectName + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(new InputStreamResource(in));
    }

    private String contentTypeOf(String objectName) {
        String ext = objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ext.equals("png") ? "image/png" : "image/jpeg";
    }

    private Map<String, Object> rowOf(com.aischool.server.entity.OaForm f) {
        return Map.of("id", f.getId(), "status", f.getStatus(), "title", f.getTitle());
    }
}
