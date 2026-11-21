package com.spintowin.campaign.prize.entity;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.probability.Ppm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;

/**
 * {@code @DynamicUpdate}：UPDATE 只寫有變更的欄位。draw.completed consumer 以原子 UPDATE 扣減 remaining_stock，
 * 不經這個 entity；修改名稱或機率（未鎖 Prize 列）時若連同讀到的舊 remaining_stock 一起寫回，會蓋掉並行的扣減。
 * 會改 Stock 的操作（{@link #adjustStock}、{@link #deductDrawn}）須先以 SELECT ... FOR UPDATE 鎖住 Prize 列。
 */
@Entity
@Table(name = "prize")
@DynamicUpdate
public class Prize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Win Probability，單位 ppm。 */
    @Column(name = "probability_ppm", nullable = false)
    private int probabilityPpm;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "remaining_stock", nullable = false)
    private int remainingStock;

    protected Prize() {
    }

    private Prize(Long campaignId, String name, int probabilityPpm, int stock) {
        this.campaignId = campaignId;
        this.name = name;
        this.probabilityPpm = probabilityPpm;
        this.totalStock = stock;
        this.remainingStock = stock;
    }

    /** 新的 Prize：總 Stock 與剩餘 Stock 都等於 {@code stock}。機率總和由呼叫端（service）驗證。 */
    public static Prize create(Long campaignId, String name, int probabilityPpm, int stock) {
        return new Prize(campaignId, name, probabilityPpm, stock);
    }

    /** 修改名稱與 Win Probability。機率總和由呼叫端（service）驗證。 */
    public void update(String name, int probabilityPpm) {
        this.name = name;
        this.probabilityPpm = probabilityPpm;
    }

    /**
     * 以增減量調整 Stock：總 Stock 與剩餘 Stock 同時加上 {@code delta}。
     *
     * @throws BusinessException STOCK_NEGATIVE：剩餘 Stock 會變成負數
     */
    public void adjustStock(int delta) {
        long remaining = (long) remainingStock + delta;
        long total = (long) totalStock + delta;
        if (remaining < 0) {
            throw new BusinessException(ErrorCode.STOCK_NEGATIVE,
                    "Prize " + id + " has " + remainingStock + " remaining; a delta of " + delta
                            + " would make Stock negative.");
        }
        if (total > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Prize " + id + " Stock would exceed " + Integer.MAX_VALUE + ".");
        }
        this.remainingStock = (int) remaining;
        this.totalStock = (int) total;
    }

    /**
     * 扣減被抽走的 {@code count} 份，最少扣到 0（MySQL 不足時不讓剩餘 Stock 變成負數）。
     * 呼叫端須持有 Prize 列鎖。
     *
     * @return 不足而沒有扣到的份數；0 = 全數扣減
     */
    public int deductDrawn(int count) {
        int deducted = Math.min(count, remainingStock);
        this.remainingStock -= deducted;
        return count - deducted;
    }

    /** Win Probability 百分比（4 位小數）。 */
    public BigDecimal winProbabilityPercent() {
        return Ppm.toPercent(probabilityPpm);
    }

    public Long getId() {
        return id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public String getName() {
        return name;
    }

    public int getProbabilityPpm() {
        return probabilityPpm;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public int getRemainingStock() {
        return remainingStock;
    }
}
