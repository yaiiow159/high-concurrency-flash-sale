package com.flashsale.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 應用啟動入口。
 *
 * <p>此模組是六角架構中的「組裝根」（Composition Root）：唯一知道所有實作類別存在的地方。
 * 領域層與應用層都不曉得自己跑在 Spring 上，也不曉得資料存在 MySQL 還是別的地方。
 *
 * <p>{@code @EntityScan} 與 {@code @EnableJpaRepositories} 必須顯式指向基礎設施模組——
 * 預設的掃描範圍只涵蓋本類別所在的套件，跨模組時掃不到。
 */
@SpringBootApplication(scanBasePackages = "com.flashsale")
@EntityScan(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.entity")
@EnableJpaRepositories(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.jpa")
@EnableScheduling
public class FlashSaleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSaleApplication.class, args);
    }
}
