package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.BlacklistView;
import com.flashsale.application.port.in.dto.PageView;

import java.time.Instant;

/** 後台維護黑名單。 */
public interface RiskAdminUseCase {

    PageView<BlacklistView> list(int page, int size);

    /** 已在名單上的人再列一次會覆寫原因與到期時間。expiresAt 為 null 代表永久。 */
    BlacklistView add(Long targetUserId, String reason, Instant expiresAt, Long operatorUserId);

    void remove(Long targetUserId);
}
