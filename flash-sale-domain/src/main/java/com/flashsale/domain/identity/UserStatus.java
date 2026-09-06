package com.flashsale.domain.identity;

/** 帳號狀態。 */
public enum UserStatus {

    /** 正常，可登入與下單。 */
    ACTIVE,

    /** 已停權，不可登入。 */
    SUSPENDED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
