package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.config.WasteProperties;
import com.waste.entity.*;
import com.waste.enums.*;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 消防通道违规堆放处置（夜间巡查整改任务）：
 * 1. 夜间巡查发现 → 依据房号装修登记、施工队联系方式、现场照片、巡查时间生成整改任务并限期；
 * 2. 施工队限期内移走且未影响通道、无邻居投诉 → 仅记录警告（不扣押金），警告计入施工队信用；
 * 3. 影响消防通道使用或被邻居投诉 → 按规则扣押金（不足物业垫付）+ 施工队罚款账单 + 信用处罚记录；
 * 4. 超时未移走 → 自动通知物业经理和业主本人；二次巡查仍占用 → 以占用时长、影响范围、二次巡查结论扣款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RectificationService {

    private final RectificationTaskRepository taskRepo;
    private final RenovationRegistrationRepository regRepo;
    private final UserRepository userRepo;
    private final ViolationRepository violationRepo;
    private final ComplaintRepository complaintRepo;
    private final TeamCreditRecordRepository creditRepo;
    private final BillRepository billRepo;
    private final BillingService billingService;
    private final NotificationService notificationService;
    private final WasteProperties props;
    private final TraceLogger traceLogger;

    /** 夜间巡查发现消防通道堆放，生成整改任务 */
    @Transactional
    public RectificationTask create(Session property, Long registrationId, String scenePhotos,
                                    String fireLaneLocation, String material, String impactScope,
                                    boolean fireLaneBlocked, LocalDateTime patrolTime, Integer deadlineMinutes) {
        RenovationRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new BizException(404, "装修登记不存在，无法依据房号生成整改任务"));
        if (patrolTime == null) {
            patrolTime = LocalDateTime.now();
        }
        int minutes = deadlineMinutes != null ? deadlineMinutes : props.getFireLaneRectifyDeadlineMinutes();
        if (minutes < 0) {
            throw new BizException("整改时限不能为负");
        }

        // 施工队联系方式：从同队施工人员账号取联系人/电话
        String contact = userRepo.findByTeamName(reg.getConstructionTeam()).stream()
                .map(u -> u.getName() + "/" + (u.getPhone() == null ? "未登记电话" : u.getPhone()))
                .reduce((a, b) -> a + "; " + b).orElse("未登记联系方式");

        RectificationTask task = new RectificationTask();
        task.setTaskNo(NoGen.of("CRID"));
        task.setRegistrationId(reg.getId());
        task.setBuilding(reg.getBuilding());
        task.setRoomNumber(reg.getRoomNumber());
        task.setConstructionTeam(reg.getConstructionTeam());
        task.setTeamContact(contact);
        task.setScenePhotos(scenePhotos);
        task.setPatrolTime(patrolTime);
        task.setDeadline(patrolTime.plusMinutes(minutes));
        task.setFireLaneLocation(fireLaneLocation);
        task.setMaterial(material == null || material.isBlank() ? "装修材料" : material);
        task.setImpactScope(impactScope);
        task.setFireLaneBlocked(fireLaneBlocked);
        task.setPatrolUserId(property.userId());
        task.setPatrolUserName(property.name());

        // 同步生成消防通道堆放违规单（PENDING，不走上报自动扣款；随整改结论扣款或豁免）
        Violation v = new Violation();
        v.setViolationNo(NoGen.of("VIO"));
        v.setRegistrationId(reg.getId());
        v.setType(ViolationType.FIRE_LANE_STACKING);
        v.setDescription("夜间巡查发现施工队将" + task.getMaterial() + "堆放于消防通道: "
                + fireLaneLocation + "；房号 " + reg.getBuilding() + reg.getRoomNumber()
                + "；影响范围: " + impactScope + "；限 " + minutes + " 分钟内移走");
        v.setDeductionAmount(props.getFireLaneBlockFine());
        violationRepo.save(v);
        task.setViolationId(v.getId());
        taskRepo.save(task);
        v.setRectificationTaskId(task.getId());
        violationRepo.save(v);

        traceLogger.record(null, "RECTIFICATION", task.getId(), property, "RECTIFICATION_CREATE",
                "夜间巡查发现消防通道堆放，生成整改任务 " + task.getTaskNo() + "：房号 " + reg.getBuilding()
                        + reg.getRoomNumber() + "，施工队 " + reg.getConstructionTeam() + "（" + contact
                        + "），堆放[" + task.getMaterial() + "]于 " + fireLaneLocation
                        + "，影响范围: " + impactScope + "，是否阻断通道=" + fireLaneBlocked
                        + "，整改时限至 " + task.getDeadline() + "，现场照片: " + scenePhotos);
        return task;
    }

    /** 施工队移走（或物业代为确认） */
    @Transactional
    public RectificationTask markCleared(Session session, Long taskId, String clearPhotos, LocalDateTime clearedAt) {
        RectificationTask task = getOwnedOrProperty(session, taskId);
        if (task.getStatus() == RectificationStatus.PENALIZED || task.getStatus() == RectificationStatus.CLEARED) {
            throw new BizException("整改任务已结案，不能重复移走确认");
        }
        if (clearedAt == null) {
            clearedAt = LocalDateTime.now();
        }
        task.setClearedAt(clearedAt);
        task.setClearPhotos(clearPhotos);
        task.setOccupationMinutes(occupationMinutes(task, clearedAt));

        int neighborComplaints = countNeighborComplaints(task);
        boolean inTime = !clearedAt.isAfter(task.getDeadline());
        if (inTime && !task.isFireLaneBlocked() && neighborComplaints == 0) {
            finishWarning(task, session);
        } else {
            String trigger = !inTime ? "超过整改时限后才移走"
                    : (task.isFireLaneBlocked() ? "堆放已影响消防通道正常使用" : "引发邻居投诉");
            resolvePenalty(task, trigger, session.userId(), session.name(), clearedAt, clearPhotos, null);
        }
        return task;
    }

    /** 物业二次巡查：仍占用 → 扣款；已移走 → 按是否超时/阻断/投诉给结论 */
    @Transactional
    public RectificationTask secondPatrol(Session property, Long taskId, PatrolResult result,
                                          String photos, String note, LocalDateTime patrolTime) {
        RectificationTask task = mustFind(taskId);
        if (task.getStatus() == RectificationStatus.CLEARED || task.getStatus() == RectificationStatus.PENALIZED) {
            throw new BizException("整改任务已结案");
        }
        if (result == PatrolResult.PENDING) {
            throw new BizException("二次巡查结论必须为 CLEARED 或 STILL_THERE");
        }
        if (patrolTime == null) {
            patrolTime = LocalDateTime.now();
        }
        task.setSecondPatrolResult(result);
        task.setSecondPatrolTime(patrolTime);
        task.setSecondPatrolPhotos(photos);
        task.setSecondPatrolNote(note);
        task.setSecondPatrolUserId(property.userId());
        task.setSecondPatrolUserName(property.name());
        task.setOccupationMinutes(occupationMinutes(task, patrolTime));
        traceLogger.record(null, "RECTIFICATION", task.getId(), property, "RECTIFICATION_SECOND_PATROL",
                "二次巡查结论 " + result + "（" + task.getTaskNo() + "）：" + note
                        + "，照片: " + photos + "，占用时长 " + task.getOccupationMinutes() + " 分钟");

        if (result == PatrolResult.STILL_THERE) {
            resolvePenalty(task, "二次巡查确认消防通道仍被占用", property.userId(), property.name(),
                    patrolTime, photos, note);
        } else if (!patrolTime.isAfter(task.getDeadline()) && !task.isFireLaneBlocked()
                && countNeighborComplaints(task) == 0) {
            task.setClearedAt(patrolTime);
            task.setClearPhotos(photos);
            finishWarning(task, property);
        } else {
            resolvePenalty(task, patrolTime.isAfter(task.getDeadline()) ? "二次巡查确认超时后才移走"
                    : "二次巡查确认已移走但堆放曾影响消防通道/引发投诉", property.userId(), property.name(),
                    patrolTime, photos, note);
        }
        return task;
    }

    /** 物业依据现场情况手动扣款结案（如确认影响消防通道） */
    @Transactional
    public RectificationTask penalize(Session property, Long taskId, String reason) {
        RectificationTask task = mustFind(taskId);
        if (task.getStatus() == RectificationStatus.PENALIZED) {
            throw new BizException("整改任务已扣款结案");
        }
        resolvePenalty(task, "物业确认: " + reason, property.userId(), property.name(),
                LocalDateTime.now(), null, reason);
        return task;
    }

    /** 邻居投诉（占用消防通道）联动：未结案→按规则扣款；已扣款→追加投诉扣款；已警告→改处罚 */
    @Transactional
    public void onNeighborComplaint(Complaint complaint) {
        if (complaint.getType() != ComplaintType.FIRE_LANE || complaint.getTargetRegistrationId() == null) {
            return;
        }
        List<RectificationTask> tasks = taskRepo
                .findByRegistrationIdOrderByIdDesc(complaint.getTargetRegistrationId());
        if (tasks.isEmpty()) {
            return;
        }
        RectificationTask task = tasks.get(0);
        boolean sameCase = complaint.getCreatedAt() != null
                && !complaint.getCreatedAt().isBefore(task.getPatrolTime().minusMinutes(1));
        if (!sameCase) {
            return;
        }
        if (task.getStatus() == RectificationStatus.PENALIZED) {
            appendSurcharge(task, props.getNeighborComplaintFine(),
                    "邻居投诉占用消防通道（投诉单 " + complaint.getComplaintNo() + "）追加扣款", complaint);
        } else {
            task.setComplaintId(complaint.getId());
            resolvePenalty(task, "邻居投诉占用消防通道（投诉单 " + complaint.getComplaintNo() + "）",
                    null, "邻居" + complaint.getComplainantName(), LocalDateTime.now(), null,
                    complaint.getDescription());
        }
    }

    /** 定时/手动扫描：超过时限仍未完成 → 置 OVERDUE 并通知物业经理与业主本人（幂等） */
    @Transactional
    public int scanOverdue() {
        List<RectificationTask> due = taskRepo.findByStatusInAndDeadlineBefore(
                List.of(RectificationStatus.OPEN), LocalDateTime.now());
        for (RectificationTask task : due) {
            markOverdue(task);
        }
        return due.size();
    }

    private void markOverdue(RectificationTask task) {
        task.setStatus(RectificationStatus.OVERDUE);
        task.setOverdueNotifiedAt(LocalDateTime.now());
        task.setOccupationMinutes(occupationMinutes(task, LocalDateTime.now()));
        taskRepo.save(task);
        User owner = regRepo.findById(task.getRegistrationId())
                .flatMap(r -> userRepo.findById(r.getOwnerId())).orElse(null);
        notificationService.notifyOverdue(task, owner);
        traceLogger.record(null, "RECTIFICATION", task.getId(), null, "RECTIFICATION_OVERDUE",
                "整改任务 " + task.getTaskNo() + " 超过时限（" + task.getDeadline() + "）仍未完成，"
                        + "已自动通知物业经理与业主本人；当前占用时长 " + task.getOccupationMinutes()
                        + " 分钟，影响范围: " + task.getImpactScope());
    }

    // ---------------- 内部：结案结论 ----------------

    /** 限期内移走且未影响通道、无投诉 → 仅警告，不扣押金 */
    private void finishWarning(RectificationTask task, Session operator) {
        task.setStatus(RectificationStatus.CLEARED);
        task.setDeductionAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        task.setResolution("限期内移走，消防通道恢复畅通，仅记录警告，不予扣款");
        taskRepo.save(task);
        saveCredit(task, "WARNING", props.getWarningCreditScore(), BigDecimal.ZERO,
                "消防通道堆放警告：限期内移走（任务 " + task.getTaskNo() + "）", operator);
        if (task.getViolationId() != null) {
            violationRepo.findById(task.getViolationId()).ifPresent(v -> {
                v.setStatus(ViolationStatus.WAIVED);
                violationRepo.save(v);
            });
        }
        traceLogger.record(null, "RECTIFICATION", task.getId(), operator, "RECTIFICATION_CLEAR_WARNING",
                "施工队在限期内移走（任务 " + task.getTaskNo() + "），占用 " + task.getOccupationMinutes()
                        + " 分钟，仅记录警告，不扣押金");
    }

    /** 扣款结案：押金扣除 + 物业垫付缺口 + 施工队罚款账单 + 信用处罚 */
    private void resolvePenalty(RectificationTask task, String trigger, Long operatorId, String operatorName,
                                LocalDateTime endTime, String photos, String note) {
        if (task.getStatus() == RectificationStatus.PENALIZED) {
            return;
        }
        RenovationRegistration reg = regRepo.findById(task.getRegistrationId()).orElseThrow();
        int complaints = countNeighborComplaints(task);
        BigDecimal fine = calcFine(task, complaints, endTime);
        task.setOccupationMinutes(occupationMinutes(task, endTime));
        executeFine(task, reg, fine, trigger, operatorId, operatorName);
        task.setStatus(RectificationStatus.PENALIZED);
        task.setDeductionAmount(fine);
        task.setResolution(trigger + "；按规则扣款 " + fine + " 元（占用 " + task.getOccupationMinutes()
                + " 分钟，影响范围: " + task.getImpactScope() + "，二次巡查: "
                + task.getSecondPatrolResult() + "，邻居投诉 " + complaints + " 次）");
        if (photos != null) {
            task.setClearPhotos(photos);
        }
        taskRepo.save(task);
        if (task.getViolationId() != null) {
            violationRepo.findById(task.getViolationId()).ifPresent(v -> {
                v.setDeductionAmount(fine);
                v.setStatus(ViolationStatus.DEDUCTED);
                violationRepo.save(v);
            });
        }
        User owner = userRepo.findById(reg.getOwnerId()).orElse(null);
        notificationService.notifyPenalty(task, owner, fine);
    }

    /** 已扣款结案后又被投诉：按次追加扣款，再写一条信用处罚 */
    private void appendSurcharge(RectificationTask task, BigDecimal surcharge, String reason, Complaint c) {
        RenovationRegistration reg = regRepo.findById(task.getRegistrationId()).orElseThrow();
        executeFine(task, reg, surcharge, reason, null, "邻居" + c.getComplainantName());
        task.setDeductionAmount((task.getDeductionAmount() == null ? BigDecimal.ZERO : task.getDeductionAmount())
                .add(surcharge).setScale(2, RoundingMode.HALF_UP));
        task.setResolution(task.getResolution() + "；追加: " + reason);
        taskRepo.save(task);
    }

    /** 扣款规则：阻断通道基础罚 + 邻居投诉次罚 + 超时占用按时长追加（封顶） */
    private BigDecimal calcFine(RectificationTask task, int neighborComplaints, LocalDateTime endTime) {
        BigDecimal fine = BigDecimal.ZERO;
        if (task.isFireLaneBlocked()) {
            fine = fine.add(props.getFireLaneBlockFine());
        }
        if (neighborComplaints > 0) {
            fine = fine.add(props.getNeighborComplaintFine().multiply(BigDecimal.valueOf(neighborComplaints)));
        }
        if (endTime.isAfter(task.getDeadline())) {
            long occMin = Math.max(1, Duration.between(task.getPatrolTime(), endTime).toMinutes());
            long hours = Math.max(1, (occMin + 59) / 60);
            BigDecimal overdue = props.getOverdueFinePerHour().multiply(BigDecimal.valueOf(hours));
            if (overdue.compareTo(props.getOverdueFineCap()) > 0) {
                overdue = props.getOverdueFineCap();
            }
            fine = fine.add(overdue);
        }
        return fine.setScale(2, RoundingMode.HALF_UP);
    }

    /** 执行扣款：押金优先、不足物业垫付；生成施工队罚款账单；写信用；落追溯 */
    private void executeFine(RectificationTask task, RenovationRegistration reg, BigDecimal fine,
                             String trigger, Long operatorId, String operatorName) {
        String period = LocalDate.now().toString().substring(0, 7);
        BigDecimal deduct = reg.getDepositBalance().min(fine);
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
            regRepo.save(reg);
            billingService.saveDeduction(reg.getId(), task.getViolationId(), null, "FINE", deduct,
                    "消防通道违规堆放扣款（整改任务 " + task.getTaskNo() + "）: " + trigger
                            + "，押金扣除 " + deduct + " 元", reg.getDepositBalance());
        }
        BigDecimal gap = fine.subtract(deduct);
        if (gap.compareTo(BigDecimal.ZERO) > 0) {
            Bill advance = new Bill();
            advance.setBillNo(NoGen.of("BILL"));
            advance.setBillType(BillType.PROPERTY_ADVANCE);
            advance.setPartyName("物业");
            advance.setRegistrationId(reg.getId());
            advance.setPeriod(period);
            advance.setAmount(gap);
            advance.setDetail("押金不足，物业垫付消防通道违规扣款（整改任务 " + task.getTaskNo() + "）");
            billRepo.save(advance);
        }
        Bill teamBill = new Bill();
        teamBill.setBillNo(NoGen.of("BILL"));
        teamBill.setBillType(BillType.CONSTRUCTION_TEAM);
        teamBill.setPartyName(task.getConstructionTeam());
        teamBill.setRegistrationId(reg.getId());
        teamBill.setPeriod(period);
        teamBill.setAmount(fine);
        teamBill.setDetail("施工队消防通道违规堆放罚款（整改任务 " + task.getTaskNo() + "）: " + trigger
                + "（缴清后返还业主押金）");
        billRepo.save(teamBill);
        task.setTeamBillId(teamBill.getId());

        String reason = "消防通道违规堆放处罚（整改任务 " + task.getTaskNo() + "）: " + trigger
                + "，扣款 " + fine + " 元，占用 " + task.getOccupationMinutes() + " 分钟，影响范围: "
                + task.getImpactScope() + "，二次巡查: " + task.getSecondPatrolResult();
        TeamCreditRecord credit = saveCredit(task, "PENALTY", props.getPenaltyCreditScore(), fine,
                reason, operatorId, operatorName);
        task.setCreditRecordId(credit.getId());

        traceLogger.record(null, "RECTIFICATION", task.getId(), null, "RECTIFICATION_PENALTY",
                reason + "；押金扣除 " + deduct + " 元"
                        + (gap.compareTo(BigDecimal.ZERO) > 0 ? "，物业垫付 " + gap + " 元" : "")
                        + "，施工队罚款账单 " + teamBill.getBillNo());
    }

    private TeamCreditRecord saveCredit(RectificationTask task, String type, int score, BigDecimal fine,
                                        String reason, Session operator) {
        return saveCredit(task, type, score, fine, reason,
                operator == null ? null : operator.userId(), operator == null ? null : operator.name());
    }

    private TeamCreditRecord saveCredit(RectificationTask task, String type, int score, BigDecimal fine,
                                        String reason, Long operatorId, String operatorName) {
        TeamCreditRecord c = new TeamCreditRecord();
        c.setRecordNo(NoGen.of("CR"));
        c.setConstructionTeam(task.getConstructionTeam());
        c.setTaskId(task.getId());
        c.setViolationId(task.getViolationId());
        c.setType(type);
        c.setScoreDeducted(score);
        c.setFineAmount(fine.setScale(2, RoundingMode.HALF_UP));
        c.setReason(reason);
        c.setOperatorId(operatorId);
        c.setOperatorName(operatorName);
        creditRepo.save(c);
        return c;
    }

    // ---------------- 查询 ----------------

    public List<RectificationTask> list(Session session, RectificationStatus status,
                                        Long registrationId, String team) {
        if (session.role() == Role.COLLECTOR) {
            throw new BizException(403, "当前角色无权查看整改任务");
        }
        List<RectificationTask> all;
        if (registrationId != null) {
            all = taskRepo.findByRegistrationIdOrderByIdDesc(registrationId);
        } else if (team != null && !team.isBlank()) {
            all = taskRepo.findByConstructionTeamOrderByIdDesc(team);
        } else if (status != null) {
            all = taskRepo.findByStatusOrderByIdDesc(status);
        } else {
            all = taskRepo.findAll();
        }
        if (session.role() == Role.WORKER && session.teamName() != null) {
            all = all.stream().filter(t -> session.teamName().equals(t.getConstructionTeam())).toList();
        } else if (session.role() == Role.OWNER) {
            var regIds = regRepo.findByOwnerIdOrderByIdDesc(session.userId()).stream()
                    .map(RenovationRegistration::getId).toList();
            all = all.stream().filter(t -> regIds.contains(t.getRegistrationId())).toList();
        }
        return status == null ? all : all.stream().filter(t -> t.getStatus() == status).toList();
    }

    public List<TeamCreditRecord> listCredit(Session session, String team) {
        if (session.role() == Role.WORKER) {
            if (session.teamName() == null) {
                throw new BizException(403, "当前施工人员未归属施工队");
            }
            return creditRepo.findByConstructionTeamOrderByIdDesc(session.teamName());
        }
        if (session.role() != Role.PROPERTY && session.role() != Role.FINANCE && session.role() != Role.ADMIN) {
            throw new BizException(403, "当前角色无权查看施工队信用");
        }
        if (team != null && !team.isBlank()) {
            return creditRepo.findByConstructionTeamOrderByIdDesc(team);
        }
        return creditRepo.findAll();
    }

    public RectificationTask get(Session session, Long id) {
        RectificationTask task = mustFind(id);
        if (session.role() == Role.PROPERTY || session.role() == Role.FINANCE || session.role() == Role.ADMIN) {
            return task;
        }
        if (session.role() == Role.WORKER) {
            if (session.teamName() != null && session.teamName().equals(task.getConstructionTeam())) {
                return task;
            }
            throw new BizException(403, "只能查看本施工队的整改任务");
        }
        if (session.role() == Role.OWNER) {
            RenovationRegistration reg = regRepo.findById(task.getRegistrationId()).orElseThrow();
            if (reg.getOwnerId().equals(session.userId())) {
                return task;
            }
        }
        throw new BizException(403, "当前角色无权查看该整改任务");
    }

    private RectificationTask mustFind(Long id) {
        return taskRepo.findById(id).orElseThrow(() -> new BizException(404, "整改任务不存在"));
    }

    /** 施工队只能操作本队任务，物业可操作任意任务 */
    private RectificationTask getOwnedOrProperty(Session session, Long id) {
        RectificationTask task = mustFind(id);
        if (session.role() == Role.WORKER
                && (session.teamName() == null || !session.teamName().equals(task.getConstructionTeam()))) {
            throw new BizException(403, "只能处理本施工队的整改任务");
        }
        if (session.role() != Role.WORKER && session.role() != Role.PROPERTY && session.role() != Role.ADMIN) {
            throw new BizException(403, "当前角色无权处理整改任务");
        }
        return task;
    }

    private int countNeighborComplaints(RectificationTask task) {
        return (int) complaintRepo.findByTargetRegistrationId(task.getRegistrationId()).stream()
                .filter(c -> c.getType() == ComplaintType.FIRE_LANE)
                .filter(c -> c.getCreatedAt() == null
                        || !c.getCreatedAt().isBefore(task.getPatrolTime().minusMinutes(1)))
                .count();
    }

    private long occupationMinutes(RectificationTask task, LocalDateTime endTime) {
        long minutes = Duration.between(task.getPatrolTime(), endTime).toMinutes();
        return Math.max(0, minutes);
    }
}
