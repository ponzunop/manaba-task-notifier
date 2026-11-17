package com.example.manabanotifier.repository;

import com.example.manabanotifier.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    List<NotificationSetting> findByUserId(String userId);

    Optional<NotificationSetting> findByUserIdAndCourseName(String userId, String courseName);
}
