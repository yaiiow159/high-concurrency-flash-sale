package com.flashsale.infrastructure.adapter.out.identity;

import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.domain.identity.PasswordHash;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密碼雜湊實作。
 *
 * <p><b>為什麼是 BCrypt 而不是 SHA-256？</b>
 * 密碼雜湊要的是「<b>慢</b>」。SHA-256 為速度而設計，現代 GPU 每秒能算數十億次，
 * 用它存密碼等於把外洩後的破解時間從數年壓縮到數小時。
 * BCrypt 刻意讓每次運算耗時數十毫秒，且成本因子可隨硬體進步調高。
 *
 * <p>成本因子 10 約需 50–100ms。這是登入延遲與破解難度之間的取捨：
 * 調高一階，破解成本加倍，登入也慢一倍。登入是低頻操作，這個代價值得付。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    /** 一個固定的合法 BCrypt 雜湊，僅用於帳號不存在時消耗等量時間。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String DUMMY_PASSWORD = "not-a-real-password";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Override
    public PasswordHash hash(String rawPassword) {
        return new PasswordHash(encoder.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash hash) {
        return encoder.matches(rawPassword, hash.value());
    }

    /**
     * {@inheritDoc}
     *
     * <p>對一個固定的假雜湊執行真實比對。刻意<b>不用 {@code Thread.sleep}</b>——
     * 睡固定時間反而製造出可辨識的時間特徵，而真實比對的耗時分佈
     * 與正常登入完全一致。
     */
    @Override
    public void wasteTime() {
        encoder.matches(DUMMY_PASSWORD, DUMMY_HASH);
    }
}
