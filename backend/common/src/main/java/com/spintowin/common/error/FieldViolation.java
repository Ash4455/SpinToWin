package com.spintowin.common.error;

/** Problem Details {@code errors[]} 中的一筆欄位錯誤。 */
public record FieldViolation(String field, String message) {
}
