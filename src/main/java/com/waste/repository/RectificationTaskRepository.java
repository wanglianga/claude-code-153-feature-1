package com.waste.repository;

import com.waste.entity.RectificationTask;
import com.waste.enums.RectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RectificationTaskRepository extends JpaRepository<RectificationTask, Long> {
    List<RectificationTask> findByRegistrationIdOrderByIdDesc(Long registrationId);
    List<RectificationTask> findByConstructionTeamOrderByIdDesc(String constructionTeam);
    List<RectificationTask> findByStatusOrderByIdDesc(RectificationStatus status);
    List<RectificationTask> findByStatusInAndDeadlineBefore(List<RectificationStatus> statuses, LocalDateTime now);
}
