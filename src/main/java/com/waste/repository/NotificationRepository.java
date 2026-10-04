package com.waste.repository;

import com.waste.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByReceiverIdOrderByIdDesc(Long receiverId);
    List<Notification> findByReceiverRoleOrderByIdDesc(String receiverRole);
    List<Notification> findByTaskIdOrderByIdDesc(Long taskId);
}
