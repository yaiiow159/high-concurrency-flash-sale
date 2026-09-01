package com.flashsale.domain.identity;

/** 帳號狀態。 */
public enum UserStatus {

    /** 正常，可登入與下單。 */
    ACTIVE,

    /**
     * 已停權，不可登入。
     *
     * <p>停權時必須<b>同時撤銷該使用者所有的 refresh token</b>——
     * 只改狀態而不撤銷，持有有效 access token 的人在令牌過期前仍能操作，
     * 停權會有最長一個 access token 生命週期的空窗。
     */
    SUSPENDED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
