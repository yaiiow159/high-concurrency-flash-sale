package com.flashsale.domain.activity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 活動時間窗口值物件（左閉右開區間 [startAt, endAt)）。
 *
 * <p>採左閉右開可避免「結束當下毫秒」的邊界爭議，也讓連續檔期不會重疊。
 */
public record ActivityPeriod(Instant startAt, Instant endAt) {

    public ActivityPeriod {
        Objects.requireNonNull(startAt, "startAt 不可為 null");
        Objects.requireNonNull(endAt, "endAt 不可為 null");
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "活動結束時間必須晚於開始時間");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }

    public boolean notStartedAt(Instant instant) {
        return instant.isBefore(startAt);
    }

    public boolean endedAt(Instant instant) {
        return !instant.isBefore(endAt);
    }
}
