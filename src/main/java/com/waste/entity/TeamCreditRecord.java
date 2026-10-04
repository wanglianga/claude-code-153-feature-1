package com.waste.entity;

import com.waste.enums.CreditLevel;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 施工队信用记录：消防通道违规堆放处置结果写入施工队信用档案 */
@Data
@Entity
@Table(name = "team_credit_record")
public class TeamCreditRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String creditNo;

    @Column(nullable = false, length = 64)
    private String constructionTeam;

    /** 关联整改任务 */
    @Column(nullable = false)
    private Long rectificationId;

    @Column(nullable = false, length = 32)
    private String taskNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CreditLevel level;

    /** 信用扣分（警告扣 warningScore，扣款扣 deductScore） */
    @Column(nullable = false)
    private int scoreChange;

    @Column(nullable = false, length = 512)
    private String reason;

    private Long operatorId;

    @Column(length = 64)
    private String operatorName;

    private LocalDateTime createdAt = LocalDateTime.now();
}
