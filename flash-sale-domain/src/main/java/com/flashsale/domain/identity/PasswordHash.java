package com.flashsale.domain.identity;

import java.util.Objects;

/** 密碼雜湊值物件。 */
public record PasswordHash(String value) {

    public PasswordHash {
        Objects.requireNonNull(value, "密碼雜湊不可為 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("密碼雜湊不可為空白");
        }
    }

    /** 永遠不輸出雜湊內容。 */
    @Override
    public String toString() {
        return "PasswordHash{***}";
    }
}
