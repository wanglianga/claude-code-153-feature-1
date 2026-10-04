package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.entity.AppNotification;
import com.waste.entity.User;
import com.waste.enums.Role;
import com.waste.repository.AppNotificationRepository;
import com.waste.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 站内通知：整改超期自动通知物业经理与业主；用户读取与已读 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AppNotificationRepository notifyRepo;
    private final UserRepository userRepo;

    @Transactional
    public AppNotification send(Long receiverId, String receiverName, String receiverRole,
                                String title, String content, String bizType, Long bizId) {
        AppNotification n = new AppNotification();
        n.setReceiverId(receiverId);
        n.setReceiverName(receiverName);
        n.setReceiverRole(receiverRole);
        n.setTitle(title);
        n.setContent(content);
        n.setBizType(bizType);
        n.setBizId(bizId);
        notifyRepo.save(n);
        log.info("【站内通知】-> {}({}/{}): {} | {}", receiverName, receiverRole, receiverId, title, content);
        return n;
    }

    /** 通知全体物业经理，返回通知人数 */
    @Transactional
    public int notifyPropertyManagers(String title, String content, String bizType, Long bizId) {
        List<User> managers = userRepo.findAll().stream()
                .filter(u -> u.getRole() == Role.PROPERTY).toList();
        for (User m : managers) {
            send(m.getId(), m.getName(), Role.PROPERTY.name(), title, content, bizType, bizId);
        }
        return managers.size();
    }

    public List<AppNotification> listMine(Session session) {
        return notifyRepo.findByReceiverIdOrderByIdDesc(session.userId());
    }

    @Transactional
    public AppNotification markRead(Session session, Long id) {
        AppNotification n = notifyRepo.findById(id).orElseThrow(() -> new BizException(404, "通知不存在"));
        if (!n.getReceiverId().equals(session.userId())) {
            throw new BizException(403, "只能读取本人通知");
        }
        if (!n.isReadFlag()) {
            n.setReadFlag(true);
            n.setReadAt(LocalDateTime.now());
            notifyRepo.save(n);
        }
        return n;
    }
}
