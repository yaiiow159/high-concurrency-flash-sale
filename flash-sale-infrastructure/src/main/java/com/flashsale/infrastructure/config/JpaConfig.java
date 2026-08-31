package com.flashsale.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 掃描範圍設定。
 *
 * <p><b>為什麼從啟動類別搬到這裡？</b> Entity 與 Repository 的位置是<b>基礎設施的實作細節</b>，
 * 啟動模組不該知道它們放在哪個套件——那等於讓組裝根依賴於持久化技術的內部結構。
 *
 * <p>還有一個很實際的好處：掛在 {@code @SpringBootApplication} 上時，
 * {@code @WebMvcTest} 這類切片測試會連帶套用這些註解，導致必須有 EntityManagerFactory 才能啟動，
 * 一個純粹的 Web 層測試因此被迫拉起整個 JPA 基礎設施。
 * 移到普通的 {@code @Configuration} 後，切片測試不會載入它，Web 測試得以真正輕量。
 */
@Configuration
@EntityScan(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.entity")
@EnableJpaRepositories(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.jpa")
public class JpaConfig {
}
