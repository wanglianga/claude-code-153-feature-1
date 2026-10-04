package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内通知：整改超期自动通知物业经理与业主本人等 */
@Data
@Entity
@Table(name = "app_notification")
public class AppNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long receiverId;

    @Column(nullable = false, length = 64)
    private String receiverName;

    /** PROPERTY_MANAGER / OWNER / CONSTRUCTION_TEAM */
    @Column(nullable = false, length = 24)
    private String receiverRole;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 512)
    private String content;

    /** 业务类型：FIRE_LANE_OVERDUE 等 */
    @Column(nullable = false, length = 32)
    private String bizType;

    private Long bizId;

    @Column(nullable = false)
    private boolean readFlag = false;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime readAt;
}
