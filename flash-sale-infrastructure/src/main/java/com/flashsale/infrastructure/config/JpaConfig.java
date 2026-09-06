package com.flashsale.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** JPA 掃描範圍設定。 */
@Configuration
@EntityScan(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.entity")
@EnableJpaRepositories(basePackages = "com.flashsale.infrastructure.adapter.out.persistence.jpa")
public class JpaConfig {
}
