package org.zhzssp.memorandum.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 给 HTML 响应打上分栏指纹，并在旧模板仍把 Agent 写成 fixed 浮层时注入 split CSS/JS。
 * 浏览器 Network 里应能看到 {@code X-LP-Agent-Layout: split-v11}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentLayoutFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/ws/") || uri.startsWith("/sse") || uri.startsWith("/actuator")) {
            return true;
        }
        int dot = uri.lastIndexOf('.');
        if (dot > uri.lastIndexOf('/')) {
            String ext = uri.substring(dot).toLowerCase();
            return ext.matches("\\.(js|css|png|jpe?g|gif|svg|ico|woff2?|map|webp|mp4|pdf|json)$");
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(AgentLayoutAssets.HEADER, AgentLayoutAssets.VERSION);
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapped);

        String contentType = wrapped.getContentType();
        byte[] body = wrapped.getContentAsByteArray();
        if (body.length == 0 || contentType == null || !contentType.toLowerCase().contains("text/html")) {
            wrapped.copyBodyToResponse();
            return;
        }

        Charset charset = resolveCharset(wrapped);
        String html = new String(body, charset);
        String rewritten = AgentLayoutAssets.inject(html);
        if (rewritten.equals(html)) {
            wrapped.copyBodyToResponse();
            return;
        }
        byte[] out = rewritten.getBytes(charset);
        response.setContentType(contentType);
        response.setCharacterEncoding(charset.name());
        response.setContentLength(out.length);
        response.getOutputStream().write(out);
    }

    private static Charset resolveCharset(ContentCachingResponseWrapper wrapped) {
        String encoding = wrapped.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
