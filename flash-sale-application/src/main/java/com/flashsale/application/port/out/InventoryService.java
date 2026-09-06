package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionResult;

import java.util.Objects;

/** 庫存扣減埠（出站）——雙模型的統一入口。 */
public interface InventoryService {

    /** 扣減庫存。 */
    StockDeductionResult deduct(DeductCommand command);

    /** 退回先前的扣減。 */
    boolean restore(RestoreCommand command);

    /** 扣減指令。 */
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

    /**
     * 退庫指令。欄位需求與 {@link DeductCommand} 對稱。
     *
     * @param returnNo 非 {@code null} 時代表這次退庫是退貨造成的（ADR-0011）。
     * 它會成為庫存流水的來源單號，而流水的唯一鍵包含來源單號——
     * 一張訂單可以有多張退貨單，全都記訂單號的話，
     * 第二張的回補會被判定為重複而安靜略過
     */
    record RestoreCommand(
            OrderChannel channel,
            Long skuId,
            Long activityId,
            Long userId,
            int quantity,
            String requestId,
            String orderNo,
            String returnNo) {

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
                    quantity, requestId, orderNo, null);
        }

        public static RestoreCommand forSeckill(Long activityId, Long skuId, Long userId,
                                                int quantity, String requestId, String orderNo) {
            return new RestoreCommand(OrderChannel.SECKILL, skuId, activityId, userId,
                    quantity, requestId, orderNo, null);
        }

        /** 退貨造成的退庫。 */
        public static RestoreCommand forReturn(Long skuId, Long userId, int quantity,
                                               String orderNo, String returnNo) {
            return new RestoreCommand(OrderChannel.NORMAL, skuId, null, userId,
                    quantity, returnNo, orderNo, returnNo);
        }
    }
}
