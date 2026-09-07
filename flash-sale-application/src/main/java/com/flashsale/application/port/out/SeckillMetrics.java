package com.flashsale.application.port.out;

import com.flashsale.application.port.out.SeckillMetrics;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.domain.shared.ErrorCode;

/**
 * 秒殺鏈路的業務指標。實作必須保證：記錄失敗絕不影響業務流程（吞掉自己的例外），
 * 且標籤基數有界——絕不可放 userId 或 orderNo，那會讓時間序列數量爆炸。
 */
public interface SeckillMetrics {

    void recordSuccess(Long activityId, long startNanos);

    void recordRejection(Long activityId, ErrorCode errorCode, long startNanos);

    /** 非預期的失敗（Redis 掛掉等）。與 rejection 分開：那是「賣完了」，這是「壞掉了」。 */
    void recordError(Long activityId, long startNanos);

    /** 資格預檢結果：granted 或被拒的錯誤碼名稱。被拒的比例一高就是有人在刷。 */
    void recordQualification(String result);

    /** 庫存補償結果；{@code success=false} 代表退庫失敗，需要人工或對帳排程介入。 */
    void recordCompensation(Long activityId, String trigger, boolean success);

    void recordOrderPersisted(Long activityId, String result);

    void recordReconciliation(ActivityReconciliation result);

    /** 孤兒扣減的偵測與修復結果。{@code action} 為 detected / repaired / repair-failed 等。 */
    void recordOrphanBinding(Long activityId, String action);
}
