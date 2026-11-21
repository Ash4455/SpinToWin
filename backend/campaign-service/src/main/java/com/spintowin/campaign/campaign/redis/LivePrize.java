package com.spintowin.campaign.campaign.redis;

import com.spintowin.campaign.prize.entity.Prize;

import java.util.List;

/** 一個 Prize 在 Redis live state 中的值（快照，不隨 entity 變動）。 */
public record LivePrize(long id, String name, int probabilityPpm, int remainingStock) {

    public static LivePrize of(Prize prize) {
        return new LivePrize(prize.getId(), prize.getName(), prize.getProbabilityPpm(), prize.getRemainingStock());
    }

    /** 依 Prize 順序（呼叫端傳入的順序，即 id 遞增）快照。 */
    public static List<LivePrize> of(List<Prize> prizes) {
        return prizes.stream().map(LivePrize::of).toList();
    }
}
