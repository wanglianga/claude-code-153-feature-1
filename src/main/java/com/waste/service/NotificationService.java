package com.waste.service;

import com.waste.auth.Session;
import com.waste.entity.Notification;
import com.waste.entity.RectificationTask;
import com.waste.entity.User;
import com.waste.enums.Role;
import com.waste.repository.NotificationRepository;
import com.waste.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 通知发送与查询：物业经理 / 业主 / 施工队，短信+站内双通道落库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepo;
    private final UserRepository userRepo;

    @Transactional
    public void notifyUser(User user, String roleTag, String channel, String subject,
                           String content, Long taskId) {
        Notification n = new Notification();
        n.setReceiverId(user.getId());
        n.setReceiverName(user.getName());
        n.setReceiverRole(roleTag);
        n.setChannel(channel);
        n.setSubject(subject);
        n.setContent(content);
        n.setTaskId(taskId);
        notificationRepo.save(n);
        log.info("【{}通知→{} {}】{}: {}", channel, roleTag, user.getName(), subject, content);
    }

    /** 超时未整改：通知全体物业经理 + 业主本人（短信+站内） */
    @Transactional
    public void notifyOverdue(RectificationTask task, User owner) {
        String subject = "消防通道堆放整改任务超时";
        String content = "整改任务 " + task.getTaskNo() + "：" + task.getBuilding() + task.getRoomNumber()
                + " 装修施工队[" + task.getConstructionTeam() + "]将" + task.getMaterial()
                + "堆放在消防通道（" + task.getFireLaneLocation() + "），限 "
                + task.getDeadline() + " 前移走，现已超时仍未完成。占用时长 "
                + task.getOccupationMinutes() + " 分钟，影响范围：" + task.getImpactScope()
                + "，二次巡查：" + task.getSecondPatrolResult() + "。平台将按规则扣款并记入施工队信用，请立即处置。";
        for (User manager : userRepo.findByRole(Role.PROPERTY)) {
            notifyUser(manager, "PROPERTY_MANAGER", "SMS", subject, content, task.getId());
            notifyUser(manager, "PROPERTY_MANAGER", "APP", subject, content, task.getId());
        }
        if (owner != null) {
            notifyUser(owner, "OWNER", "SMS", subject, content, task.getId());
            notifyUser(owner, "OWNER", "APP", subject, content, task.getId());
        }
    }

    /** 扣款处罚后告知业主本人 */
    @Transactional
    public void notifyPenalty(RectificationTask task, User owner, java.math.BigDecimal fine) {
        if (owner == null) {
            return;
        }
        String content = "您房产（" + task.getBuilding() + task.getRoomNumber() + "）的施工队["
                + task.getConstructionTeam() + "]因消防通道违规堆放被扣款 " + fine
                + " 元（任务 " + task.getTaskNo() + "），款项从装修押金中扣除，施工队缴清后返还。";
        notifyUser(owner, "OWNER", "APP", "消防通道违规扣款告知", content, task.getId());
    }

    public List<Notification> listMine(Session session) {
        return notificationRepo.findByReceiverIdOrderByIdDesc(session.userId());
    }

    public List<Notification> listByRole(String role) {
        return notificationRepo.findByReceiverRoleOrderByIdDesc(role);
    }

    public List<Notification> listByTask(Long taskId) {
        return notificationRepo.findByTaskIdOrderByIdDesc(taskId);
    }
}
