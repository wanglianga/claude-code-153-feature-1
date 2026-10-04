package com.waste.enums;

/** 消防通道违规堆放整改任务状态 */
public enum RectifyStatus {
    PENDING,         // 待整改（整改时限内，暂不扣款，仅记录警告）
    OVERDUE_NOTIFIED,// 超期未整改，已自动通知物业经理与业主
    RECTIFIED,       // 已移走（限期内：仅警告；逾期后移走：按时长扣款）
    DEDUCTED,        // 已按规则扣款（影响消防通道/邻居投诉/二次巡查不合格）
    WAIVED           // 物业核实后撤销（误报）
}
