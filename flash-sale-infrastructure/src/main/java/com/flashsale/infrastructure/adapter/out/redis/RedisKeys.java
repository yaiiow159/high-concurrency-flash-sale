package com.flashsale.infrastructure.adapter.out.redis;

/** Redis 鍵命名的唯一來源。 */
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
