package com.spintowin.common.event;

/**
 * draw.completed 事件無法解析或不符合合約（例如缺少 requestId），無法安全地處理。
 * 重試也不會成功，因此消費端的 error handler 不重試，直接把它送進該消費端的 dead-letter topic。
 */
public class InvalidDrawEventException extends RuntimeException {

    public InvalidDrawEventException(String message) {
        super(message);
    }

    public InvalidDrawEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
