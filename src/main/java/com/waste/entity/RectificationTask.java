package com.waste.entity;

import com.waste.enums.PatrolResult;
import com.waste.enums.RectificationStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消防通道违规堆放整改任务（夜间巡查发起）。
 * 根据房号装修登记、施工队联系方式、现场照片、巡查时间生成；
 * 限期内移走仅记录警告；影响消防通道或被邻居投诉则按规则扣款并写入施工队信用；
 * 超时未完成自动通知物业经理和业主本人，占用时长/影响范围/二次巡查结果作为扣款依据。
 */
@Data
@Entity
@Table(name = "rectification_task")
public class RectificationTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String taskNo;

    @Column(nullable = false)
    private Long registrationId;

    private Long violationId;      // 关联消防通道违规单（可空）
    private Long complaintId;      // 关联邻居投诉（可空）

    // ---- 现场信息（巡查时录入）----
    @Column(nullable = false, length = 64)
    private String building;

    @Column(nullable = false, length = 32)
    private String roomNumber;

    @Column(nullable = false, length = 64)
    private String constructionTeam;

    @Column(nullable = false, length = 32)
    private String teamContact;        // 施工队联系方式（电话/联系人）

    @Column(nullable = false, length = 512)
    private String scenePhotos;        // 现场照片 URL，多个以逗号分隔

    @Column(nullable = false)
    private LocalDateTime patrolTime;  // 巡查时间（发现时间）

    @Column(nullable = false, length = 256)
    private String fireLaneLocation;   // 被占用消防通道位置

    @Column(nullable = false, length = 128)
    private String material;           // 堆放物（木板等）

    @Column(nullable = false, length = 256)
    private String impactScope;        // 影响范围（如：1号楼西侧消防通道，阻断疏散出口）

    /** 是否已经影响消防通道正常使用（阻断/半幅占用），为 true 时按规则直接扣款 */
    @Column(nullable = false)
    private boolean fireLaneBlocked;

    private Long patrolUserId;         // 巡查人（物业）
    @Column(length = 64)
    private String patrolUserName;

    // ---- 时限 ----
    @Column(nullable = false)
    private LocalDateTime deadline;

    // ---- 处置结果 ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RectificationStatus status = RectificationStatus.OPEN;

    private LocalDateTime clearedAt;            // 施工队移走时间
    @Column(length = 512)
    private String clearPhotos;                 // 移走后照片

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PatrolResult secondPatrolResult = PatrolResult.PENDING;
    private LocalDateTime secondPatrolTime;     // 二次巡查时间
    @Column(length = 512)
    private String secondPatrolPhotos;
    @Column(length = 256)
    private String secondPatrolNote;
    private Long secondPatrolUserId;
    @Column(length = 64)
    private String secondPatrolUserName;

    private LocalDateTime overdueNotifiedAt;    // 超时通知发送时间（幂等）

    /** 消防通道占用时长（分钟）= 移走/扣款时间 - 巡查发现时间 */
    private Long occupationMinutes;

    private java.math.BigDecimal deductionAmount; // 实际扣款金额（仅警告时为 0）
    private Long creditRecordId;                  // 施工队信用记录 ID
    private Long teamBillId;                      // 施工队罚款账单 ID

    @Column(length = 512)
    private String resolution;                  // 结案说明

    private LocalDateTime createdAt = LocalDateTime.now();
}
