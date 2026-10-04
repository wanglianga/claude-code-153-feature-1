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
    private BigDecimal weighAnomalyThresholdPct = new BigDecimal("20.0");
    private BigDecimal capacityWarnRatio = new BigDecimal("0.9");
    private int lateToleranceMinutes = 30;

    // ---- 消防通道违规堆放整改规则 ----
    /** 整改时限（小时）：夜间巡查发现后要求施工队在此时间内移走 */
    private int fireLaneRectifyHours = 24;
    /** 消防通道违规基础扣款（元）：影响消防通道/投诉/超期时计取 */
    private BigDecimal fireLaneBaseFine = new BigDecimal("200.0");
    /** 超期占用加扣款：按占用时长每满 fireLaneOverdueBlockHours 小时加扣一次 */
    private BigDecimal fireLaneOverdueBlockFine = new BigDecimal("100.0");
    /** 超期加扣款计取的时间块（小时），不足一块按一块计 */
    private int fireLaneOverdueBlockHours = 12;
    /** 超期加扣款上限（元） */
    private BigDecimal fireLaneOverdueMaxFine = new BigDecimal("500.0");
    /** 邻居投诉额外扣款（元） */
    private BigDecimal fireLaneComplaintFine = new BigDecimal("200.0");
    /** 二次巡查仍未移走额外扣款（元） */
    private BigDecimal fireLaneRePatrolFine = new BigDecimal("200.0");
    /** 单任务扣款总额上限（元） */
    private BigDecimal fireLaneFineCap = new BigDecimal("1000.0");
    /** 信用分初始/满分 */
    private int teamCreditFullScore = 100;
    /** 限期内移走（警告）扣分 */
    private int teamCreditWarningScore = 5;
    /** 扣款（实质影响）扣分 */
    private int teamCreditDeductScore = 20;
}
