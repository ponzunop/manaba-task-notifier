package com.example.manabanotifier;

import com.example.manabanotifier.entity.User;
import com.example.manabanotifier.repository.UserRepository;
import com.example.manabanotifier.security.SecurityFilter;
import com.example.manabanotifier.security.SecurityUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * セキュリティ修正の自動回帰テスト（認証偽装・SSRF・Open Redirect・CSRF・OAuth state・セキュリティヘッダ・GAS連携）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sectestdb;DB_CLOSE_DELAY=-1",
        "spring.h2.console.enabled=false"
})
class SecurityHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void unsignedUserIdCookieCannotBypassAuthentication() throws Exception {
        User victim = new User();
        victim.setUserId("user-victim12345");
        victim.setEmail("victim@chibatech.ac.jp");
        victim.setDisplayName("Victim");
        victim.setAuthType("GAS_BROWSER");
        userRepository.save(victim);

        // 署名なし user_id Cookie を送信しても認証バイパスできないこと
        mockMvc.perform(get("/tasks").cookie(new Cookie("user_id", "user-victim12345")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/settings").cookie(new Cookie("user_id", "user-victim12345")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/history").cookie(new Cookie("user_id", "user-victim12345")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/api/notifications/recent").cookie(new Cookie("user_id", "user-victim12345")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersArePresentOnLoginPage() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Referrer-Policy"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void csrfProtectionBlocksStateChangingPostWithoutToken() throws Exception {
        mockMvc.perform(post("/auth/login").param("email", "test@example.com"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/auth/oauth-config")
                        .param("clientId", "evil-id")
                        .param("clientSecret", "evil-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getLogoutIsRejectedWithMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/auth/logout"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void openRedirectAndSsrfUrlsAreRejected() throws Exception {
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl("https://evil.example.com/exec")).isFalse();
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl("http://127.0.0.1:8080/macros/s/abc123456789/exec")).isFalse();
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl("https://script.google.com.evil.com/macros/s/abc123456789/exec")).isFalse();
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl(
                "https://script.google.com/a/macros/chibatech.ac.jp/s/AKfycbyUhoA5hshULJndfIheqnALZOJngywTkw9SetzBA5epdvQAyBnIwCbynsasq1l5KK_PLA/exec"))
                .isTrue();
        assertThat(SecurityUtils.isValidGoogleAppsScriptUrl(
                "https://script.google.com/macros/s/AKfycby1234567890abcdef/exec"))
                .isTrue();

        MvcResult loginPage = mockMvc.perform(get("/login")).andReturn();
        MockHttpSession session = (MockHttpSession) loginPage.getRequest().getSession(false);
        String csrf = (String) session.getAttribute(SecurityFilter.SESSION_CSRF_TOKEN);

        // 悪意のある外部URLへの Open Redirect が拒否され 302 にならないこと
        mockMvc.perform(post("/auth/gas-browser-start")
                        .session(session)
                        .param("_csrf", csrf)
                        .param("gasUrl", "https://evil.example.com/phishing"))
                .andExpect(status().isOk());

        // 内部メタデータアドレスへの SSRF が拒否されること
        mockMvc.perform(post("/auth/gas-login")
                        .session(session)
                        .param("_csrf", csrf)
                        .param("gasUrl", "http://169.254.169.254/latest/meta-data/"))
                .andExpect(status().isOk());
    }

    @Test
    void gasCallbackRejectsUntrustedOriginAndAcceptsGoogleAppsScriptOrigin() throws Exception {
        String payload = """
                {
                  "email": "student-sec@chibatech.ac.jp",
                  "gasUrl": "https://script.google.com/a/macros/chibatech.ac.jp/s/AKfycby1234567890abcdef/exec",
                  "emails": [
                    {
                      "subject": "[manaba] 小テスト公開のお知らせ",
                      "from": "manaba <do-not-reply@manaba.jp>",
                      "body": "[コース名] : サイバーセキュリティ\\n[小テスト名] : 第9回小テスト\\n[受付開始日時] : 2026-11-19 10:00\\n[受付終了日時] : 2026-11-26 23:59\\nPC : https://cit.manaba.jp/ct/course_1_query_2"
                    }
                  ]
                }
                """;

        // 悪意のある外部オリジンからのクロスサイト POST は 403 拒否
        mockMvc.perform(post("/auth/gas-callback")
                        .header("Origin", "https://evil.example.com")
                        .param("payload", payload))
                .andExpect(status().isForbidden());

        // Origin も Referer もないリクエストも 403 拒否
        mockMvc.perform(post("/auth/gas-callback")
                        .param("payload", payload))
                .andExpect(status().isForbidden());

        // Google Apps Script サンドボックスオリジンからの正当な POST は成功しセッション確立
        MvcResult gasResult = mockMvc.perform(post("/auth/gas-callback")
                        .header("Origin", "https://n-abc123-0lu-script.googleusercontent.com")
                        .param("payload", payload))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks?synced=true"))
                .andReturn();

        MockHttpSession authedSession = (MockHttpSession) gasResult.getRequest().getSession(false);
        assertThat(authedSession).isNotNull();
        assertThat(authedSession.getAttribute("user_id")).isNotNull();

        // 確立済みセッションで /tasks・/settings・/history にアクセス可能
        mockMvc.perform(get("/tasks").session(authedSession)).andExpect(status().isOk());
        mockMvc.perform(get("/settings").session(authedSession)).andExpect(status().isOk());
        mockMvc.perform(get("/history").session(authedSession)).andExpect(status().isOk());

        // 既に GAS_BROWSER で登録済みのメールアドレスに対して未認証の /auth/login でなりすましできないこと
        MvcResult freshLogin = mockMvc.perform(get("/login")).andReturn();
        MockHttpSession attackerSession = (MockHttpSession) freshLogin.getRequest().getSession(false);
        String attackerCsrf = (String) attackerSession.getAttribute(SecurityFilter.SESSION_CSRF_TOKEN);
        mockMvc.perform(post("/auth/login")
                        .session(attackerSession)
                        .param("_csrf", attackerCsrf)
                        .param("email", "student-sec@chibatech.ac.jp"))
                .andExpect(status().isOk());
        assertThat(attackerSession.getAttribute("user_id")).isNull();

        // CSRF トークン付きの POST ログアウトは正常動作しセッションを破棄すること
        String authedCsrf = (String) authedSession.getAttribute(SecurityFilter.SESSION_CSRF_TOKEN);
        mockMvc.perform(post("/auth/logout")
                        .session(authedSession)
                        .param("_csrf", authedCsrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout=true"));
    }

    @Test
    void oauthCallbackRejectsMissingState() throws Exception {
        mockMvc.perform(get("/auth/callback").param("code", "fake-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=oauth_failed"));
    }
}
