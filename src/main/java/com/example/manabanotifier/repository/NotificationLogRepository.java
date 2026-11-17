package com.example.manabanotifier.repository;

import com.example.manabanotifier.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByUserIdOrderBySentAtDesc(String userId);

    boolean existsByTaskIdAndUserIdAndMessageContainingAndStatus(Long taskId, String userId, String timingTag, String status);
}
