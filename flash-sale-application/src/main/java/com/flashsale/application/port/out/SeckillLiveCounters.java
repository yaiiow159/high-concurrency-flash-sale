package com.flashsale.application.port.out;

import java.util.Map;

/**
 * 秒殺熱路徑的即時計數（出站）。
 *
 * <p>讀的是這個節點上的指標暫存值，<b>不是跨節點的總和</b>——跨節點請看 Grafana。
 * 後台監控要的是「這一秒發生了什麼」，而那個問題在單一節點上就能回答。
 */
public interface SeckillLiveCounters {

    Snapshot read(Long activityId);

    /**
     * @param attemptsByResult      success / rejected / error 的累計次數
     * @param rejectionsByCode      依錯誤碼分的拒絕次數
     * @param publishByOutcome      acked / pending / failed
     * @param compensationsByResult success / failure
     * @param persistedByResult     消費端落庫結果
     * @param attemptP95Millis      端到端耗時 p95；沒有樣本時為 0
     */
    record Snapshot(Map<String, Long> attemptsByResult,
                    Map<String, Long> rejectionsByCode,
                    Map<String, Long> publishByOutcome,
                    Map<String, Long> compensationsByResult,
                    Map<String, Long> persistedByResult,
                    double attemptP95Millis,
                    double attemptP99Millis) {
    }
}
