package com.flashsale.infrastructure.adapter.out.search;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 啟動時確保搜尋 alias 存在。 */
@Configuration
public class SearchIndexBootstrap {

    @Bean
    public ApplicationRunner ensureSearchAlias(ProductIndexAdmin indexAdmin) {
        return args -> indexAdmin.ensureAliasExists();
    }
}
