package com.flashsale.application.port.out;

/**
 * 售罄標記埠（出站）——單機記憶體級的快速失敗閘門。
 *
 * <p>秒殺的流量分布極端：庫存 1000 件可能湧入 100 萬次請求，
 * 其中 99.9% 注定失敗。若每一次都打到 Redis，光是這些必敗請求就能把 Redis 打滿。
 *
 * <p>因此在 Lua 回報售罄的當下，於<b>本機 JVM</b> 記一個標記，
 * 後續請求連 Redis 都不必碰就直接返回。這是本專案削峰漏斗的第一層。
 *
 * <p><b>刻意不做跨節點同步</b>：各節點各自標記，最壞情況是每個節點多打一次 Redis，
 * 代價遠低於維護一致性的成本。標記帶短 TTL，庫存回補後會自然失效。
 */
public interface SoldOutMarker {

    boolean isSoldOut(Long activityId);

    void markSoldOut(Long activityId);

    /** 庫存補償退回後清除標記，讓退回的量能被重新搶購。 */
    void clear(Long activityId);
}
