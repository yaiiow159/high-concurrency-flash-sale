package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始管理員設定。
 *
 * <p><b>三個欄位都沒有預設值，這是刻意的。</b> 給了預設的信箱與密碼，
 * 等於每一次部署都自帶一組已知憑證的管理員帳號——那是會出現在
 * 漏洞報告裡的那種預設值，而且因為系統「就這樣跑起來了」，
 * 沒有人會發現它存在。
 *
 * <p>不設定就完全不啟用，這是預設行為。
 *
 * <pre>
 * flash-sale:
 *   security:
 *     bootstrap-admin:
 *       email: ops@example.com
 *       password: ${BOOTSTRAP_ADMIN_PASSWORD}
 *       display-name: 營運
 * </pre>
 *
 * @param email       管理員信箱；留空代表不啟用
 * @param password    明文密碼，套用與一般註冊相同的長度政策。
 *                    <b>應由環境變數注入，不要寫進版控裡的設定檔</b>
 * @param displayName 顯示名稱
 */
@ConfigurationProperties(prefix = "flash-sale.security.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String displayName) {

    /** 有沒有要啟用。信箱是開關——沒填就整個功能不存在。 */
    public boolean isConfigured() {
        return email != null && !email.isBlank();
    }

    /**
     * 只填了信箱沒填密碼。
     *
     * <p>這種情況要<b>當場失敗</b>而不是安靜略過：設定的人明顯是想啟用它，
     * 而安靜略過的結果是他以為建好了、實際上沒有，
     * 然後在需要進後台的那一刻才發現。
     */
    public boolean isIncomplete() {
        return isConfigured() && (password == null || password.isBlank());
    }

    /** 顯示名稱可以省略，給一個中性的預設值。 */
    public String displayNameOrDefault() {
        return displayName == null || displayName.isBlank() ? "系統管理員" : displayName;
    }
}
