package com.waste.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 计费与风控规则参数（application.yml waste.pricing.*） */
@Data
@Component
@ConfigurationProperties(prefix = "waste.pricing")
public class WasteProperties {
    private BigDecimal bagUnitPrice = new BigDecimal("8.0");
    private BigDecimal extraBagMultiplier = new BigDecimal("1.5");
    private BigDecimal avgBagWeightKg = new BigDecimal("25.0");
    private BigDecimal collectorPricePerTon = new BigDecimal("120.0");
    private BigDecimal fireLaneFine = new BigDecimal("200.0");
    private BigDecimal mixedWasteFine = new BigDecimal("100.0");
    private int fireLaneRectifyDeadlineMinutes = 120;   // 消防通道堆放整改时限（分钟）
    private BigDecimal fireLaneBlockFine = new BigDecimal("500.0");    // 影响消防通道使用基础扣款（元）
    private BigDecimal neighborComplaintFine = new BigDecimal("200.0");// 被邻居投诉追加扣款（元/次）
    private BigDecimal overdueFinePerHour = new BigDecimal("50.0");    // 超时占用追加扣款（元/小时，不足1小时按1小时）
    private BigDecimal overdueFineCap = new BigDecimal("1000.0");      // 超时追加扣款上限（元）
    private int warningCreditScore = 0;     // 警告信用扣分
    private int penaltyCreditScore = 5;     // 单次扣款信用扣分
    private BigDecimal weighAnomalyThresholdPct = new BigDecimal("20.0");
    private BigDecimal capacityWarnRatio = new BigDecimal("0.9");
    private int lateToleranceMinutes = 30;
}
