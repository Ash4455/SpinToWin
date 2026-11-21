package com.spintowin.draw.draw.service;

import com.spintowin.common.probability.Ppm;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 正式的隨機數來源：{@link SecureRandom}，均勻分布在 {@code [0, 1,000,000)}。SecureRandom 本身是 thread-safe。 */
@Component
public class SecureRandomPpmSource implements RandomPpmSource {

    private final SecureRandom random = new SecureRandom();

    @Override
    public int nextPpm() {
        return random.nextInt(Ppm.ONE_HUNDRED_PERCENT);
    }
}
