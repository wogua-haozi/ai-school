package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.report.PdfStoreService;
import com.aischool.server.service.repair.RepairService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 教师端报修（批10）：提交（文字+拍照凭证）+我的报修+凭证照片回图 */
@RestController
@RequestMapping("/api/repair")
@RequiredArgsConstructor
public class RepairController {

    private final RepairService repairService;
    private final PdfStoreService pdfStore;

    /** 提交（multipart：location + description + photos[] 可选 ≤3） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestParam String location,
                                                   @RequestParam String description,
                                                   @RequestParam(value = "photos", required = false) List<MultipartFile> photos) {
        var user = AuthUtil.current();
        if ("PARENT".equals(user.role())) {
            throw new BizException(403, "家长账号无需报修");
        }
        return ApiResponse.ok(repairService.create(user, location, description, photos));
    }

    /** 我的报修 */
    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my(@RequestParam(required = false) String status) {
        return ApiResponse.ok(repairService.my(AuthUtil.current(), status));
    }

    /** 详情（报修人本人或管理员） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(repairService.detail(id, AuthUtil.current()));
    }

    /** 批40e 撤回普适（批63 放宽）：发起人撤回报修单（未接单时） */
    @PutMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        repairService.cancel(id, AuthUtil.current());
        return ApiResponse.ok();
    }

    /** 批63 工作台：后勤=待审批；维修工=待接单抢单池+我的维修中 */
    @GetMapping("/workbench")
    public ApiResponse<Map<String, Object>> workbench() {
        return ApiResponse.ok(repairService.workbench(AuthUtil.current()));
    }

    /** 批63 后勤审批：approved=true 通过（转维修工抢单）/ false 拒绝（通知报修人） */
    @PutMapping("/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable Long id, @RequestBody ApproveReq req) {
        repairService.approve(id, AuthUtil.current(), Boolean.TRUE.equals(req.getApproved()), req.getNote());
        return ApiResponse.ok();
    }

    @Data
    public static class ApproveReq {
        private Boolean approved;
        private String note;
    }

    /** 批63 维修工接单（抢单，先到先得） */
    @PutMapping("/{id}/claim")
    public ApiResponse<Void> claim(@PathVariable Long id) {
        repairService.claim(id, AuthUtil.current());
        return ApiResponse.ok();
    }

    /** 批63 接单人完成（通知报修人+抄送名单） */
    @PutMapping("/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody CompleteReq req) {
        repairService.complete(id, AuthUtil.current(), req.getNote());
        return ApiResponse.ok();
    }

    @Data
    public static class CompleteReq {
        private String note;
    }

    /** 凭证照片预览（inline，流式+ETag+缓存，同微光照片模式） */
    @GetMapping("/file/{id}")
    public ResponseEntity<InputStreamResource> file(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "0") int idx) {
        String objectName = repairService.photoObject(id, idx, AuthUtil.current());
        InputStream in = pdfStore.download(objectName);
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
}
