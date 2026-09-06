package com.flashsale.infrastructure.adapter.out.identity;

import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.domain.identity.PasswordHash;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 密碼雜湊實作。 */
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

    /** {@inheritDoc} */
    @Override
    public void wasteTime() {
        encoder.matches(DUMMY_PASSWORD, DUMMY_HASH);
    }
}
