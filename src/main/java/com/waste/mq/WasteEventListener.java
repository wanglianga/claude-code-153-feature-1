package com.waste.mq;

import com.waste.config.RabbitConfig;
import com.waste.service.BillingService;
import com.waste.service.FireLaneService;
import com.waste.service.ViolationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 业务事件监听：称重→计费、违规→押金扣款、预约→通知清运、投诉→通知物业/消防通道整改扣款 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WasteEventListener {

    private final BillingService billingService;
    private final ViolationService violationService;
    private final FireLaneService fireLaneService;

    /** 统一入口：MQ 与同步降级共用 */
    public void dispatch(String routingKey, Long entityId) {
        switch (routingKey) {
            case RabbitConfig.RK_WEIGH_RECORDED -> onWeighRecorded(String.valueOf(entityId));
            case RabbitConfig.RK_VIOLATION_CREATED -> onViolationCreated(String.valueOf(entityId));
            case RabbitConfig.RK_APPOINTMENT_APPROVED -> onAppointmentApproved(String.valueOf(entityId));
            case RabbitConfig.RK_COMPLAINT_CREATED -> onComplaintCreated(String.valueOf(entityId));
            default -> log.warn("未知事件路由键: {}", routingKey);
        }
    }

    /** 预约审核通过 → 通知清运公司安排派单 */
    @RabbitListener(queues = RabbitConfig.Q_APPOINTMENT)
    public void onAppointmentApproved(String appointmentId) {
        log.info("【通知清运公司】预约 {} 已审核通过，请安排车辆派单", appointmentId);
    }

    /** 称重完成 → 自动生成业主账单与清运公司结算单 */
    @RabbitListener(queues = RabbitConfig.Q_WEIGH)
    public void onWeighRecorded(String weighRecordId) {
        log.info("【计费】称重记录 {} 已产生，开始生成账单", weighRecordId);
        billingService.onWeighRecorded(Long.parseLong(weighRecordId));
    }

    /** 违规产生 → 押金扣款 + 施工队罚款账单 */
    @RabbitListener(queues = RabbitConfig.Q_VIOLATION)
    public void onViolationCreated(String violationId) {
        log.info("【违规处理】违规 {} 已产生，开始押金扣款", violationId);
        violationService.processDeduction(Long.parseLong(violationId));
    }

    /** 投诉产生 → 通知物业处理；占用消防通道投诉 → 触发整改任务按规则扣款 */
    @RabbitListener(queues = RabbitConfig.Q_COMPLAINT)
    public void onComplaintCreated(String complaintId) {
        log.info("【通知物业】新投诉 {} 待处理", complaintId);
        fireLaneService.onComplaintCreated(Long.parseLong(complaintId));
    }
}
