package com.example.manabanotifier.repository;

import com.example.manabanotifier.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByUserIdOrderByDeadlineAsc(String userId);

    List<Task> findByUserIdAndSubmittedFalseOrderByDeadlineAsc(String userId);

    List<Task> findBySubmittedFalse();

    Optional<Task> findByUserIdAndTitleAndCourseName(String userId, String title, String courseName);
}
