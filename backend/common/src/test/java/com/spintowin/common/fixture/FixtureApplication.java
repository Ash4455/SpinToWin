package com.spintowin.common.fixture;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 測試用的最小服務：只掃描本套件的 fixture controller，
 * 錯誤處理完全來自 common 的 auto-configuration（與正式服務相同的接法）。
 */
@SpringBootApplication
class FixtureApplication {
}
