package com.aischool.server.security;

import com.aischool.server.entity.AuditLog;
import com.aischool.server.mapper.AuditLogMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 审计过滤器：记录 /api/** 的非 GET 请求（谁/何时/哪个接口/参数摘要/结果码）。
 * 密码类接口（/api/auth/**、/api/admin/user/**）与 multipart 上传不记请求体。
 * 审计失败绝不影响业务请求。
 */
public class AuditFilter extends OncePerRequestFilter {

    /** 登录接口回填身份的 request attribute 名（见 AuthController.login，认证完成时 SecurityContext 尚无用户） */
    public static final String ATTR_USER_ID = "audit.userId";
    public static final String ATTR_USERNAME = "audit.username";

    /** 请求体摘要最大长度 */
    private static final int BODY_LIMIT = 512;

    private final AuditLogMapper auditLogMapper;

    public AuditFilter(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean auditable = uri.startsWith("/api/") && !"GET".equalsIgnoreCase(request.getMethod());
        if (!auditable) {
            chain.doFilter(request, response);
            return;
        }
        boolean sensitive = uri.startsWith("/api/auth/") || uri.startsWith("/api/admin/user/");
        boolean multipart = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith("multipart/");
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        StatusCapture status = new StatusCapture(response);
        try {
            chain.doFilter(wrapped, status);
        } finally {
            try {
                AuditLog log = new AuditLog();
                Object attrUserId = request.getAttribute(ATTR_USER_ID);
                Object attrUsername = request.getAttribute(ATTR_USERNAME);
                if (attrUserId instanceof Long uid && attrUsername instanceof String uname) {
                    // 登录接口：控制器认证成功后回填的身份
                    log.setUserId(uid);
                    log.setUsername(uname);
                } else {
                    try {
                        var user = AuthUtil.current();
                        log.setUserId(user.userId());
                        log.setUsername(user.username());
                    } catch (Exception ignore) {
                        // 未登录（被 401 拒绝的请求也留痕，用户为空）
                    }
                }
                log.setMethod(request.getMethod());
                log.setUri(uri.length() > 255 ? uri.substring(0, 255) : uri);
                if (!sensitive && !multipart) {
                    String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8).trim();
                    if (!body.isEmpty()) {
                        log.setBody(body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) : body);
                    }
                }
                log.setStatus(status.status());
                log.setIp(clientIp(request));
                auditLogMapper.insert(log);
            } catch (Exception e) {
                logger.warn("审计日志写入失败: " + uri + " - " + e.getMessage());
            }
        }
    }

    private String clientIp(HttpServletRequest request) {
        // 反向代理链下取真实客户端 IP：X-Forwarded-For 第一跳，缺则 X-Real-IP（nginx 直传），最后才是 socket 地址
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    /** 只捕获状态码的轻量响应包装 */
    private static final class StatusCapture extends jakarta.servlet.http.HttpServletResponseWrapper {
        private int status = 200;

        StatusCapture(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            super.setStatus(sc);
            this.status = sc;
        }

        @Override
        public void sendError(int sc) throws IOException {
            super.sendError(sc);
            this.status = sc;
        }

        int status() {
            return status;
        }
    }
}
