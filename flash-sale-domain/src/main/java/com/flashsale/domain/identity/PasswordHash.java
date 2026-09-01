package com.flashsale.domain.identity;

import java.util.Objects;

/**
 * 密碼雜湊值物件。
 *
 * <p><b>領域層刻意只把它當成不透明字串</b>，完全不知道用的是 BCrypt、Argon2 還是別的。
 * 雜湊與比對由 {@code PasswordHasher} 埠負責，實作在基礎設施層。
 *
 * <p>這樣切的好處很實際：日後要換演算法（例如從 BCrypt 遷移到 Argon2），
 * 領域模型一行都不用動。
 *
 * <p><b>這個型別的存在本身就是一道防線</b>：方法簽章寫 {@code PasswordHash} 而非
 * {@code String}，編譯器就能擋下「不小心把明文密碼傳進來」這種錯誤。
 */
public record PasswordHash(String value) {

    public PasswordHash {
        Objects.requireNonNull(value, "密碼雜湊不可為 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("密碼雜湊不可為空白");
        }
    }

    /**
     * 永遠不輸出雜湊內容。
     *
     * <p>雜湊值外洩雖不等於密碼外洩，但會讓離線暴力破解成為可能。
     * 日誌與例外訊息都不該出現它。
     */
    @Override
    public String toString() {
        return "PasswordHash{***}";
    }
}
