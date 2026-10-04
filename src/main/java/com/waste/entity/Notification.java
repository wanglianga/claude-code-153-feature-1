package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 平台通知（超时整改任务自动通知物业经理 / 业主本人等） */
@Data
@Entity
@Table(name = "notification")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long receiverId;

    @Column(nullable = false, length = 64)
    private String receiverName;

    /** PROPERTY_MANAGER 物业经理 / OWNER 业主 / WORKER 施工队 */
    @Column(nullable = false, length = 24)
    private String receiverRole;

    @Column(nullable = false, length = 32)
    private String channel;       // SMS 短信 / APP 站内 / VOICE 电话

    @Column(nullable = false, length = 64)
    private String subject;

    @Column(nullable = false, length = 512)
    private String content;

    private Long taskId;          // 关联整改任务（可空）

    private LocalDateTime sentAt = LocalDateTime.now();
    private LocalDateTime readAt;
}
