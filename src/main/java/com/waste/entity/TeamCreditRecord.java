package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 施工队信用记录：警告与扣款均留痕，作为后续施工队准入/评级依据 */
@Data
@Entity
@Table(name = "team_credit_record")
public class TeamCreditRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String recordNo;

    @Column(nullable = false, length = 64)
    private String constructionTeam;

    private Long taskId;        // 关联整改任务（可空）
    private Long violationId;   // 关联违规单（可空）

    /** WARNING 警告（限期内移走，不扣款） / PENALTY 扣款处罚（影响通道/被投诉/超时） */
    @Column(nullable = false, length = 16)
    private String type;

    /** 信用扣分：警告 0 分，扣款按规则计分 */
    @Column(nullable = false)
    private Integer scoreDeducted;

    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal fineAmount;

    @Column(nullable = false, length = 512)
    private String reason;

    private Long operatorId;
    @Column(length = 64)
    private String operatorName;

    private LocalDateTime createdAt = LocalDateTime.now();
}
