package com.example.manabanotifier.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * HTTP セキュリティヘッダ付与および CSRF トークン検証フィルタ。
 * - 全レスポンスに X-Frame-Options / X-Content-Type-Options / CSP / Referrer-Policy 等を付与
 * - すべての状態変更リクエスト（POST/PUT/DELETE/PATCH）で Synchronizer Token パターンによる CSRF 検証を実施
 * - GAS ブラウザ連携コールバック（POST /auth/gas-callback）のみ Google Apps Script 由来 Origin/Referer を厳格検証
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    public static final String SESSION_CSRF_TOKEN = "CSRF_TOKEN";
    public static final String PARAM_CSRF = "_csrf";
    public static final String HEADER_CSRF = "X-CSRF-TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final String CSP_POLICY =
            "default-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; "
                    + "connect-src 'self'; "
                    + "form-action 'self' https://script.google.com https://accounts.google.com; "
                    + "frame-ancestors 'none'; "
                    + "base-uri 'self'";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. セキュリティヘッダ付与
        applySecurityHeaders(response, path);

        // 2. 静的リソースは CSRF チェック不要
        if (isStaticResource(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. セッションに CSRF トークンを確保（GET 画面表示時に埋め込めるようにする）
        HttpSession session = request.getSession(true);
        String expectedToken = ensureCsrfToken(session);

        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
        if (!SAFE_METHODS.contains(method)) {
            if ("/auth/gas-callback".equals(path)) {
                // GAS HtmlService からのクロスサイト POST は SameSite=Lax のためセッション Cookie が付かず、
                // さらにサンドボックス iframe (userCodeAppPanel) からのフォーム送信では Origin: null / Referer なしになる。
                // そのため Google Apps Script 公式オリジン、またはサンドボックス iframe からの
                // トップレベルフォーム送信（Origin: null + Sec-Fetch-Mode: navigate + Sec-Fetch-Dest: document）のみ許可し、
                // 悪意ある外部ドメインや XHR/fetch を確実に遮断する。
                String origin = request.getHeader("Origin");
                String referer = request.getHeader("Referer");
                String secFetchMode = request.getHeader("Sec-Fetch-Mode");
                String secFetchDest = request.getHeader("Sec-Fetch-Dest");
                String sameOrigin = buildRequestOrigin(request);
                if (!SecurityUtils.isTrustedGasCallbackSource(origin, referer, sameOrigin, secFetchMode, secFetchDest)) {
                    log.warn("不正なオリジンからの GAS コールバックを拒否しました: origin={}, referer={}, mode={}, dest={}",
                            origin, referer, secFetchMode, secFetchDest);
                    sendForbidden(request, response, "不正な送信元からのリクエストです。");
                    return;
                }
            } else {
                String submittedToken = request.getHeader(HEADER_CSRF);
                if (submittedToken == null || submittedToken.isBlank()) {
                    submittedToken = request.getParameter(PARAM_CSRF);
                }
                if (!SecurityUtils.constantTimeEquals(expectedToken, submittedToken)) {
                    log.warn("CSRF トークン検証に失敗しました: method={}, path={}, remote={}",
                            method, path, request.getRemoteAddr());
                    sendForbidden(request, response, "不正なリクエスト（CSRFトークン不一致）です。画面を再読み込みして再度お試しください。");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    public static String ensureCsrfToken(HttpSession session) {
        Object existing = session.getAttribute(SESSION_CSRF_TOKEN);
        if (existing instanceof String token && !token.isBlank()) {
            return token;
        }
        String generated = SecurityUtils.generateSecureToken();
        session.setAttribute(SESSION_CSRF_TOKEN, generated);
        return generated;
    }

    private void applySecurityHeaders(HttpServletResponse response, String path) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", CSP_POLICY);

        if (!isStaticResource(path)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }
    }

    private boolean isStaticResource(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.equals("/sw.js")
                || path.equals("/favicon.ico");
    }

    private String buildRequestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean isDefaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return isDefaultPort
                ? scheme + "://" + host
                : scheme + "://" + host + ":" + port;
    }

    private void sendForbidden(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        if ((accept != null && accept.contains("application/json"))
                || (contentType != null && contentType.contains("application/json"))
                || request.getRequestURI().startsWith("/api/")
                || "/auth/push-subscribe".equals(request.getRequestURI())) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":\"error\",\"message\":\"Forbidden\"}");
        } else {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(message);
        }
    }
}
