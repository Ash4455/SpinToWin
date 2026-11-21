package com.spintowin.record.stats.service;

import com.spintowin.record.record.repository.DrawRecordRepository;
import com.spintowin.record.record.repository.DrawRecordRepository.PrizeNameCount;
import com.spintowin.record.stats.dto.CampaignStatsResponse;
import com.spintowin.record.stats.dto.CampaignStatsResponse.PrizeStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 後台統計：只依已落庫的紀錄計算（最終一致，可能比 Redis 的即時狀態稍晚）。 */
@Service
@Transactional(readOnly = true)
public class CampaignStatsService {

    private static final Comparator<PrizeNameCount> LATEST_NAME_FIRST =
            Comparator.comparing(PrizeNameCount::getLastDrawnAt).reversed()
                    .thenComparing(PrizeNameCount::getPrizeName);

    private final DrawRecordRepository repository;

    public CampaignStatsService(DrawRecordRepository repository) {
        this.repository = repository;
    }

    public CampaignStatsResponse stats(long campaignId) {
        long noWin = 0;
        Map<Long, List<PrizeNameCount>> byPrize = new TreeMap<>();
        for (PrizeNameCount group : repository.countByPrizeAndName(campaignId)) {
            if (group.getPrizeId() == null) {
                noWin += group.getDrawnCount();
            } else {
                byPrize.computeIfAbsent(group.getPrizeId(), id -> new ArrayList<>()).add(group);
            }
        }
        List<PrizeStats> prizes = byPrize.entrySet().stream()
                .map(e -> new PrizeStats(e.getKey(), latestName(e.getValue()),
                        e.getValue().stream().mapToLong(PrizeNameCount::getDrawnCount).sum()))
                .toList();
        long total = noWin + prizes.stream().mapToLong(PrizeStats::drawnCount).sum();
        return new CampaignStatsResponse(campaignId, total, noWin, prizes);
    }

    private static String latestName(List<PrizeNameCount> groups) {
        return groups.stream().min(LATEST_NAME_FIRST).orElseThrow().getPrizeName();
    }
}
