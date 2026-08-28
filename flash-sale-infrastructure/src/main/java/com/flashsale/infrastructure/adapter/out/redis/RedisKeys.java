package com.flashsale.infrastructure.adapter.out.redis;

/**
 * Redis 鍵命名的唯一來源。
 *
 * <p>鍵名散落在各處是快取類系統最常見的維護災難——改一個前綴就要全域搜尋字串。
 * 集中在這裡後，鍵的結構變更只會影響一個檔案。
 *
 * <p><b>Hash Tag 設計</b>：同一活動的三個鍵都帶 {@code {a<id>}} 標籤，
 * Redis Cluster 會依大括號內的內容計算 slot，確保它們落在同一個節點。
 * <b>這是 Lua 腳本能在叢集模式下運作的前提</b>——跨 slot 的多鍵腳本會被 Redis 直接拒絕。
 */
public final class RedisKeys {

    private static final String ACTIVITY_SLOT = "seckill:{a%d}:";

    private RedisKeys() {
    }

    /** 庫存餘量（String）。 */
    public static String stock(Long activityId) {
        return ACTIVITY_SLOT.formatted(activityId) + "stock";
    }

    /** 使用者已購量（Hash: userId → quantity），用於限購判斷。 */
    public static String userPurchased(Long activityId) {
        return ACTIVITY_SLOT.formatted(activityId) + "user";
    }

    /** 請求→訂單映射（Hash: requestId → orderNo），冪等與補償的憑據。 */
    public static String requestBinding(Long activityId) {
        return ACTIVITY_SLOT.formatted(activityId) + "req";
    }

    /** 活動靜態資訊的 L2 快取。 */
    public static String activityCache(Long activityId) {
        return "seckill:cache:activity:" + activityId;
    }

    /** 已上架活動列表的 L2 快取。 */
    public static String onlineActivitiesCache() {
        return "seckill:cache:activity:online";
    }

    /** 搶購請求受理狀態，供前端輪詢。 */
    public static String requestStatus(String orderNo) {
        return "seckill:req-status:" + orderNo;
    }

    /** 快取重建鎖（防擊穿）。 */
    public static String cacheRebuildLock(Long activityId) {
        return "seckill:lock:cache-rebuild:" + activityId;
    }
}
