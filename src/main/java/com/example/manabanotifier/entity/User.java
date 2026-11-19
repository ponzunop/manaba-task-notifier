package com.example.manabanotifier.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * ログイン情報（users）エンティティ。
 * Google OAuth 2.0 認証情報および Web Push 購読情報を保持する。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id")
    private String userId;

    private String email;

    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String accessToken;

    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    @Column(columnDefinition = "TEXT")
    private String pushEndpoint;

    @Column(columnDefinition = "TEXT")
    private String pushKeys;

    @Column(columnDefinition = "TEXT")
    private String gasUrl;

    private String gasToken;

    private String authType;

    public User() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getPushEndpoint() {
        return pushEndpoint;
    }

    public void setPushEndpoint(String pushEndpoint) {
        this.pushEndpoint = pushEndpoint;
    }

    public String getPushKeys() {
        return pushKeys;
    }

    public void setPushKeys(String pushKeys) {
        this.pushKeys = pushKeys;
    }

    public String getGasUrl() {
        return gasUrl;
    }

    public void setGasUrl(String gasUrl) {
        this.gasUrl = gasUrl;
    }

    public String getGasToken() {
        return gasToken;
    }

    public void setGasToken(String gasToken) {
        this.gasToken = gasToken;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }
}
