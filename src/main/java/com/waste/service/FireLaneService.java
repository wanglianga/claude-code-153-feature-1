package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消防通道违规堆放处置（夜间巡查）。
 * 巡查发现 → 依房号装修登记/施工队联系方式/现场照片/巡查时间生成整改任务（限期移走）；
 * 限期内移走：押金不动，仅记警告与信用警告；
 * 影响消防通道 / 邻居投诉 / 超期未清：按"基础罚款 + 占用时长加扣 + 投诉/二次巡查加扣"扣款，
 *   押金优先扣除（不足物业垫付），并生成施工队罚款账单、写入施工队信用；
 * 超期仍未完成：自动通知物业经理与业主本人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FireLaneService {

    public static final String BIZ_OVERDUE = "FIRE_LANE_OVERDUE";
    public static final String BIZ_DEDUCT = "FIRE_LANE_DEDUCT";

    private final FireLaneRectificationRepository fireLaneRepo;
    private final RenovationRegistrationRepository regRepo;
    private final UserRepository userRepo;
    private final BillRepository billRepo;
    private final ComplaintRepository complaintRepo;
    private final TeamCreditRecordRepository creditRepo;
    private final BillingService billingService;
    private final NotificationService notificationService;
    private final TraceLogger traceLogger;
    private final com.waste.config.WasteProperties props;

    /** 夜间巡查发现 → 生成整改任务 */
    @Transactional
    public FireLaneRectification create(Session patrol, Long registrationId, LocalDateTime patrolTime,
                                        String scenePhotos, String stackingLocation, String materialDesc) {
        if (patrolTime == null) {
            patrolTime = LocalDateTime.now();
        }
        if (scenePhotos == null || scenePhotos.isBlank()) {
            throw new BizException("现场照片为必填项");
        }
        if (stackingLocation == null || stackingLocation.isBlank()) {
            throw new BizException("堆放位置为必填项");
        }
        RenovationRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new BizException(404, "房号装修登记不存在"));
        if (reg.getStatus() != RegStatus.ACTIVE) {
            throw new BizException("该房号装修登记已完结，不能再生成整改任务");
        }
        String teamPhone = userRepo.findAll().stream()
                .filter(u -> u.getRole() == Role.WORKER && reg.getConstructionTeam().equals(u.getTeamName()))
                .map(User::getPhone).findFirst().orElse(null);

        FireLaneRectification t = new FireLaneRectification();
        t.setTaskNo(NoGen.of("FLR"));
        t.setRegistrationId(reg.getId());
        t.setBuilding(reg.getBuilding());
        t.setRoomNumber(reg.getRoomNumber());
        t.setConstructionTeam(reg.getConstructionTeam());
        t.setTeamPhone(teamPhone);
        t.setOwnerId(reg.getOwnerId());
        t.setOwnerName(reg.getOwnerName());
        t.setPatrolTime(patrolTime);
        t.setScenePhotos(scenePhotos);
        t.setStackingLocation(stackingLocation);
        t.setMaterialDesc(materialDesc == null ? "木板等装修材料" : materialDesc);
        t.setPatrolUserId(patrol.userId());
        t.setPatrolUserName(patrol.name());
        t.setDeadline(patrolTime.plusHours(props.getFireLaneRectifyHours()));
        fireLaneRepo.save(t);

        traceLogger.record(null, "FIRE_LANE_TASK", t.getId(), patrol, "FIRE_LANE_PATROL_CREATE",
                "夜间巡查发现消防通道违规堆放，生成整改任务 " + t.getTaskNo() + "：" + reg.getBuilding()
                        + reg.getRoomNumber() + "室，施工队=" + reg.getConstructionTeam()
                        + (teamPhone != null ? "(" + teamPhone + ")" : "(无联系方式)")
                        + "，堆放=" + t.getMaterialDesc() + "@" + stackingLocation
                        + "，巡查时间=" + patrolTime + "，限 " + props.getFireLaneRectifyHours()
                        + " 小时内（截止 " + t.getDeadline() + "）移走，现场照片=" + scenePhotos);
        log.info("【消防通道整改】建单 {}，房号 {}{}，施工队 {}({})，整改时限 {}",
                t.getTaskNo(), t.getBuilding(), t.getRoomNumber(), t.getConstructionTeam(), teamPhone, t.getDeadline());
        return t;
    }

    /** 施工队移走（限期内=仅警告；逾期=按占用时长扣款）。WORKER 只能操作本队任务，物业可代登记。 */
    @Transactional
    public FireLaneRectification clear(Session session, Long id, LocalDateTime clearedAt, String photos) {
        FireLaneRectification t = getOpenTask(id);
        assertCanOperateTeam(session, t);
        if (clearedAt == null) {
            clearedAt = LocalDateTime.now();
        }
        if (clearedAt.isBefore(t.getPatrolTime())) {
            throw new BizException("移走时间不能早于巡查时间");
        }
        appendPatrolNote(t, "移走登记@" + clearedAt + (photos != null ? " 照片=" + photos : "")
                + " 操作人=" + session.name());
        t.setClearedAt(clearedAt);
        t.setPatrolResult(PatrolResult.CLEARED);

        if (!clearedAt.isAfter(t.getDeadline())) {
            return markWarningOnly(t, session, "施工队在整改时限（" + t.getDeadline() + "）内移走，消防通道恢复畅通，"
                    + "押金只记录警告，不予扣款");
        }
        t.setImpactScope(appendScope(t.getImpactScope(), "超期 " + props.getFireLaneRectifyHours()
                + " 小时仍占用消防通道"));
        return applyDeduction(t, clearedAt, session, "施工队逾期移走（超整改时限）");
    }

    /** 物业二次巡查：CLEARED 视同移走；NOT_CLEARED 按占用时长+影响范围+二次巡查结果扣款 */
    @Transactional
    public FireLaneRectification rePatrol(Session property, Long id, PatrolResult result,
                                          LocalDateTime at, String photos, String impactScope) {
        FireLaneRectification t = getOpenTask(id);
        if (at == null) {
            at = LocalDateTime.now();
        }
        t.setPatrolResult(result);
        appendPatrolNote(t, "二次巡查@" + at + " 结果=" + result
                + (photos != null ? " 照片=" + photos : "")
                + (impactScope != null ? " 影响范围=" + impactScope : "") + " 巡查人=" + property.name());
        if (impactScope != null && !impactScope.isBlank()) {
            t.setImpactScope(appendScope(t.getImpactScope(), impactScope));
        }
        if (result == PatrolResult.CLEARED) {
            t.setClearedAt(at);
            if (!at.isAfter(t.getDeadline())) {
                return markWarningOnly(t, property, "二次巡查确认限期内已移走，仅记录警告");
            }
            return applyDeduction(t, at, property, "二次巡查确认逾期移走");
        }
        // NOT_CLEARED：继续占用，二次巡查结果作为扣款依据
        t.setImpactLevel(ImpactLevel.SERIOUS);
        t.setImpactScope(appendScope(t.getImpactScope(), "二次巡查仍未移走，继续占用消防通道"));
        return applyDeduction(t, at, property, "二次巡查仍未移走");
    }

    /** 物业核实为误报，撤销任务（不扣款、不记信用） */
    @Transactional
    public FireLaneRectification waive(Session property, Long id, String reason) {
        FireLaneRectification t = fireLaneRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "整改任务不存在"));
        if (t.getStatus() == RectifyStatus.RECTIFIED || t.getStatus() == RectifyStatus.DEDUCTED) {
            throw new BizException("任务已处置完成，不能撤销");
        }
        if (t.getStatus() == RectifyStatus.WAIVED) {
            throw new BizException("任务已撤销");
        }
        t.setStatus(RectifyStatus.WAIVED);
        fireLaneRepo.save(t);
        traceLogger.record(null, "FIRE_LANE_TASK", t.getId(), property, "FIRE_LANE_WAIVE",
                "物业撤销整改任务 " + t.getTaskNo() + "，原因=" + reason);
        return t;
    }

    /**
     * 扫描超期未完成任务：自动通知物业经理与业主本人（幂等）。
     * 由定时任务调用，也可由物业/管理员手动触发以便立即处理。
     */
    @Transactional
    public List<FireLaneRectification> scanOverdue() {
        List<FireLaneRectification> due = fireLaneRepo
                .findByStatusAndDeadlineBefore(RectifyStatus.PENDING, LocalDateTime.now());
        for (FireLaneRectification t : due) {
            t.setStatus(RectifyStatus.OVERDUE_NOTIFIED);
            t.setOverdueNotified(true);
            long occupiedMin = Duration.between(t.getPatrolTime(), LocalDateTime.now()).toMinutes();
            t.setOccupiedMinutes(occupiedMin);
            t.setImpactLevel(ImpactLevel.SERIOUS);
            fireLaneRepo.save(t);

            String basis = "扣款依据：消防通道占用时长（截至目前 " + occupiedMin + " 分钟）、影响范围（"
                    + t.getStackingLocation() + "，阻断消防通行）、二次巡查结果；"
                    + "继续拒不整改将按规则加扣（基础 " + props.getFireLaneBaseFine() + " 元 + 超期每满 "
                    + props.getFireLaneOverdueBlockHours() + " 小时加扣 " + props.getFireLaneOverdueBlockFine()
                    + " 元，上限 " + props.getFireLaneFineCap() + " 元）";
            String title = "【消防通道整改超期】" + t.getBuilding() + t.getRoomNumber() + " 请立即处理";
            String content = "整改任务 " + t.getTaskNo() + "：" + t.getBuilding() + t.getRoomNumber()
                    + " 室施工队（" + t.getConstructionTeam()
                    + (t.getTeamPhone() != null ? " " + t.getTeamPhone() : "") + "）将" + t.getMaterialDesc()
                    + "堆放在消防通道，截止 " + t.getDeadline() + " 仍未移走。" + basis;
            if (t.getOwnerId() != null) {
                notificationService.send(t.getOwnerId(), t.getOwnerName(), Role.OWNER.name(),
                        title, content, BIZ_OVERDUE, t.getId());
            }
            notificationService.notifyPropertyManagers(title, content, BIZ_OVERDUE, t.getId());
            traceLogger.record(null, "FIRE_LANE_TASK", t.getId(), null, "FIRE_LANE_OVERDUE_NOTIFY",
                    "整改任务超期未完成，已自动通知物业经理与业主本人。" + basis);
            log.info("【消防通道整改】任务 {} 超期，已通知物业经理与业主 {}", t.getTaskNo(), t.getOwnerName());
        }
        return due;
    }

    /** MQ 事件：邻居投诉（占用消防通道）→ 对在办整改任务按规则扣款（幂等） */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onComplaintCreated(Long complaintId) {
        Complaint c = complaintRepo.findById(complaintId).orElse(null);
        if (c == null || c.getType() != ComplaintType.FIRE_LANE || c.getTargetRegistrationId() == null) {
            return;
        }
        FireLaneRectification t = fireLaneRepo.findByRegistrationIdOrderByIdDesc(c.getTargetRegistrationId())
                .stream()
                .filter(x -> x.getStatus() == RectifyStatus.PENDING
                        || x.getStatus() == RectifyStatus.OVERDUE_NOTIFIED)
                .findFirst().orElse(null);
        if (t == null || t.isNeighborComplaint()) {
            return;
        }
        t.setNeighborComplaint(true);
        t.setImpactLevel(ImpactLevel.SERIOUS);
        t.setImpactScope(appendScope(t.getImpactScope(),
                "造成邻居投诉（投诉单 " + c.getComplaintNo() + "：" + c.getDescription() + "）"));
        applyDeduction(t, LocalDateTime.now(), null, "邻居投诉占用消防通道（投诉单 " + c.getComplaintNo() + "）");
    }

    /** 限期内移走：押金不动，仅记录警告并写信用警告 */
    private FireLaneRectification markWarningOnly(FireLaneRectification t, Session operator, String reason) {
        t.setStatus(RectifyStatus.RECTIFIED);
        t.setImpactLevel(ImpactLevel.NONE);
        t.setOccupiedMinutes(Duration.between(t.getPatrolTime(), t.getClearedAt()).toMinutes());
        t.setDeductionAmount(BigDecimal.ZERO);
        fireLaneRepo.save(t);
        writeCredit(t, CreditLevel.WARNING, -props.getTeamCreditWarningScore(), reason, operator);
        traceLogger.record(null, "FIRE_LANE_TASK", t.getId(), operator, "FIRE_LANE_WARNING",
                "整改任务 " + t.getTaskNo() + " 限期内整改完成：" + reason + "；信用警告扣 "
                        + props.getTeamCreditWarningScore() + " 分，押金不扣款");
        log.info("【消防通道整改】任务 {} 限期内移走，仅警告不扣款", t.getTaskNo());
        return t;
    }

    /**
     * 按规则扣款：押金优先扣除（不足物业垫付）+ 施工队罚款账单 + 施工队信用扣分。
     * 扣款依据 = 消防通道占用时长 + 影响范围 + 二次巡查结果（+ 邻居投诉）。
     */
    private FireLaneRectification applyDeduction(FireLaneRectification t, LocalDateTime at,
                                                 Session operator, String trigger) {
        long occupiedMin = Math.max(0, Duration.between(t.getPatrolTime(), at).toMinutes());
        t.setOccupiedMinutes(occupiedMin);
        BigDecimal fine = calcFine(t, occupiedMin);

        RenovationRegistration reg = regRepo.findById(t.getRegistrationId()).orElseThrow();
        String period = LocalDate.now().toString().substring(0, 7);

        BigDecimal deduct = reg.getDepositBalance().min(fine);
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
            regRepo.save(reg);
            billingService.saveDeduction(reg.getId(), null, null, "FINE", deduct,
                    "消防通道违规堆放扣款[" + t.getTaskNo() + "]: " + trigger, reg.getDepositBalance());
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
            advance.setDetail("押金不足，物业垫付消防通道整改罚款（整改任务 " + t.getTaskNo() + "）");
            billRepo.save(advance);
        }

        Bill teamBill = new Bill();
        teamBill.setBillNo(NoGen.of("BILL"));
        teamBill.setBillType(BillType.CONSTRUCTION_TEAM);
        teamBill.setPartyName(t.getConstructionTeam());
        teamBill.setRegistrationId(reg.getId());
        teamBill.setPeriod(period);
        teamBill.setAmount(fine);
        teamBill.setDetail("施工队消防通道违规堆放罚款（整改任务 " + t.getTaskNo() + "）: " + trigger
                + "（缴清后返还业主押金）");
        billRepo.save(teamBill);

        t.setDeductionAmount(fine);
        t.setTeamBillNo(teamBill.getBillNo());
        t.setStatus(RectifyStatus.DEDUCTED);
        fireLaneRepo.save(t);

        String reason = trigger + "；占用时长 " + occupiedMin + " 分钟；影响范围="
                + (t.getImpactScope() == null ? "占用消防通道" : t.getImpactScope())
                + "；二次巡查=" + t.getPatrolResult() + "；邻居投诉=" + t.isNeighborComplaint()
                + "；扣款 " + fine + " 元，罚款账单 " + teamBill.getBillNo();
        writeCredit(t, CreditLevel.DEDUCT, -props.getTeamCreditDeductScore(), reason, operator);

        traceLogger.record(null, "FIRE_LANE_TASK", t.getId(), operator, "FIRE_LANE_DEDUCT",
                "消防通道违规堆放按规则扣款: " + reason + "；业主押金扣除 " + deduct + " 元"
                        + (gap.compareTo(BigDecimal.ZERO) > 0 ? "，物业垫付 " + gap + " 元" : ""));

        String title = "【消防通道违规扣款】" + t.getBuilding() + t.getRoomNumber();
        String content = "整改任务 " + t.getTaskNo() + " 因「" + trigger + "」已按规则扣款 " + fine
                + " 元（占用 " + occupiedMin + " 分钟），施工队罚款账单 " + teamBill.getBillNo() + "。";
        if (t.getOwnerId() != null) {
            notificationService.send(t.getOwnerId(), t.getOwnerName(), Role.OWNER.name(),
                    title, content, BIZ_DEDUCT, t.getId());
        }
        notificationService.notifyPropertyManagers(title, content, BIZ_DEDUCT, t.getId());
        log.info("【消防通道整改】任务 {} 触发扣款 {} 元（{}）", t.getTaskNo(), fine, trigger);
        return t;
    }

    /**
     * 扣款金额规则：
     * 基础罚款（影响消防通道） + 超期占用时间块加扣（每满 blockHours 小时一块，不足一块按一块，封顶）
     * + 邻居投诉加扣 + 二次巡查仍未移走加扣；总额封顶 fineCap。
     */
    private BigDecimal calcFine(FireLaneRectification t, long occupiedMin) {
        BigDecimal fine = props.getFireLaneBaseFine();
        long limitMin = props.getFireLaneRectifyHours() * 60L;
        if (occupiedMin > limitMin) {
            long overMin = occupiedMin - limitMin;
            long blockMin = props.getFireLaneOverdueBlockHours() * 60L;
            long blocks = (overMin + blockMin - 1) / blockMin;
            BigDecimal overdueFine = props.getFireLaneOverdueBlockFine()
                    .multiply(BigDecimal.valueOf(blocks)).min(props.getFireLaneOverdueMaxFine());
            fine = fine.add(overdueFine);
        }
        if (t.isNeighborComplaint()) {
            fine = fine.add(props.getFireLaneComplaintFine());
        }
        if (t.getPatrolResult() == PatrolResult.NOT_CLEARED) {
            fine = fine.add(props.getFireLaneRePatrolFine());
        }
        return fine.min(props.getFireLaneFineCap()).setScale(2, RoundingMode.HALF_UP);
    }

    private void writeCredit(FireLaneRectification t, CreditLevel level, int scoreChange,
                             String reason, Session operator) {
        TeamCreditRecord c = new TeamCreditRecord();
        c.setCreditNo(NoGen.of("CR"));
        c.setConstructionTeam(t.getConstructionTeam());
        c.setRectificationId(t.getId());
        c.setTaskNo(t.getTaskNo());
        c.setLevel(level);
        c.setScoreChange(scoreChange);
        c.setReason(reason);
        if (operator != null) {
            c.setOperatorId(operator.userId());
            c.setOperatorName(operator.name());
        } else {
            c.setOperatorName("SYSTEM");
        }
        creditRepo.save(c);
    }

    public FireLaneRectification get(Long id) {
        return fireLaneRepo.findById(id).orElseThrow(() -> new BizException(404, "整改任务不存在"));
    }

    private FireLaneRectification getOpenTask(Long id) {
        FireLaneRectification t = get(id);
        if (t.getStatus() != RectifyStatus.PENDING && t.getStatus() != RectifyStatus.OVERDUE_NOTIFIED) {
            throw new BizException("整改任务当前状态为 " + t.getStatus() + "，不能再操作");
        }
        return t;
    }

    private void assertCanOperateTeam(Session session, FireLaneRectification t) {
        if (session.role() == Role.WORKER
                && (session.teamName() == null || !session.teamName().equals(t.getConstructionTeam()))) {
            throw new BizException(403, "只能处理本施工队的整改任务");
        }
        if (session.role() != Role.WORKER && session.role() != Role.PROPERTY && session.role() != Role.ADMIN) {
            throw new BizException(403, "当前角色无权处理整改任务");
        }
    }

    private void appendPatrolNote(FireLaneRectification t, String note) {
        String existed = t.getRePatrolNote() == null ? "" : t.getRePatrolNote() + " | ";
        t.setRePatrolNote(existed + note);
    }

    private String appendScope(String existed, String part) {
        return existed == null || existed.isBlank() ? part : existed + "；" + part;
    }

    public List<FireLaneRectification> list(Session session, RectifyStatus status, String team) {
        List<FireLaneRectification> all = status != null
                ? fireLaneRepo.findByStatusOrderByIdDesc(status) : fireLaneRepo.findAll();
        if (session.role() == Role.WORKER && session.teamName() != null) {
            return all.stream().filter(t -> session.teamName().equals(t.getConstructionTeam())).toList();
        }
        if (session.role() == Role.OWNER) {
            List<Long> regIds = regRepo.findByOwnerIdOrderByIdDesc(session.userId())
                    .stream().map(RenovationRegistration::getId).toList();
            return all.stream().filter(t -> regIds.contains(t.getRegistrationId())).toList();
        }
        if (team != null && !team.isBlank()) {
            return all.stream().filter(t -> team.equals(t.getConstructionTeam())).toList();
        }
        return all;
    }

    /** 施工队信用档案：累计信用分、警告/扣款次数、明细 */
    public Map<String, Object> creditProfile(String team) {
        List<TeamCreditRecord> records = creditRepo.findByConstructionTeamOrderByIdDesc(team);
        long warnings = records.stream().filter(r -> r.getLevel() == CreditLevel.WARNING).count();
        long deducts = records.stream().filter(r -> r.getLevel() == CreditLevel.DEDUCT).count();
        int score = props.getTeamCreditFullScore()
                + records.stream().mapToInt(TeamCreditRecord::getScoreChange).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("constructionTeam", team);
        m.put("fullScore", props.getTeamCreditFullScore());
        m.put("currentScore", score);
        m.put("warningCount", warnings);
        m.put("deductionCount", deducts);
        m.put("records", records);
        return m;
    }

    public List<TeamCreditRecord> teamCreditRecords(String team) {
        return creditRepo.findByConstructionTeamOrderByIdDesc(team);
    }
}
