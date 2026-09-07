package com.flashsale.application.service;

import com.flashsale.application.port.in.MembershipUseCase;
import com.flashsale.application.port.in.ProductSalesUseCase;
import com.flashsale.application.port.in.ReturnUseCase;
import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.in.dto.ReturnRequestView;
import com.flashsale.application.port.in.dto.ReturnableView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.application.port.out.ReturnNoGenerator;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.payment.PaymentStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.Page;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 退貨退款服務（ADR-0011）。 */
@Service
public class ReturnService implements ReturnUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    /** 可以申請退貨的訂單狀態。 */
    private static final Set<OrderStatus> RETURNABLE_STATUSES =
            Set.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    /** 貨已經離開倉庫，因此需要買家寄回。 */
    private static final Set<OrderStatus> REQUIRES_GOODS_RETURN =
            Set.of(OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    private static final int MAX_PAGE_SIZE = 50;

    private final ReturnRequestRepository returnRepository;
    private final ReturnNoGenerator returnNoGenerator;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EventOutbox eventOutbox;
    private final MembershipUseCase membershipUseCase;
    private final ProductSalesUseCase productSalesUseCase;
    private final Clock clock;

    public ReturnService(ReturnRequestRepository returnRepository,
                         ReturnNoGenerator returnNoGenerator,
                         OrderRepository orderRepository,
                         PaymentRepository paymentRepository,
                         EventOutbox eventOutbox,
                         MembershipUseCase membershipUseCase,
                         Clock clock,
                         ProductSalesUseCase productSalesUseCase) {
        this.membershipUseCase = membershipUseCase;
        this.productSalesUseCase = productSalesUseCase;
        this.returnRepository = returnRepository;
        this.returnNoGenerator = returnNoGenerator;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.eventOutbox = eventOutbox;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnableView inspectReturnable(String orderNo, Long userId) {
        Order order = requireOwnedOrder(orderNo, userId);
        if (!RETURNABLE_STATUSES.contains(order.status())) {
            return new ReturnableView(orderNo, false,
                    unreturnableReason(order.status()), false, List.of());
        }

        Map<Long, Integer> remaining = returnableQuantities(order);
        List<ReturnableView.Line> lines = order.lines().stream()
                .map(line -> new ReturnableView.Line(line.skuId(), line.skuSnapshot(),
                        line.unitPrice(), line.quantity(),
                        Math.max(remaining.getOrDefault(line.skuId(), 0), 0),
                        line.allocatedAmount()))
                .toList();

        // 每一項都退完了就等於整張不能再退。回 true 卻沒有任何可選項目，
        // 使用者會看到一張空表單而不知道為什麼
        boolean anythingLeft = lines.stream().anyMatch(line -> line.returnableQuantity() > 0);
        return new ReturnableView(orderNo, anythingLeft,
                anythingLeft ? null : "這張訂單的品項都已經申請過退貨了",
                REQUIRES_GOODS_RETURN.contains(order.status()), lines);
    }

    /** 把「為什麼不能退」講清楚。只回一個 false 會讓使用者以為是系統壞了。 */
    private static String unreturnableReason(OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> "這張訂單還沒付款，不需要退款——直接取消即可";
            case REFUNDED -> "這張訂單已經全額退款完成";
            case CANCELLED, FAILED -> "這張訂單已經取消，沒有款項需要退回";
            default -> "這張訂單目前的狀態無法申請退貨";
        };
    }

    @Override
    @Transactional
    public ReturnRequestView open(OpenReturnCommand command) {
        // ⚠ 必須是本交易的第一個查詢。REPEATABLE READ 的讀取快照建立於第一次一般 SELECT，
        // 排在取鎖之前的話，後續讀取看不到對手已提交的寫入——實測併發全部超額通過
        // （ReturnConcurrencyIntegrationTest 會抓到）。
        Order order = requireOwnedOrderForUpdate(command.orderNo(), command.userId());

        // 冪等：逾時重送同一個 requestId 拿回同一張退貨單。
        // 與下單同一個手法，理由更強——重複下單只是多一張待付款訂單，
        // 重複退貨是同一批商品被退兩次
        Optional<ReturnRequest> existing = returnRepository.findByRequestId(command.requestId());
        if (existing.isPresent()) {
            log.debug("requestId {} 已有退貨單，回傳既有結果", command.requestId());
            return ReturnRequestView.from(existing.get());
        }
        if (!RETURNABLE_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_RETURNABLE,
                    "訂單 %s 目前狀態為 %s，無法申請退貨".formatted(order.orderNo(), order.status()));
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨單至少要有一個品項");
        }

        Map<Long, Integer> remaining = returnableQuantities(order);
        List<ReturnLine> lines = command.items().stream()
                .map(item -> toReturnLine(order, item, remaining))
                .toList();

        ReturnNo returnNo = returnNoGenerator.next();
        ReturnRequest request = ReturnRequest.open(returnNo, order.orderNo(), command.userId(),
                command.requestId(), lines, command.reason(), command.reasonDetail(),
                REQUIRES_GOODS_RETURN.contains(order.status()), clock.instant());

        ReturnRequest saved = returnRepository.save(request);
        log.info("已開立退貨單 returnNo={}, orderNo={}, 金額={}",
                returnNo, order.orderNo(), saved.refundAmount());
        return ReturnRequestView.from(saved);
    }

    @Override
    @Transactional
    public ReturnRequestView cancel(String returnNo, Long userId) {
        ReturnRequest request = requireOwnedReturn(returnNo, userId);
        request.cancel(clock.instant());
        return ReturnRequestView.from(returnRepository.update(request));
    }

    @Override
    @Transactional
    public ReturnRequestView approve(String returnNo, String note) {
        ReturnRequest request = requireReturn(returnNo);
        request.approve(note, clock.instant());
        return ReturnRequestView.from(returnRepository.update(request));
    }

    @Override
    @Transactional
    public ReturnRequestView reject(String returnNo, String note) {
        ReturnRequest request = requireReturn(returnNo);
        request.reject(note, clock.instant());
        return ReturnRequestView.from(returnRepository.update(request));
    }

    @Override
    @Transactional
    public ReturnRequestView receive(String returnNo, Map<Long, Boolean> restockDecisions) {
        ReturnRequest request = requireReturn(returnNo);
        request.receive(restockDecisions, clock.instant());
        return ReturnRequestView.from(returnRepository.update(request));
    }

    @Override
    @Transactional
    public ReturnRequestView refund(String returnNo) {
        ReturnRequest request = requireReturn(returnNo);
        Instant now = clock.instant();

        Payment payment = paymentRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "訂單 %s 沒有付款紀錄，無從退款".formatted(request.orderNo())));

        Order order = requireOrder(request.orderNo().value());

        // 運費退不退由退貨原因決定（ADR-0019 決策 7）。
        //
        // 「全額退貨」的判準是**這次退完之後訂單沒有任何可退的東西了**，
        // 而不是「這張退貨單涵蓋所有品項」——分兩次退完應該與一次退完結果相同。
        // 而那需要訂單層級的視野，聚合根自己看不到同一張訂單的其他退貨單。
        boolean fullyReturned = returnableQuantities(order).values().stream()
                .allMatch(remaining -> remaining <= 0);
        BigDecimal shippingRefund = request.shouldRefundShipping(fullyReturned)
                ? order.shippingFee()
                : BigDecimal.ZERO;
        BigDecimal totalRefund = request.refundAmount().add(shippingRefund);

        // 第三層防重複：累計退款不可超過已收金額。前兩層都在退貨的脈絡裡，
        // 而 PaymentRefundScheduler 走的是另一條路，看不到退貨單。
        //
        // 上限是 payableAmount（含運費），因此運費退得出來——
        // 若付款當初只收了 totalAmount，這裡就會撞上限而失敗
        payment.applyRefund(totalRefund, now);
        paymentRepository.save(payment);

        request.startRefund(now);
        // 事件先取出來。update() 可能回傳一個從 entity 重建的新物件，
        // 而重建出來的聚合根身上沒有剛剛註冊的事件——那會讓退款靜靜地不發生
        List<DomainEvent> events = request.pullDomainEvents();
        ReturnRequest updated = returnRepository.update(request);

        // 全額退完才動訂單狀態。用付款聚合根的判斷而不是自己再算一次——
        // 它剛剛才根據累計金額決定了 REFUNDED 還是 PARTIALLY_REFUNDED，
        // 這裡重算等於製造第二個真實來源
        if (payment.status() == PaymentStatus.REFUNDED) {
            order.markFullyRefunded("退貨單 " + returnNo, now);
            orderRepository.update(order);
            log.info("訂單 {} 已全額退款", order.orderNo());
        }

        // 積分扣回放在同一個交易：走事件會開一個「錢退了、積分還沒扣」的窗口。
        // 基準用商品實付（不含運費），與當初發點的基準一致
        membershipUseCase.clawbackForReturn(order.userId(), order.orderNo().value(),
                returnNo, request.refundAmount(), order.totalAmount());

        // 銷量扣回，同樣在這個交易裡。不扣的話「買了再退」就能把商品
        // 刷上暢銷榜，而那是可以無限重複的——與積分扣回同一個立場。
        //
        // 扣的是**這一次退的量**，不是整張訂單：部分退貨很常見，
        // 整張扣會讓銷量比實際少。冪等鍵因此是退貨單號
        productSalesUseCase.recordReturn(returnNo, request.lines().stream()
                .collect(Collectors.toMap(ReturnLine::skuId, ReturnLine::quantity, Integer::sum)));

        eventOutbox.append(events);
        log.info("已送出退款 returnNo={}, 商品={}, 運費={}, 付款狀態={}",
                returnNo, request.refundAmount(), shippingRefund, payment.status());
        return ReturnRequestView.from(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnRequestView findForUser(String returnNo, Long userId) {
        return ReturnRequestView.from(requireOwnedReturn(returnNo, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequestView> listForUser(Long userId, int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        return returnRepository.findByUserId(userId, paging.size(), paging.offset()).stream()
                .map(ReturnRequestView::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequestView> listByStatus(ReturnStatus status, int limit) {
        return returnRepository.findByStatus(status, Math.clamp(limit, 1, MAX_PAGE_SIZE)).stream()
                .map(ReturnRequestView::from)
                .toList();
    }

    /** 每個 SKU 還能退幾個。 */
    private Map<Long, Integer> returnableQuantities(Order order) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (OrderLine line : order.lines()) {
            remaining.merge(line.skuId(), line.quantity(), Integer::sum);
        }
        for (ReturnRequest existing : returnRepository.findByOrderNo(order.orderNo().value())) {
            if (!existing.status().holdsReturnQuota()) {
                continue;
            }
            for (ReturnLine line : existing.lines()) {
                remaining.merge(line.skuId(), -line.quantity(), Integer::sum);
            }
        }
        return remaining;
    }

    private ReturnLine toReturnLine(Order order, OpenReturnCommand.Item item,
                                    Map<Long, Integer> remaining) {
        if (item.quantity() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨數量必須大於 0");
        }
        OrderLine orderLine = order.lines().stream()
                .filter(line -> line.skuId().equals(item.skuId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "訂單 %s 沒有 SKU %d".formatted(order.orderNo(), item.skuId())));

        int available = remaining.getOrDefault(item.skuId(), 0);
        if (item.quantity() > available) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED,
                    "SKU %d 尚可退 %d 件，本次要求 %d 件"
                            .formatted(item.skuId(), available, item.quantity()));
        }
        // 同一張申請裡重複列出同一個 SKU 時，額度必須連續扣減，
        // 否則兩行各自對照原始餘額都會通過
        remaining.put(item.skuId(), available - item.quantity());

        // 退款金額按分攤後的實付算，不是「單價 × 數量」——後者退的是使用者沒付過的錢，
        // 而部分退貨不會被付款上限擋下
        int returnedBefore = orderLine.quantity() - available;
        BigDecimal refund = orderLine.refundFor(returnedBefore, item.quantity());

        return ReturnLine.of(orderLine.skuId(), orderLine.skuSnapshot(),
                orderLine.unitPrice(), item.quantity(), refund);
    }

    /** 取訂單並鎖住那一列；只有真的要寫退貨單時才用，查詢一律走不加鎖的版本。 */
    private Order requireOwnedOrderForUpdate(String orderNo, Long userId) {
        Order order = orderRepository.findByOrderNoForUpdate(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "訂單不存在: " + orderNo));
        return requireOwnership(order, orderNo, userId);
    }

    private Order requireOwnedOrder(String orderNo, Long userId) {
        Order order = requireOrder(orderNo);
        return requireOwnership(order, orderNo, userId);
    }

    private static Order requireOwnership(Order order, String orderNo, Long userId) {
        if (!order.belongsTo(userId)) {
            // 回「不存在」而非「無權限」：後者等於確認這個單號是有效的
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "訂單不存在: " + orderNo);
        }
        return order;
    }

    private Order requireOrder(String orderNo) {
        return orderRepository.findByOrderNo(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "訂單不存在: " + orderNo));
    }

    private ReturnRequest requireOwnedReturn(String returnNo, Long userId) {
        ReturnRequest request = requireReturn(returnNo);
        if (!request.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND,
                    "退貨單不存在: " + returnNo);
        }
        return request;
    }

    private ReturnRequest requireReturn(String returnNo) {
        Optional<ReturnRequest> found = returnRepository.findByReturnNo(ReturnNo.of(returnNo));
        return found.orElseThrow(() -> new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND,
                "退貨單不存在: " + returnNo));
    }
}
