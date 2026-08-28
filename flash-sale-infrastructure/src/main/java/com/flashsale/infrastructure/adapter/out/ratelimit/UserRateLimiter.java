package com.flashsale.infrastructure.adapter.out.ratelimit;

/**
 * 分散式限流器。
 *
 * <p>與 Resilience4j 的 {@code RateLimiter} 是<b>互補而非重複</b>的兩層防護：
 * <ul>
 *   <li>Resilience4j：<b>單機</b>整體限流，保護這台機器不被打垮（本機記憶體，零延遲）</li>
 *   <li>本介面：<b>跨節點的單一使用者</b>限流，擋腳本刷單（Redis，一次 RTT）</li>
 * </ul>
 * 單機限流擋不住「一個機器人把請求打散到 20 台機器」的攻擊，
 * 全域限流則無法保護個別節點的資源。兩者缺一不可。
 */
public interface UserRateLimiter {

    /**
     * 嘗試取得令牌。
     *
     * @param scope 限流維度（如 {@code seckill}），與 key 組成桶的識別
     * @return 放行為 {@code true}
     */
    boolean tryAcquire(String scope, String key);
}
