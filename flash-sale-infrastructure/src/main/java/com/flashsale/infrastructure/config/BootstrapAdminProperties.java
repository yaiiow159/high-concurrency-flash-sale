package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 初始管理員設定。 */
@ConfigurationProperties(prefix = "flash-sale.security.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String displayName) {

    /** 有沒有要啟用。信箱是開關——沒填就整個功能不存在。 */
    public boolean isConfigured() {
        return email != null && !email.isBlank();
    }

    /** 只填了信箱沒填密碼。 */
    public boolean isIncomplete() {
        return isConfigured() && (password == null || password.isBlank());
    }

    /** 顯示名稱可以省略，給一個中性的預設值。 */
    public String displayNameOrDefault() {
        return displayName == null || displayName.isBlank() ? "系統管理員" : displayName;
    }
}
