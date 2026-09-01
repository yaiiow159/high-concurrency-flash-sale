package com.flashsale.application.port.out;

import com.flashsale.domain.identity.PasswordHash;

/**
 * 密碼雜湊埠（出站）。
 *
 * <p>領域層不認得任何雜湊演算法，比對也不在聚合根裡——
 * BCrypt 把 salt 藏在雜湊字串內，比對需要演算法知識，那是基礎設施的事。
 */
public interface PasswordHasher {

    PasswordHash hash(String rawPassword);

    /**
     * 比對明文與雜湊。
     *
     * <p><b>實作必須是常數時間比較</b>，否則回應時間的細微差異會洩漏
     * 「前幾個字元對了」這種資訊。BCrypt 的 {@code matches} 已經處理了這件事。
     */
    boolean matches(String rawPassword, PasswordHash hash);

    /**
     * 執行一次「假比對」以消耗與真實比對相當的時間。
     *
     * <p>用途是防<b>時序攻擊下的帳號枚舉</b>：信箱不存在時若直接回錯誤，
     * 回應會明顯快於「信箱存在但密碼錯」，攻擊者能據此判斷哪些信箱已註冊。
     * 呼叫此方法讓兩條路徑的耗時接近。
     */
    void wasteTime();
}
