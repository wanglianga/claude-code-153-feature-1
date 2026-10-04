package com.waste.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时扫描消防通道整改任务：超过时限仍未完成 → 自动通知物业经理与业主本人 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FireLaneScheduler {

    private final FireLaneService fireLaneService;

    @Scheduled(fixedDelayString = "${waste.pricing.fire-lane-scan-ms:60000}",
            initialDelayString = "${waste.pricing.fire-lane-scan-ms:60000}")
    public void scanOverdueRectifications() {
        try {
            var overdue = fireLaneService.scanOverdue();
            if (!overdue.isEmpty()) {
                log.info("【定时巡查】{} 个消防通道整改任务超期，已自动通知", overdue.size());
            }
        } catch (Exception e) {
            log.error("消防通道整改超期扫描失败", e);
        }
    }
}
