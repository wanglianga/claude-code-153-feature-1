package com.waste.enums;

/** 消防通道占用影响等级（决定扣款档位） */
public enum ImpactLevel {
    NONE,    // 无实质影响（限期内自行移走）
    MINOR,   // 轻微（局部占用，未阻断通行）
    SERIOUS  // 严重（阻断消防通道 / 造成邻居投诉 / 二次巡查仍未移走）
}
