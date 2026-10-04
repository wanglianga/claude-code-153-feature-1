package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.FireLaneRectification;
import com.waste.entity.TeamCreditRecord;
import com.waste.enums.PatrolResult;
import com.waste.enums.RectifyStatus;
import com.waste.enums.Role;
import com.waste.service.FireLaneService;
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

@Tag(name = "消防通道违规堆放处置",
        description = "夜间巡查建整改任务（房号装修登记/施工队联系方式/现场照片/巡查时间）；限期移走仅警告，"
                + "影响消防通道/邻居投诉/超期未清按规则扣款并计入施工队信用；超期自动通知物业经理与业主")
@RestController
@RequestMapping("/api/fire-lane")
@RequiredArgsConstructor
public class FireLaneController {

    private final FireLaneService fireLaneService;

    public record CreateRequest(
            @NotNull Long registrationId,
            LocalDateTime patrolTime,
            @NotBlank String scenePhotos,
            @NotBlank String stackingLocation,
            String materialDesc) {
    }

    public record ClearRequest(LocalDateTime clearedAt, String photos) {
    }

    public record RePatrolRequest(
            @NotNull PatrolResult result,
            LocalDateTime at,
            String photos,
            String impactScope) {
    }

    public record WaiveRequest(@NotBlank String reason) {
    }

    @Operation(summary = "夜间巡查发现 → 生成整改任务（物业），自动关联房号装修登记与施工队联系方式")
    @PostMapping("/rectifications")
    public ApiResponse<FireLaneRectification> create(@RequestBody @Valid CreateRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(fireLaneService.create(property, req.registrationId(), req.patrolTime(),
                req.scenePhotos(), req.stackingLocation(), req.materialDesc()));
    }

    @Operation(summary = "整改任务列表（物业/财务看全部，业主看本户，施工队看本队）")
    @GetMapping("/rectifications")
    public ApiResponse<List<FireLaneRectification>> list(
            @RequestParam(required = false) RectifyStatus status,
            @RequestParam(required = false) String constructionTeam) {
        Session session = AuthService.current();
        return ApiResponse.ok(fireLaneService.list(session, status, constructionTeam));
    }

    @Operation(summary = "整改任务详情")
    @GetMapping("/rectifications/{id}")
    public ApiResponse<FireLaneRectification> get(@PathVariable Long id) {
        AuthService.current();
        return ApiResponse.ok(fireLaneService.get(id));
    }

    @Operation(summary = "施工队移走堆放物（施工队本人/物业登记）；限期内仅警告，逾期按占用时长扣款")
    @PostMapping("/rectifications/{id}/clear")
    public ApiResponse<FireLaneRectification> clear(@PathVariable Long id,
                                                    @RequestBody(required = false) ClearRequest req) {
        Session session = AuthService.require(Role.WORKER, Role.PROPERTY);
        LocalDateTime at = req == null ? null : req.clearedAt();
        String photos = req == null ? null : req.photos();
        return ApiResponse.ok(fireLaneService.clear(session, id, at, photos));
    }

    @Operation(summary = "物业二次巡查：已移走确认整改，未移走按占用时长/影响范围/巡查结果扣款")
    @PostMapping("/rectifications/{id}/re-patrol")
    public ApiResponse<FireLaneRectification> rePatrol(@PathVariable Long id,
                                                       @RequestBody @Valid RePatrolRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(fireLaneService.rePatrol(property, id, req.result(), req.at(),
                req.photos(), req.impactScope()));
    }

    @Operation(summary = "物业核实误报，撤销整改任务（不扣款、不计信用）")
    @PostMapping("/rectifications/{id}/waive")
    public ApiResponse<FireLaneRectification> waive(@PathVariable Long id,
                                                    @RequestBody @Valid WaiveRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(fireLaneService.waive(property, id, req.reason()));
    }

    @Operation(summary = "手动扫描超期整改任务（物业/管理员）；系统亦会按周期自动扫描并通知物业经理与业主")
    @PostMapping("/scan-overdue")
    public ApiResponse<List<FireLaneRectification>> scanOverdue() {
        AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(fireLaneService.scanOverdue());
    }

    @Operation(summary = "施工队信用档案：当前信用分、警告/扣款次数与整改信用明细")
    @GetMapping("/credit")
    public ApiResponse<Map<String, Object>> credit(@RequestParam String constructionTeam) {
        AuthService.require(Role.PROPERTY, Role.FINANCE);
        return ApiResponse.ok(fireLaneService.creditProfile(constructionTeam));
    }

    @Operation(summary = "施工队整改信用记录列表")
    @GetMapping("/credit/records")
    public ApiResponse<List<TeamCreditRecord>> creditRecords(@RequestParam String constructionTeam) {
        AuthService.require(Role.PROPERTY, Role.FINANCE);
        return ApiResponse.ok(fireLaneService.teamCreditRecords(constructionTeam));
    }
}
