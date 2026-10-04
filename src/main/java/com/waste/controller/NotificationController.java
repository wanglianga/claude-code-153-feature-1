package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.AppNotification;
import com.waste.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "站内通知", description = "消防通道整改超期/扣款等自动通知（物业经理、业主本人）")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "我的通知列表")
    @GetMapping
    public ApiResponse<List<AppNotification>> mine() {
        return ApiResponse.ok(notificationService.listMine(AuthService.current()));
    }

    @Operation(summary = "通知标记已读")
    @PostMapping("/{id}/read")
    public ApiResponse<AppNotification> read(@PathVariable Long id) {
        Session session = AuthService.current();
        return ApiResponse.ok(notificationService.markRead(session, id));
    }
}
