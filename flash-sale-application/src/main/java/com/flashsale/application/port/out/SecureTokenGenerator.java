package com.flashsale.application.port.out;

/** 不可預測的隨機字串產生埠（出站）。 */
public interface SecureTokenGenerator {

    /** 產生一個新的不透明 token 原值，只會回傳這一次。 */
    String generateToken();

    /** 產生輪替鏈識別。 */
    String generateFamilyId();

    /** 計算 token 的雜湊，作為儲存與查詢的鍵。 */
    String hashToken(String rawToken);
}
