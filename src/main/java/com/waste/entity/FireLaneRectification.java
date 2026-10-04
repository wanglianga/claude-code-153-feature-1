package com.waste.entity;

import com.waste.enums.ImpactLevel;
import com.waste.enums.RectifyStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消防通道违规堆放整改任务（夜间巡查发现）。
 * 根据房号装修登记、施工队联系方式、现场照片、巡查时间生成；
 * 限期内移走仅记录警告，影响消防通道/邻居投诉/超期未清则按规则扣款并计入施工队信用。
 */
@Data
@Entity
@Table(name = "fire_lane_rectification")
public class FireLaneRectification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String taskNo;

    @Column(nullable = false)
    private Long registrationId;

    /** 冗余房号/施工队/联系方式，便于通知与追溯 */
    @Column(nullable = false, length = 32)
    private String building;

    @Column(nullable = false, length = 32)
    private String roomNumber;

    @Column(nullable = false, length = 64)
    private String constructionTeam;

    @Column(length = 32)
    private String teamPhone;

    private Long ownerId;

    @Column(nullable = false, length = 64)
    private String ownerName;

    /** 夜间巡查时间 */
    @Column(nullable = false)
    private LocalDateTime patrolTime;

    /** 现场照片 URL（多张逗号分隔） */
    @Column(nullable = false, length = 512)
    private String scenePhotos;

    @Column(nullable = false, length = 256)
    private String stackingLocation;

    /** 堆放物（木板等）与数量描述 */
    @Column(nullable = false, length = 256)
    private String materialDesc;

    /** 巡查登记人（物业夜巡人员） */
    private Long patrolUserId;

    @Column(length = 64)
    private String patrolUserName;

    /** 整改时限（截止时间） */
    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RectifyStatus status = RectifyStatus.PENDING;

    /** 最近一次二次巡查结果（可空） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private com.waste.enums.PatrolResult patrolResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImpactLevel impactLevel = ImpactLevel.NONE;

    /** 是否产生邻居投诉 */
    @Column(nullable = false)
    private boolean neighborComplaint = false;

    /** 实际移走时间（可空，未移走） */
    private LocalDateTime clearedAt;

    /** 消防通道实际占用时长（分钟），移走/扣款时结算 */
    private Long occupiedMinutes;

    /** 影响范围说明（阻断通道/影响住户等） */
    @Column(length = 512)
    private String impactScope;

    /** 二次巡查记录（时间 + 结果 + 照片，多次追加） */
    @Column(length = 1024)
    private String rePatrolNote;

    /** 最终扣款金额（0 表示仅警告） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deductionAmount = BigDecimal.ZERO;

    /** 扣款生成的施工队罚款账单号（可空） */
    @Column(length = 32)
    private String teamBillNo;

    /** 超期是否已自动通知（幂等） */
    @Column(nullable = false)
    private boolean overdueNotified = false;

    private LocalDateTime createdAt = LocalDateTime.now();
}
