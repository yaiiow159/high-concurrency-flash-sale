package com.flashsale.infrastructure.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.infrastructure.adapter.out.redis.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多級活動快取的失效行為。
 *
 * <h2>這支測試守的是什麼</h2>
 *
 * <p>先前這個裝飾器<b>沒有任何失效路徑</b>——全檔唯一的 delete 只發生在
 * 反序列化失敗時。而活動狀態也沒有寫入埠，只能直接改資料庫，
 * 於是緊急下架一個有問題的活動之後，L1 過期後讀到的是 TTL 5~6 分鐘的 L2，
 * 最壞 6 分鐘內請求還是進得來、庫存照樣扣。
 *
 * <p>因此這裡驗的不是「有沒有呼叫 delete」，而是<b>行為</b>：
 * 下架之後再讀，讀到的必須是新狀態，不能是快取裡那份舊的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("活動多級快取")
class MultiLevelActivityRepositoryTest {

    private static final long ACTIVITY_ID = 1001L;
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");

    @Mock
    private ActivityRepository delegate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DistributedLock distributedLock;

    private MultiLevelActivityRepository repository;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // L2 一律未命中，把測試聚焦在 L1 與失效行為上
        when(valueOperations.get(anyString())).thenReturn(null);
        // 取得鎖後直接執行回源
        when(distributedLock.executeWithLock(anyString(), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(3)).get());

        repository = new MultiLevelActivityRepository(
                delegate, redisTemplate, distributedLock, new ObjectMapper());
    }

    private static SeckillActivity activity(ActivityStatus status) {
        return SeckillActivity.builder()
                .id(ACTIVITY_ID).skuId(2001L).productName("測試商品")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000).perUserLimit(2).period(START, END)
                .status(status).build();
    }

    @Test
    @DisplayName("下架之後再讀，讀到的是下架狀態而不是快取裡那份上架的")
    void updateInvalidatesSoNextReadSeesNewStatus() {
        SeckillActivity online = activity(ActivityStatus.ONLINE);
        SeckillActivity offline = activity(ActivityStatus.OFFLINE);
        when(delegate.findById(ACTIVITY_ID)).thenReturn(Optional.of(online));
        when(delegate.update(any())).thenReturn(offline);

        // 先讀一次把它放進 L1
        assertThat(repository.findById(ACTIVITY_ID))
                .get().extracting(SeckillActivity::status).isEqualTo(ActivityStatus.ONLINE);

        // 下架；資料庫之後回傳的就是新狀態
        when(delegate.findById(ACTIVITY_ID)).thenReturn(Optional.of(offline));
        repository.update(offline);

        assertThat(repository.findById(ACTIVITY_ID))
                .as("沒有失效的話這裡會拿到 L1 裡那份 ONLINE，而庫存會繼續被扣")
                .get().extracting(SeckillActivity::status).isEqualTo(ActivityStatus.OFFLINE);
    }

    @Test
    @DisplayName("L2 的鍵也要清——只清本機 L1 的話，其他節點回源時又會讀到舊值")
    void updateAlsoDeletesTheSharedCacheKey() {
        when(delegate.update(any())).thenReturn(activity(ActivityStatus.OFFLINE));

        repository.update(activity(ActivityStatus.OFFLINE));

        verify(redisTemplate).delete(RedisKeys.activityCache(ACTIVITY_ID));
    }

    @Test
    @DisplayName("上架清單也要清，否則首頁會繼續列出已下架的活動")
    void updateInvalidatesTheOnlineList() {
        SeckillActivity online = activity(ActivityStatus.ONLINE);
        when(delegate.findOnlineActivities()).thenReturn(java.util.List.of(online));
        when(delegate.update(any())).thenReturn(activity(ActivityStatus.OFFLINE));

        assertThat(repository.findOnlineActivities()).hasSize(1);

        when(delegate.findOnlineActivities()).thenReturn(java.util.List.of());
        repository.update(activity(ActivityStatus.OFFLINE));

        assertThat(repository.findOnlineActivities())
                .as("首頁列表沒清的話，已下架的活動還會掛在上面讓人點進去")
                .isEmpty();
    }

    @Test
    @DisplayName("Redis 清除失敗不該讓下架失敗——資料庫已經寫進去了")
    void redisFailureDoesNotBreakTakeOffline() {
        SeckillActivity offline = activity(ActivityStatus.OFFLINE);
        when(delegate.update(any())).thenReturn(offline);
        when(redisTemplate.delete(anyString())).thenThrow(new IllegalStateException("Redis 掛了"));

        assertThat(repository.update(offline)).isEqualTo(offline);
    }
}
