package com.spintowin.common.probability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Win Probability 百分比（最多 4 位小數）與 ppm 的換算。 */
class PpmTest {

    @Test
    void wholePercentConvertsToPpm() {
        assertThat(Ppm.fromPercent(new BigDecimal("1"))).isEqualTo(10_000);
    }

    @Test
    void fourDecimalPlacesConvertExactly() {
        assertThat(Ppm.fromPercent(new BigDecimal("0.0001"))).isEqualTo(1);
        assertThat(Ppm.fromPercent(new BigDecimal("9.5"))).isEqualTo(95_000);
        assertThat(Ppm.fromPercent(new BigDecimal("33.3333"))).isEqualTo(333_333);
    }

    @Test
    void boundariesZeroAndOneHundredPercent() {
        assertThat(Ppm.fromPercent(BigDecimal.ZERO)).isZero();
        assertThat(Ppm.fromPercent(new BigDecimal("100"))).isEqualTo(1_000_000);
    }

    @Test
    void trailingZerosBeyondFourDecimalsAreAccepted() {
        assertThat(Ppm.fromPercent(new BigDecimal("12.500000"))).isEqualTo(125_000);
    }

    @Test
    void moreThanFourSignificantDecimalsIsRejected() {
        assertThatThrownBy(() -> Ppm.fromPercent(new BigDecimal("0.00005")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfRangePercentIsRejected() {
        assertThatThrownBy(() -> Ppm.fromPercent(new BigDecimal("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ppm.fromPercent(new BigDecimal("100.0001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsBackToPercent() {
        assertThat(Ppm.toPercent(Ppm.fromPercent(new BigDecimal("0.5")))).isEqualByComparingTo("0.5000");
    }
}
