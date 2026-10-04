package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.RectificationTask;
import com.waste.entity.TeamCreditRecord;
import com.waste.enums.PatrolResult;
import com.waste.enums.RectificationStatus;
import com.waste.enums.Role;
import com.waste.service.RectificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "消防通道整改", description = "夜间巡查发现消防通道违规堆放→限期整改→警告/扣款/信用/超时通知")
@RestController
@RequestMapping("/api/rectifications")
@RequiredArgsConstructor
public class RectificationController {

    private final RectificationService rectificationService;

    public record CreateRequest(
            @NotNull Long registrationId,
            @NotBlank String scenePhotos,
            @NotBlank String fireLaneLocation,
            String material,
            @NotBlank String impactScope,
            boolean fireLaneBlocked,
            LocalDateTime patrolTime,       // 不传则取当前时间（夜间巡查时间）
            Integer deadlineMinutes) {      // 不传则取系统整改时限
    }

    public record ClearRequest(String clearPhotos, LocalDateTime clearedAt) {
    }

    public record SecondPatrolRequest(
            @NotNull PatrolResult result,
            String photos,
            @NotBlank String note,
            LocalDateTime patrolTime) {
    }

    public record PenalizeRequest(@NotBlank String reason) {
    }

    @Operation(summary = "夜间巡查发现消防通道堆放，生成整改任务（物业）")
    @PostMapping
    public ApiResponse<RectificationTask> create(@RequestBody @Valid CreateRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(rectificationService.create(property, req.registrationId(), req.scenePhotos(),
                req.fireLaneLocation(), req.material(), req.impactScope(), req.fireLaneBlocked(),
                req.patrolTime(), req.deadlineMinutes()));
    }

    @Operation(summary = "整改任务列表（业主看本户、施工队看本队、物业财务看全部）")
    @GetMapping
    public ApiResponse<List<RectificationTask>> list(@RequestParam(required = false) RectificationStatus status,
                                                     @RequestParam(required = false) Long registrationId,
                                                     @RequestParam(required = false) String team) {
        return ApiResponse.ok(rectificationService.list(AuthService.current(), status, registrationId, team));
    }

    @Operation(summary = "整改任务详情（业主限本户、施工队限本队、物业财务可看全部）")
    @GetMapping("/{id}")
    public ApiResponse<RectificationTask> get(@PathVariable Long id) {
        return ApiResponse.ok(rectificationService.get(AuthService.current(), id));
    }

    @Operation(summary = "施工队移走后报结（限内移走且无阻断/投诉仅警告，否则按规则扣款）")
    @PostMapping("/{id}/clear")
    public ApiResponse<RectificationTask> clear(@PathVariable Long id, @RequestBody(required = false) ClearRequest req) {
        Session s = AuthService.require(Role.WORKER, Role.PROPERTY);
        String photos = req == null ? null : req.clearPhotos();
        LocalDateTime at = req == null ? null : req.clearedAt();
        return ApiResponse.ok(rectificationService.markCleared(s, id, photos, at));
    }

    @Operation(summary = "物业二次巡查（STILL_THERE 按占用时长/影响范围扣款并通知）")
    @PostMapping("/{id}/second-patrol")
    public ApiResponse<RectificationTask> secondPatrol(@PathVariable Long id,
                                                       @RequestBody @Valid SecondPatrolRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(rectificationService.secondPatrol(property, id, req.result(),
                req.photos(), req.note(), req.patrolTime()));
    }

    @Operation(summary = "物业确认影响消防通道，直接扣款结案")
    @PostMapping("/{id}/penalize")
    public ApiResponse<RectificationTask> penalize(@PathVariable Long id, @RequestBody @Valid PenalizeRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(rectificationService.penalize(property, id, req.reason()));
    }

    @Operation(summary = "手动触发超时扫描（定时任务每分钟自动执行；测试/演示用）")
    @PostMapping("/scan-overdue")
    public ApiResponse<Map<String, Object>> scanOverdue() {
        AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(Map.of("overdueNotified", rectificationService.scanOverdue()));
    }

    @Operation(summary = "施工队信用记录（施工队看本队，物业/财务可按 team 过滤）")
    @GetMapping("/team-credits")
    public ApiResponse<List<TeamCreditRecord>> teamCredits(@RequestParam(required = false) String team) {
        return ApiResponse.ok(rectificationService.listCredit(AuthService.current(), team));
    }
}
