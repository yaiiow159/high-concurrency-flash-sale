package com.flashsale.application.port.out;

/**
 * 不可預測的隨機字串產生埠（出站）。
 *
 * <p>用於 refresh token 與輪替鏈識別。
 *
 * <p><b>實作必須使用密碼學安全的亂數來源</b>（{@code SecureRandom}）。
 * 用 {@code Math.random()} 或 {@code java.util.Random} 產生的 token 是可預測的——
 * 攻擊者取得少量樣本後就能推算出後續的值，等同沒有 token。
 */
public interface SecureTokenGenerator {

    /** 產生一個新的不透明 token 原值，只會回傳這一次。 */
    String generateToken();

    /** 產生輪替鏈識別。 */
    String generateFamilyId();

    /**
     * 計算 token 的雜湊，作為儲存與查詢的鍵。
     *
     * <p>token 原值只交給用戶端，儲存區只留雜湊——
     * 資料庫外洩時攻擊者拿到雜湊也換不到新令牌。
     *
     * <p>這裡不需要 BCrypt 這類慢雜湊：token 本身已是高熵隨機值，
     * 不像密碼那樣可被字典攻擊，用 SHA-256 即可，且查詢要夠快。
     */
    String hashToken(String rawToken);
}
