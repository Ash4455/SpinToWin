package com.spintowin.common.event;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * 消費 draw.completed 時的失敗分類：資料庫暫時無法使用（連線失敗、lock wait timeout、deadlock…）
 * 稍後重試就可能成功，不能把事件當成無法處理而跳過；其他失敗則是事件本身的問題。
 */
public final class TransientFailures {

    private TransientFailures() {
    }

    /** 例外（或其 cause 鏈上任何一個）是基礎設施暫時性的失敗。 */
    public static boolean isTransient(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof SQLTransientException
                    || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
