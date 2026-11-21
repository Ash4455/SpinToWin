package com.spintowin.campaign.prize.repository;

import com.spintowin.campaign.prize.entity.ProcessedDrawEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProcessedDrawEventRepository extends JpaRepository<ProcessedDrawEvent, String> {

    /**
     * 記錄事件已處理；回傳 1 = 第一次處理，0 = 已處理過（重複投遞）。
     * <p>
     * 以 primary key 判斷，並行的同一事件會在 key 上等待先到者 commit，之後得到 0，因此不會重複扣減。
     * {@code INSERT IGNORE} 也會把欄位過長等錯誤降為 warning，呼叫端須先驗證 requestId 的長度。
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO processed_draw_event (request_id, campaign_id) VALUES (:requestId, :campaignId)",
            nativeQuery = true)
    int markProcessed(String requestId, long campaignId);
}
