package com.spintowin.draw.draw.service;

/**
 * 每一抽的隨機數來源：回傳 {@code [0, 1,000,000)} 的整數（ppm 落點）。
 * 正式環境是 {@link SecureRandomPpmSource}；測試可換成固定序列，精準命中 ppm 區間的邊界。
 * 實作必須是 thread-safe（同一個 bean 服務所有請求）。
 */
public interface RandomPpmSource {

    int nextPpm();
}
