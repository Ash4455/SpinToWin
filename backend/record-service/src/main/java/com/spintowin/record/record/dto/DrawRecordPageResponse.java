package com.spintowin.record.record.dto;

import com.spintowin.record.record.entity.DrawRecord;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 一頁 Draw Result 紀錄（新到舊）。
 *
 * @param content       這一頁的紀錄
 * @param page          頁碼，從 0 開始
 * @param size          每頁筆數
 * @param totalElements 全部筆數
 * @param totalPages    全部頁數（沒有紀錄時為 0）
 */
public record DrawRecordPageResponse(List<DrawRecordResponse> content, int page, int size, long totalElements,
                                     int totalPages) {

    public static DrawRecordPageResponse from(Page<DrawRecord> page) {
        return new DrawRecordPageResponse(page.getContent().stream().map(DrawRecordResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
