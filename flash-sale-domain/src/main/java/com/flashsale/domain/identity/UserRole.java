package com.flashsale.domain.identity;

import java.util.List;

/** 使用者角色，決定令牌中會帶哪些 scope。 */
public enum UserRole {

    /** 一般消費者。 */
    CUSTOMER(List.of("seckill:order")),

    /** 營運人員，可執行預熱、對帳觸發等管理操作。 */
    ADMIN(List.of("seckill:order", "seckill:admin"));

    private final List<String> scopes;

    UserRole(List<String> scopes) {
        this.scopes = scopes;
    }

    /** 此角色對應的 scope 清單，寫入令牌的 {@code scope} claim。 */
    public List<String> scopes() {
        return scopes;
    }

    /** 以空白分隔的 scope 字串，即 OAuth2 的標準格式。 */
    public String scopeClaim() {
        return String.join(" ", scopes);
    }
}
