package com.example.manabanotifier.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * すべての Thymeleaf テンプレートへ CSRF トークンを自動供給するアドバイス。
 */
@ControllerAdvice
public class SecurityControllerAdvice {

    @ModelAttribute("csrfToken")
    public String csrfToken(HttpSession session) {
        return SecurityFilter.ensureCsrfToken(session);
    }
}
