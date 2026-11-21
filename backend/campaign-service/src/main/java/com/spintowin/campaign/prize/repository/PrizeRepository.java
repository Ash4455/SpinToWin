package com.spintowin.campaign.prize.repository;

import com.spintowin.campaign.prize.entity.Prize;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PrizeRepository extends JpaRepository<Prize, Long> {

    /** 依 id 排序：Prize 順序決定 ppm 區間的累加順序。 */
    List<Prize> findByCampaignIdOrderByIdAsc(Long campaignId);

    /** 只找屬於該 Campaign 的 Prize；別的 Campaign 的 Prize 視同不存在。 */
    Optional<Prize> findByIdAndCampaignId(Long id, Long campaignId);

    /**
     * 同 {@link #findByIdAndCampaignId}，並以 {@code SELECT ... FOR UPDATE} 鎖住 Prize 列：
     * Stock 增減是 read-modify-write，鎖住才不會蓋掉並行的扣減（例如 draw.completed consumer）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Prize p where p.id = :id and p.campaignId = :campaignId")
    Optional<Prize> findForUpdateByIdAndCampaignId(Long id, Long campaignId);

    /**
     * 原子地把 remaining_stock 扣 {@code count}，只在扣完不會小於 0 時生效；回傳 1 = 已扣減，0 = 沒有扣減
     * （Prize 不屬於該 Campaign、不存在，或剩餘量不足）。
     * <p>
     * 單一 UPDATE 在 InnoDB 取得列鎖並以最新 commit 的值計算，與 ADMIN 的 Stock 增減（SELECT ... FOR UPDATE）序列化，
     * 不會互相覆蓋。
     */
    @Modifying
    @Query("""
            update Prize p set p.remainingStock = p.remainingStock - :count
            where p.id = :id and p.campaignId = :campaignId and p.remainingStock >= :count""")
    int deductRemainingStock(long id, long campaignId, int count);
}
