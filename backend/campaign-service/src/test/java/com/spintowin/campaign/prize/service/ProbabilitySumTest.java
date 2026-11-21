package com.spintowin.campaign.prize.service;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同一 Campaign 所有 Prize 的 ppm 總和不得超過 1,000,000（100%），剩餘即銘謝惠顧。 */
class ProbabilitySumTest {

    @Test
    void noPrizesLeavesEverythingToNoWin() {
        assertThat(ProbabilitySum.noWinPpm(List.of())).isEqualTo(1_000_000);
    }

    @Test
    void noWinIsTheRemainderToOneHundredPercent() {
        assertThat(ProbabilitySum.noWinPpm(List.of(10_000, 95_000, 300_000))).isEqualTo(595_000);
    }

    @Test
    void totalOfExactlyOneHundredPercentIsAllowed() {
        assertThatCode(() -> ProbabilitySum.requireWithinOneHundredPercent(List.of(400_000, 600_000)))
                .doesNotThrowAnyException();
    }

    @Test
    void totalOneppmOverOneHundredPercentIsRejected() {
        assertThatThrownBy(() -> ProbabilitySum.requireWithinOneHundredPercent(List.of(400_000, 600_001)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROBABILITY_SUM_EXCEEDED));
    }

    @Test
    void manyFullPrizesDoNotOverflowIntoAValidTotal() {
        List<Integer> ppms = java.util.Collections.nCopies(5_000, 1_000_000);

        assertThatThrownBy(() -> ProbabilitySum.requireWithinOneHundredPercent(ppms))
                .isInstanceOf(BusinessException.class);
    }
}
