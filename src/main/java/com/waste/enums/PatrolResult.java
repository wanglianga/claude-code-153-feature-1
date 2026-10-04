package com.waste.enums;

/** 二次巡查结论 */
public enum PatrolResult {
    PENDING,    // 未二次巡查
    CLEARED,    // 已移走，消防通道恢复畅通
    STILL_THERE // 仍在占用消防通道
}
