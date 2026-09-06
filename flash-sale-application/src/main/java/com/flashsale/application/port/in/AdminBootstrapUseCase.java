package com.flashsale.application.port.in;

/**
 * 建立系統的<b>第一個</b>管理員。
 *
 * <p>在此之前，取得後台權限的唯一方法是直接下 SQL 改 {@code app_user.role}——
 * 對一個「clone 下來就能跑」的專案來說那是個斷點，
 * 而對正式環境來說那是個沒有紀錄、沒有守衛的操作。
 */
public interface AdminBootstrapUseCase {

    /**
     * 依設定建立或提升初始管理員。
     *
     * <p><b>只有在系統中還沒有任何管理員時才會動作</b>，見 {@link Outcome#SKIPPED}。
     *
     * @param email       管理員信箱
     * @param rawPassword 明文密碼，套用與一般註冊相同的長度政策
     * @param displayName 顯示名稱
     */
    Outcome bootstrap(String email, String rawPassword, String displayName);

    /** 這次啟動實際做了什麼。回傳而不是只寫 log，是為了讓測試能斷言。 */
    enum Outcome {

        /**
         * 系統中已經有管理員，這次什麼都不做。
         *
         * <p><b>這是刻意的，不是「順便省一次寫入」。</b> 若每次啟動都套用設定，
         * 那份設定就變成一條永久有效的提權後門——拿到設定檔（或環境變數）的人
         * 隨時能把任意帳號變成管理員，而且看起來完全像正常啟動。
         * 「只在還沒有管理員時生效」把它限制成一次性的開機動作。
         */
        SKIPPED,

        /** 信箱已存在的帳號被提升為管理員。 */
        PROMOTED,

        /** 建立了新的管理員帳號。 */
        CREATED
    }
}
