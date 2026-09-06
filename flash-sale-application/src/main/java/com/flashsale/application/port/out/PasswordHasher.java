package com.flashsale.application.port.out;

import com.flashsale.domain.identity.PasswordHash;

/** 密碼雜湊埠（出站）。 */
public interface PasswordHasher {

    PasswordHash hash(String rawPassword);

    /** 比對明文與雜湊。 */
    boolean matches(String rawPassword, PasswordHash hash);

    /** 執行一次「假比對」以消耗與真實比對相當的時間。 */
    void wasteTime();
}
