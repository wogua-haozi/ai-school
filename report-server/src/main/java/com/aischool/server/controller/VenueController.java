package com.aischool.server.controller;

import com.aischool.server.common.ApiResponse;
import com.aischool.server.common.BizException;
import com.aischool.server.entity.Venue;
import com.aischool.server.mapper.VenueMapper;
import com.aischool.server.security.AuthUtil;
import com.aischool.server.service.oa.OaService;
import com.aischool.server.service.report.PdfStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 场地预约（批11 字典 → 批53③ 重构）：列表/详情带当日时段占用（预约页时段条、会议总览共用），
 * 照片走 MinIO（同微光模式，JWT 内联预览）；管理端 CRUD/照片上传在 AdminOaController。
 */
@RestController
@RequestMapping("/api/venue")
@RequiredArgsConstructor
public class VenueController {

    private final VenueMapper venueMapper;
    private final OaService oaService;
    private final PdfStoreService pdfStore;

    /**
     * 场地列表：不带 date=教师端下拉兼容口径（基础字段）；
     * 带 date=预约页/会议总览（+photoUrl/equip/bookings/totalCount，全体教师互见）。
     */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String date) {
        checkTeacher();
        String day = checkDate(date);
        Map<Long, OaService.VenueDayStat> stats = day == null ? Map.of() : oaService.venueDayStats(day);
        List<Map<String, Object>> out = venueMapper.selectList(new LambdaQueryWrapper<Venue>()
                        .eq(Venue::getStatus, 1).orderByAsc(Venue::getId))
                .stream().<Map<String, Object>>map(v -> {
                    Map<String, Object> m = base(v);
                    if (day != null) {
                        OaService.VenueDayStat st = stats.get(v.getId());
                        m.put("bookings", st == null ? List.of() : st.getBookings());
                        m.put("totalCount", st == null ? 0 : st.getTotalCount());
                    }
                    return m;
                }).toList();
        return ApiResponse.ok(out);
    }

    /** 场地详情（批53③ 预约页）：全字段 + 当日占用记录 + 累计已批次数 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @RequestParam(required = false) String date) {
        checkTeacher();
        Venue v = venueMapper.selectById(id);
        if (v == null || v.getStatus() == null || v.getStatus() != 1) {
            throw new BizException(404, "场地不存在或已停用");
        }
        String day = checkDate(date);
        if (day == null) {
            throw new BizException(400, "请指定日期");
        }
        OaService.VenueDayStat st = oaService.venueDayStats(day).get(id);
        Map<String, Object> m = base(v);
        m.put("bookings", st == null ? List.of() : st.getBookings());
        m.put("totalCount", st == null ? 0 : st.getTotalCount());
        return ApiResponse.ok(m);
    }

    /** 场地照片预览（inline 流式 + ETag 缓存，同微光模式；无照片 404 由前端占位纹理兜底） */
    @GetMapping("/photo/{id}")
    public ResponseEntity<InputStreamResource> photo(@PathVariable Long id) {
        checkTeacher();
        Venue v = venueMapper.selectById(id);
        if (v == null || v.getPhoto() == null) {
            throw new BizException(404, "场地照片不存在");
        }
        InputStream in = pdfStore.download(v.getPhoto());
        String ext = v.getPhoto().substring(v.getPhoto().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ResponseEntity.ok()
                .contentType(ext.equals("png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .eTag("\"" + v.getPhoto() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(new InputStreamResource(in));
    }

    private Map<String, Object> base(Venue v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("name", v.getName());
        m.put("location", v.getLocation() == null ? "" : v.getLocation());
        m.put("capacity", v.getCapacity());
        m.put("photoUrl", v.getPhoto() == null ? null : "/api/venue/photo/" + v.getId());
        m.put("equip", v.getEquip() == null ? "" : v.getEquip());
        return m;
    }

    private void checkTeacher() {
        if ("PARENT".equals(AuthUtil.current().role())) {
            throw new BizException(403, "家长账号无需申请场地");
        }
    }

    /** null/空=未指定；非空须 YYYY-MM-DD */
    private static String checkDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            throw new BizException(400, "日期参数不合法");
        }
        return date;
    }
}
