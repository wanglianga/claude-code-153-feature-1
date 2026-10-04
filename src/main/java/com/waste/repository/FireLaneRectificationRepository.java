package com.waste.repository;

import com.waste.entity.FireLaneRectification;
import com.waste.enums.RectifyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FireLaneRectificationRepository extends JpaRepository<FireLaneRectification, Long> {
    List<FireLaneRectification> findByRegistrationIdOrderByIdDesc(Long registrationId);
    List<FireLaneRectification> findByConstructionTeamOrderByIdDesc(String constructionTeam);
    List<FireLaneRectification> findByStatusOrderByIdDesc(RectifyStatus status);

    /** 到期仍未整改、且尚未发送超期通知的任务 */
    List<FireLaneRectification> findByStatusAndDeadlineBefore(RectifyStatus status, LocalDateTime now);

    List<FireLaneRectification> findByNeighborComplaintTrueAndStatus(RectifyStatus status);
}
