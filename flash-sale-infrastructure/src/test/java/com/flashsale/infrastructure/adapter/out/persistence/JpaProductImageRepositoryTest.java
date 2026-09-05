package com.flashsale.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原生查詢讀 {@code TINYINT(1)} 的型別。
 *
 * <p>這條測試存在的理由是一個實機才發現的 {@code ClassCastException}：
 * {@code variants_ready} 被直接轉成 {@code Number}，
 * 而 Connector/J 預設把 {@code TINYINT(1)} 回成 {@link Boolean}，
 * 於是每一次掛載圖片都變成「系統異常」。
 *
 * <p><b>編譯期看不出來，單元測試 mock 掉 EntityManager 也看不出來</b>——
 * 只有真的打到 MySQL 才會炸。這裡把轉換本身鎖住，
 * 讓下一個人不必再踩一次。
 */
@DisplayName("TINYINT(1) 旗標轉換")
class JpaProductImageRepositoryTest {

    @Test
    @DisplayName("驅動回 Boolean 時讀得出來")
    void acceptsBoolean() {
        // tinyInt1isBit=true（Connector/J 的預設）走這條
        assertThat(JpaProductImageRepository.flag(Boolean.TRUE)).isTrue();
        assertThat(JpaProductImageRepository.flag(Boolean.FALSE)).isFalse();
    }

    @Test
    @DisplayName("驅動回數字時也讀得出來")
    void acceptsNumber() {
        // tinyInt1isBit=false，或換成別的驅動時走這條。
        // 只支援其中一種的話，改連線字串就會壞，而那不會有編譯錯誤
        assertThat(JpaProductImageRepository.flag(1)).isTrue();
        assertThat(JpaProductImageRepository.flag(0)).isFalse();
        assertThat(JpaProductImageRepository.flag((byte) 1)).isTrue();
    }

    @Test
    @DisplayName("讀不懂的值當成否，而不是拋例外")
    void unknownIsFalse() {
        // 這個旗標只決定「要不要用變體網址」。判成否會退回原圖，
        // 那是安全的；拋例外會讓整張商品頁掛掉
        assertThat(JpaProductImageRepository.flag(null)).isFalse();
        assertThat(JpaProductImageRepository.flag("1")).isFalse();
    }
}
