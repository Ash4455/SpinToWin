package com.spintowin.draw.draw.model;

import org.jspecify.annotations.Nullable;

/**
 * 一次 Draw 的 Draw Result：抽中某個 Prize，或銘謝惠顧（{@code prizeId} 與 {@code prizeName} 皆為 null）。
 *
 * @param drawId 這一抽的唯一識別（消費端以它去重）
 */
public record DrawResult(String drawId, @Nullable Long prizeId, @Nullable String prizeName) {

    public boolean won() {
        return prizeId != null;
    }
}
