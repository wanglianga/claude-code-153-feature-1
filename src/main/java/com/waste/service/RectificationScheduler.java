package com.waste.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 整改任务超时扫描：每分钟检查超过时限仍未完成的整改任务，自动通知物业经理与业主本人 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RectificationScheduler {

    private final RectificationService rectificationService;

    @Scheduled(fixedDelayString = "${waste.pricing.scan-interval-ms:60000}")
    public void scanOverdue() {
        try {
            int n = rectificationService.scanOverdue();
            if (n > 0) {
                log.info("超时整改任务扫描：{} 个任务已置为 OVERDUE 并通知物业经理/业主", n);
            }
        } catch (Exception e) {
            log.error("超时整改任务扫描失败: {}", e.getMessage(), e);
        }
    }
}
