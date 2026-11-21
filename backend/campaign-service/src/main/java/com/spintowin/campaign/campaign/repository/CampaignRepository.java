package com.spintowin.campaign.campaign.repository;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStatus(CampaignStatus status);

    /**
     * 以 {@code SELECT ... FOR UPDATE} 鎖住 Campaign 列，直到交易結束。
     * 修改 Prize 前先取得此鎖，讓同一 Campaign 的「機率總和檢查 + 寫入」不會被並行請求穿插而超過 100%。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(Long id);
}
