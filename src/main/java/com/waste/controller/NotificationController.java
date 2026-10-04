package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.Notification;
import com.waste.enums.Role;
import com.waste.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "通知", description = "整改任务超时等场景的短信/站内通知留痕")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "我的通知（业主/物业经理/施工队本人）")
    @GetMapping("/mine")
    public ApiResponse<List<Notification>> mine() {
        Session s = AuthService.current();
        return ApiResponse.ok(notificationService.listMine(s));
    }

    @Operation(summary = "按接收角色查询通知（如 PROPERTY_MANAGER，限物业）")
    @GetMapping
    public ApiResponse<List<Notification>> byRole(@RequestParam String receiverRole) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.ADMIN);
        return ApiResponse.ok(notificationService.listByRole(receiverRole));
    }

    @Operation(summary = "某整改任务已发送的通知（物业）")
    @GetMapping("/task/{taskId}")
    public ApiResponse<List<Notification>> byTask(@PathVariable Long taskId) {
        AuthService.require(Role.PROPERTY, Role.FINANCE, Role.ADMIN);
        return ApiResponse.ok(notificationService.listByTask(taskId));
    }
}
