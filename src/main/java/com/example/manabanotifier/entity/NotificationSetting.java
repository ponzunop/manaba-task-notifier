package com.example.manabanotifier.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 通知設定（notification_settings）エンティティ。
 * 通知時刻（例: "24,5,1"）および科目ごとの通知 ON/OFF 設定を保持する。
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Long settingId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "notify_timings")
    private String notifyTimings;

    @Column(name = "course_name")
    private String courseName;

    @Column(name = "is_enabled")
    private boolean enabled;

    public NotificationSetting() {
    }

    public Long getSettingId() {
        return settingId;
    }

    public void setSettingId(Long settingId) {
        this.settingId = settingId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNotifyTimings() {
        return notifyTimings;
    }

    public void setNotifyTimings(String notifyTimings) {
        this.notifyTimings = notifyTimings;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
