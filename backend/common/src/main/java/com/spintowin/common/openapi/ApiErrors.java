package com.spintowin.common.openapi;

import com.spintowin.common.error.ErrorCode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 宣告這個 endpoint 可能回的錯誤碼。OpenAPI 文件會依 HTTP status 分組產生
 * {@code application/problem+json} 錯誤回應（含每個錯誤碼的說明與範例）。
 * {@code UNAUTHORIZED} 與 {@code FORBIDDEN} 每個 endpoint 都會自動加上，不用列。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiErrors {

    ErrorCode[] value();
}
