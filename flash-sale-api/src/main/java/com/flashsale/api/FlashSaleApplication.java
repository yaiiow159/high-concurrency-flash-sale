package com.flashsale.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 應用啟動入口。
 *
 * <p>此模組是六角架構中的「組裝根」（Composition Root）：唯一知道所有實作類別存在的地方。
 * 領域層與應用層都不曉得自己跑在 Spring 上，也不曉得資料存在 MySQL 還是別的地方。
 *
 * <p>刻意保持極簡——JPA 的掃描範圍由 {@code JpaConfig} 負責，
 * 因為 Entity 與 Repository 的位置是基礎設施的實作細節，組裝根不該知道。
 */
@SpringBootApplication(scanBasePackages = "com.flashsale")
@EnableScheduling
public class FlashSaleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSaleApplication.class, args);
    }
}
