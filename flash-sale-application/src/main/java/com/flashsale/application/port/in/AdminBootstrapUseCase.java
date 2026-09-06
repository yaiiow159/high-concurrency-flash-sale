package com.flashsale.application.port.in;

/** 建立系統的<b>第一個</b>管理員。 */
public interface AdminBootstrapUseCase {

    /** 依設定建立或提升初始管理員。 */
    Outcome bootstrap(String email, String rawPassword, String displayName);

    /** 這次啟動實際做了什麼。回傳而不是只寫 log，是為了讓測試能斷言。 */
    enum Outcome {

        /** 系統中已經有管理員，這次什麼都不做。 */
        SKIPPED,

        /** 信箱已存在的帳號被提升為管理員。 */
        PROMOTED,

        /** 建立了新的管理員帳號。 */
        CREATED
    }
}
