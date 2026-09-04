package com.flashsale.infrastructure.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 管理用的 Kafka 客戶端（ADR-0023）。
 *
 * <p>只用來查佇列深度，<b>不參與任何訊息收發</b>。
 *
 * <p>設定沿用 Spring Boot 自動配置的 {@link KafkaAdmin}，
 * 而不是另外讀一份 bootstrap-servers——兩份設定遲早會分岔，
 * 而分岔的症狀是「監控看的是另一個叢集」，一個看起來一切正常的錯誤。
 */
@Configuration
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(KafkaAdmin kafkaAdmin) {
        return AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }
}
