package com.flashsale.application.port.out;

import com.flashsale.domain.risk.BlacklistEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 黑名單。一個使用者最多一筆；已過期的視為不存在。 */
public interface BlacklistRepository {

    Optional<BlacklistEntry> findActive(Long userId, Instant now);

    /** 存在即覆寫。 */
    BlacklistEntry save(BlacklistEntry entry);

    void delete(Long userId);

    /** 含已過期的，新到舊——過期的留著是給人看「他曾經被列過」。 */
    List<BlacklistEntry> findAll(int limit, int offset);

    long count();
}
