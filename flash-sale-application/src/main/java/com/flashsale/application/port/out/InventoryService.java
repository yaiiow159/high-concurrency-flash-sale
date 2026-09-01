package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionResult;

import java.util.Objects;

/**
 * 庫存扣減埠（出站）——雙模型的統一入口。
 *
 * <p>背後有兩套完全不同的機制（ADR-0008）：
 *
 * <table border="1">
 *   <caption>通道與機制對應</caption>
 *   <tr><th>通道</th><th>機制</th><th>理由</th></tr>
 *   <tr><td>{@code NORMAL}</td><td>MySQL 行 + 樂觀鎖</td>
 *       <td>數萬個 SKU、衝突率極低，DB 完全夠用且天然有交易保證</td></tr>
 *   <tr><td>{@code SECKILL}</td><td>Redis Lua</td>
 *       <td>所有請求競爭同一行，DB 鎖會塌陷（ADR-0002）</td></tr>
 * </table>
 *
 * <p><b>呼叫端不該知道背後是 Redis 還是 MySQL。</b>
 * 路由由基礎設施層的 {@code RoutingInventoryService} 依通道決定，
 * 與 {@code MultiLevelActivityRepository} 用 Decorator 藏住快取是同一個手法。
 *
 * <p>這層間接的價值在退場時最明顯：若秒殺不再是業務重點，
 * 刪掉 Redis 實作與一段路由即可，所有呼叫端不受影響。
 */
public interface InventoryService {

    /**
     * 扣減庫存。
     *
     * <p>不拋業務例外——由呼叫端決定如何把 {@link StockDeductionResult} 映射為業務語意。
     * 基礎設施故障仍會拋 {@link RuntimeException}，那是另一回事。
     */
    StockDeductionResult deduct(DeductCommand command);

    /**
     * 退回先前的扣減。
     *
     * <p><b>必須冪等。</b>補償排程、DLQ 消費端與同步補償三個路徑可能同時
     * 對同一筆訂單發起退庫，重複呼叫只能真的退一次。
     *
     * @return {@code true} 表示本次確實退回了庫存
     */
    boolean restore(RestoreCommand command);

    /**
     * 扣減指令。
     *
     * <p>用靜態工廠而非公開建構子，因為兩條通道需要的欄位不同：
     * 秒殺要 {@code activityId} 與 {@code perUserLimit}，一般下單兩者都沒有。
     * 讓呼叫端自己組一個所有欄位的建構子，遲早會出現
     * 「一般下單卻填了 activityId」這種編譯得過但語意錯誤的呼叫。
     *
     * @param skuId        兩條通道都必填。秒殺也要記 SKU，否則對帳時
     *                     無法把 Redis 的扣減對回 MySQL 的劃撥量
     * @param perUserLimit 僅秒殺有意義；一般通道傳 {@link #NO_USER_LIMIT}
     */
    record DeductCommand(
            OrderChannel channel,
            Long skuId,
            Long activityId,
            Long userId,
            int quantity,
            int perUserLimit,
            String requestId,
            String orderNo) {

        public static final int NO_USER_LIMIT = 0;

        public DeductCommand {
            Objects.requireNonNull(channel, "channel 不可為 null");
            Objects.requireNonNull(skuId, "skuId 不可為 null");
            Objects.requireNonNull(userId, "userId 不可為 null");
            if (quantity <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "扣減數量必須大於 0");
            }
            if (channel == OrderChannel.SECKILL && activityId == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "秒殺扣減必須指定活動");
            }
        }

        public static DeductCommand forNormal(Long skuId, Long userId, int quantity,
                                              String requestId, String orderNo) {
            return new DeductCommand(OrderChannel.NORMAL, skuId, null, userId,
                    quantity, NO_USER_LIMIT, requestId, orderNo);
        }

        public static DeductCommand forSeckill(Long activityId, Long skuId, Long userId,
                                               int quantity, int perUserLimit,
                                               String requestId, String orderNo) {
            return new DeductCommand(OrderChannel.SECKILL, skuId, activityId, userId,
                    quantity, perUserLimit, requestId, orderNo);
        }
    }

    /** 退庫指令。欄位需求與 {@link DeductCommand} 對稱。 */
    record RestoreCommand(
            OrderChannel channel,
            Long skuId,
            Long activityId,
            Long userId,
            int quantity,
            String requestId,
            String orderNo) {

        public RestoreCommand {
            Objects.requireNonNull(channel, "channel 不可為 null");
            Objects.requireNonNull(skuId, "skuId 不可為 null");
            if (quantity <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退回數量必須大於 0");
            }
        }

        public static RestoreCommand forNormal(Long skuId, Long userId, int quantity,
                                               String requestId, String orderNo) {
            return new RestoreCommand(OrderChannel.NORMAL, skuId, null, userId,
                    quantity, requestId, orderNo);
        }

        public static RestoreCommand forSeckill(Long activityId, Long skuId, Long userId,
                                                int quantity, String requestId, String orderNo) {
            return new RestoreCommand(OrderChannel.SECKILL, skuId, activityId, userId,
                    quantity, requestId, orderNo);
        }
    }
}
