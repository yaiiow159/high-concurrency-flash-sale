package com.flashsale.application.service;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一般下單：同步、單一交易。
 *
 * <p><b>整個流程包在一個交易裡，這是這條通道最大的優勢。</b>
 * 扣庫存與建訂單都寫 MySQL，任何一步失敗就整個回滾——
 * 不需要 Outbox 補償、不需要對帳兜底、不會有「庫存扣了但訂單沒建」的中間態。
 *
 * <p>秒殺通道付不起這個代價（單一熱點下交易會排隊塌陷），
 * 所以它用最終一致換吞吐，並為此背上補償、冪等、對帳三套機制。
 * 一般通道沒有那個需求，就不該付那個代價——
 * 把它也推進 MQ，換來的是「為什麼買一本書也要輪詢」。
 *
 * <p><b>價格一律由目錄決定。</b>呼叫端只說要買哪個 SKU、幾件；
 * 單價與商品快照都從 {@link Product} 取。前端若能決定價格，那就不叫價格了。
 */
@Service
public class OrderPlacementService implements PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final EventOutbox eventOutbox;
    private final Clock clock;

    public OrderPlacementService(ProductRepository productRepository,
                                 InventoryService inventoryService,
                                 OrderRepository orderRepository,
                                 OrderNoGenerator orderNoGenerator,
                                 EventOutbox eventOutbox,
                                 Clock clock) {
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.eventOutbox = eventOutbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderView place(PlaceOrderCommand command) {
        Optional<Order> existing = orderRepository.findByRequestId(command.requestId());
        if (existing.isPresent()) {
            // 重送同一個 requestId 拿回同一張訂單。使用者連點兩次不該被懲罰，
            // 更不該被扣兩次庫存。
            log.debug("requestId {} 已有訂單，回傳既有結果", command.requestId());
            return OrderView.from(existing.get());
        }

        Instant now = clock.instant();
        OrderNo orderNo = orderNoGenerator.next();
        List<OrderLine> lines = resolveLines(command);

        deductInventory(command, orderNo, lines);

        Order order = Order.place(orderNo, command.userId(), command.requestId(), lines, now);
        Order saved = orderRepository.saveIfAbsent(order)
                // 走到這裡代表兩個並行請求帶著同一個 requestId，
                // 而資料庫的唯一索引擋下了第二個。這是冪等的最後一道，不是錯誤。
                .orElseGet(() -> orderRepository.findByRequestId(command.requestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "訂單既存不下也查不到 requestId=" + command.requestId())));

        // 事件與訂單同一個交易寫入，這是 Outbox 的全部意義：
        // 訂單存在就必然有事件，不會出現「訂單建了但下游永遠不知道」。
        eventOutbox.append(saved.pullDomainEvents());

        log.info("一般下單完成 orderNo={}, userId={}, 品項數={}, 金額={}",
                saved.orderNo().value(), command.userId(), lines.size(), saved.totalAmount());
        return OrderView.from(saved);
    }

    /**
     * 依 SKU 查目錄，組出帶快照與價格的訂單行。
     *
     * <p>快照是<b>寫死進訂單</b>的字串，不是對商品的引用：
     * 商家日後改名或調價，歷史訂單不能跟著變。那是財務問題，不是顯示問題。
     */
    private List<OrderLine> resolveLines(PlaceOrderCommand command) {
        List<OrderLine> lines = new ArrayList<>(command.lines().size());
        for (PlaceOrderUseCase.OrderItem item : command.lines()) {
            Product product = productRepository.findBySkuId(item.skuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND,
                            "找不到規格 %d".formatted(item.skuId())));

            // 上下架與規格存在性的判斷在聚合根裡，錯誤碼才能區分
            // 「商品已下架」與「規格不存在」——前端需要顯示不同文案
            Sku sku = product.requirePurchasableSku(item.skuId());

            lines.add(new OrderLine(sku.id(), sku.snapshotFor(product.name()),
                    sku.price(), item.quantity(), null));
        }
        return lines;
    }

    /**
     * 逐行扣減可售量。
     *
     * <p><b>失敗時拋例外讓整個交易回滾</b>，先前幾行已扣掉的量會一併復原。
     * 這正是同步通道不需要補償機制的原因：回滾就是補償，而且是資料庫做的。
     *
     * <p>{@code sourceActivityId} 為 {@code null}——一般訂單不屬於任何活動，
     * 扣的是可售池而非劃撥出去的額度（ADR-0008）。
     */
    private void deductInventory(PlaceOrderCommand command, OrderNo orderNo,
                                 List<OrderLine> lines) {
        for (OrderLine line : lines) {
            StockDeductionResult result = inventoryService.deduct(
                    InventoryService.DeductCommand.forNormal(
                            line.skuId(), command.userId(), line.quantity(),
                            command.requestId(), orderNo.value()));

            if (!result.isSuccess() && !result.isDuplicate()) {
                throw new BusinessException(ErrorCode.SOLD_OUT,
                        "「%s」庫存不足".formatted(line.skuSnapshot()));
            }
        }
    }
}
