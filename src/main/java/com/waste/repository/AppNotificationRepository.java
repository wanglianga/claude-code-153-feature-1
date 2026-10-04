package com.waste.repository;

import com.waste.entity.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {
    List<AppNotification> findByReceiverIdOrderByIdDesc(Long receiverId);
    long countByReceiverIdAndReadFlagFalse(Long receiverId);
}
