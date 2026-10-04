package com.waste.enums;

/** 消防通道堆放整改任务状态 */
public enum RectificationStatus {
    OPEN,           // 待整改（限期未到）
    OVERDUE,        // 已超时（已通知，仍未移走）
    CLEARED,        // 限期内移走 → 仅警告
    PENALIZED       // 影响消防通道/邻居投诉/超时 → 已扣款并计入信用
}
