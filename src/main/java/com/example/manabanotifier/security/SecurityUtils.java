package com.example.manabanotifier.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * アプリケーション共通のセキュリティ検証ユーティリティ。
 * SSRF / Open Redirect / CSRF / URLインジェクション対策を一元化する。
 */
public final class SecurityUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Google Apps Script ウェブアプリの正規パス形式のみ許可する。
     * - 通常アカウント: /macros/s/<deploymentId>/exec
     * - Google Workspace: /a/macros/<domain>/s/<deploymentId>/exec
     */
    private static final Pattern GAS_EXEC_PATH_PATTERN = Pattern.compile(
            "^(?:/a/macros/[A-Za-z0-9.-]{1,128}/s/|/macros/s/)[A-Za-z0-9_-]{10,200}/exec$");

    private static final Pattern GMAIL_MSG_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private SecurityUtils() {
    }

    /**
     * 暗号論的に安全なランダムトークン（256bit Base64URL）を生成する。
     */
    public static String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * タイミング攻撃を防ぐ定数時間文字列比較。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Google Apps Script ウェブアプリ（https://script.google.com/.../exec）の厳格検証。
     * Open Redirect および SSRF を防止する。
     */
    public static boolean isValidGoogleAppsScriptUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 500) {
            return false;
        }
        String trimmed = rawUrl.trim();
        if (containsControlChars(trimmed)) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (uri.getRawUserInfo() != null || uri.getPort() != -1) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !"script.google.com".equalsIgnoreCase(host)) {
                return false;
            }
            String path = uri.getPath();
            return path != null && GAS_EXEC_PATH_PATTERN.matcher(path).matches();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * GAS サーバ間通信のリダイレクト先として安全な Google 公式ホストのみ許可する（SSRF防止）。
     */
    public static boolean isAllowedGasRedirectTarget(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("script.google.com")
                || lower.equals("script.googleusercontent.com")
                || lower.endsWith(".googleusercontent.com");
    }

    /**
     * POST /auth/gas-callback の Origin / Referer が Google Apps Script または同一オリジンかを検証する。
     */
    public static boolean isTrustedGasCallbackSource(String originHeader, String refererHeader, String sameOrigin) {
        if (originHeader != null && !originHeader.isBlank()) {
            return isAllowedGasOrigin(originHeader.trim(), sameOrigin);
        }
        if (refererHeader != null && !refererHeader.isBlank()) {
            try {
                URI refUri = URI.create(refererHeader.trim());
                if (refUri.getScheme() == null || refUri.getHost() == null) {
                    return false;
                }
                String reconstructed = refUri.getScheme().toLowerCase(Locale.ROOT)
                        + "://" + refUri.getHost().toLowerCase(Locale.ROOT)
                        + (refUri.getPort() != -1 ? ":" + refUri.getPort() : "");
                return isAllowedGasOrigin(reconstructed, sameOrigin);
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return false;
    }

    private static boolean isAllowedGasOrigin(String origin, String sameOrigin) {
        if (sameOrigin != null && sameOrigin.equalsIgnoreCase(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
                return false;
            }
            int port = uri.getPort();
            if (port != -1 && port != 443) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.equals("script.google.com")
                    || lower.equals("script.googleusercontent.com")
                    || lower.endsWith("-script.googleusercontent.com")
                    || lower.endsWith(".googleusercontent.com");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 画面表示用の安全な外部 HTTP/HTTPS URL 検証（javascript: / data: / 制御文字を排除）。
     */
    public static String sanitizeHttpUrl(String rawUrl, int maxLength) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > maxLength || containsControlChars(trimmed)) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Gmail API のメッセージ ID が安全な英数字・ハイフン・アンダースコアのみか検証する。
     */
    public static boolean isValidGmailMessageId(String msgId) {
        return msgId != null && GMAIL_MSG_ID_PATTERN.matcher(msgId).matches();
    }

    /**
     * リモートアドレスがループバック（localhost）か判定する。
     */
    public static boolean isLoopbackAddress(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        return "127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }
}
