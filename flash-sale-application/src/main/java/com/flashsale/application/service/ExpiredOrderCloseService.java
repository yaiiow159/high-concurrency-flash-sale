package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.ExpiredOrderCloseUseCase;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.shared.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 逾期訂單關單服務。
 *
 * <p>逐筆處理而非整批更新，是刻意的取捨：批次 SQL 雖快，但會繞過聚合根的狀態機，
 * 使非法轉移得以發生，也拿不到領域事件。這裡的量級（每批數百筆）不值得為此犧牲正確性。
 *
 * <p>單筆失敗不中斷整批——一張訂單的髒資料不該讓其他訂單的庫存一直卡著。
 *
 * <p><b>兩種庫存的退回走不同路徑</b>，因為它們的一致性保證不同：
 * <ul>
 *   <li><b>一般庫存</b>：就在這個交易裡直接退。它與訂單同一個資料庫，
 *       關單失敗就一起回滾，不需要事件、補償或冪等——回滾就是補償</li>
 *   <li><b>秒殺庫存</b>：只寫退庫事件，由 {@code StockCompensationService} 非同步退。
 *       Redis 無法加入資料庫交易，若在交易內退而交易隨後回滾，
 *       庫存會憑空多出來——那是比少賣嚴重得多的超賣</li>
 * </ul>
 *
 * <p>判斷依據是<b>訂單行的 {@code sourceActivityId}</b>，不是訂單的 {@code channel}：
 * 庫存從哪裡來是「行」的屬性。未來購物車混入一個秒殺品項時，
 * 同一張訂單的不同行本來就該走不同路徑（ADR-0006 也明令不得依 channel 分支）。
 */
@Service
public class ExpiredOrderCloseService implements ExpiredOrderCloseUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpiredOrderCloseService.class);
    private static final String CLOSE_REASON = "逾時未付款，系統自動關閉";

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final EventOutbox eventOutbox;
    private final SeckillPolicy policy;
    private final Clock clock;

    public ExpiredOrderCloseService(OrderRepository orderRepository,
                                    InventoryService inventoryService,
                                    EventOutbox eventOutbox,
                                    SeckillPolicy policy,
                                    Clock clock) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.eventOutbox = eventOutbox;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int closeExpiredOrders() {
        Instant now = clock.instant();
        Instant deadline = now.minus(policy.paymentWindow());

        List<Order> expired =
                orderRepository.findExpiredPendingOrders(deadline, policy.compensationBatchSize());
        if (expired.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (Order order : expired) {
            if (closeOne(order, now)) {
                closed++;
            }
        }
        log.info("逾期關單完成：撈出 {} 筆，成功關閉 {} 筆", expired.size(), closed);
        return closed;
    }

    /**
     * 把一般庫存直接退回可售池。
     *
     * <p>{@code sourceActivityId == null} 的行代表這批貨扣的是可售池，
     * 不是劃撥給活動的額度（ADR-0008）。退回也走同一條路。
     *
     * <p>退庫本身仍以流水的唯一鍵做冪等：這個交易若因為批次中其他訂單而重跑，
     * 第二次不會重複退。冪等在這裡不是必要的（交易已經保證了），
     * 但它讓這段程式碼在未來被搬到交易外時仍然安全。
     */
    private void restoreStandardInventory(Order order) {
        for (OrderLine line : order.lines()) {
            if (line.sourceActivityId() != null) {
                continue;
            }
            inventoryService.restore(InventoryService.RestoreCommand.forNormal(
                    line.skuId(), order.userId(), line.quantity(),
                    order.requestId(), order.orderNo().value()));
        }
    }

    private boolean closeOne(Order order, Instant now) {
        try {
            order.cancel(CLOSE_REASON, now);
            orderRepository.update(order);
            restoreStandardInventory(order);
            // 秒殺庫存的退庫事件與關單狀態同交易寫入，避免「關了單卻沒退庫」的漏洞。
            eventOutbox.append(order.pullDomainEvents());
            return true;
        } catch (BusinessException e) {
            // 訂單在撈取後、關單前被付款了——這是正常的競態，不需告警。
            log.debug("訂單 {} 已非待付款狀態，略過：{}", order.orderNo(), e.getMessage());
            return false;
        }
    }
}
