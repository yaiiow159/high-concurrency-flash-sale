package com.flashsale.application.service;

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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 退貨退款服務（ADR-0011）。
 *
 * <h2>可退數量的計算是防重複退款的第二層</h2>
 *
 * <p>「這件商品還能退幾個」等於<b>訂單行數量 − 所有仍佔用額度的退貨單上的數量</b>。
 * 關鍵在「仍佔用額度」包含還在審核中的單（見 {@code ReturnStatus.holdsReturnQuota}）——
 * 只算已退款的，買家可以在第一張單還在審核時開第二張，兩張都會過。
 *
 * <h2>庫存一律回到一般庫存，絕不回秒殺池</h2>
 *
 * <p>即使這一行來自秒殺活動也一樣。活動可能早就結束並釋放過額度了，
 * 把量寫回 Redis 等於復活一個已釋放的活動——那正是 ADR-0008 明文禁止、
 * 且實際發生過的超賣路徑。
 *
 * <h2>金流呼叫不在交易裡</h2>
 *
 * <p>{@link #refund} 只做三件事：扣減付款聚合根的可退額度、翻轉退貨單狀態、
 * 寫入 outbox。真正打金流與回補庫存的是消費端。
 * 遠端呼叫留在交易裡，會把資料庫交易的存活時間綁在對方的回應時間上，
 * 而逾時的結果是「不知道錢送出去了沒」。
 */
@Service
public class ReturnService implements ReturnUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    /**
     * 可以申請退貨的訂單狀態。
     *
     * <p>{@code PENDING_PAYMENT} 不在其中——錢都還沒收，
     * 「退款」無從退起，那個情境的正確操作是取消訂單。
     */
    private static final Set<OrderStatus> RETURNABLE_STATUSES =
            Set.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    /** 貨已經離開倉庫，因此需要買家寄回。 */
    private static final Set<OrderStatus> REQUIRES_GOODS_RETURN =
            Set.of(OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReturnRequestRepository returnRepository;
    private final ReturnNoGenerator returnNoGenerator;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EventOutbox eventOutbox;
    private final Clock clock;

    public ReturnService(ReturnRequestRepository returnRepository,
                         ReturnNoGenerator returnNoGenerator,
                         OrderRepository orderRepository,
                         PaymentRepository paymentRepository,
                         EventOutbox eventOutbox,
                         Clock clock) {
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
        // ⚠ 這一行必須是本交易的第一個查詢，順序是正確性的一部分。
        //
        // 它做兩件事：鎖住訂單那一列，把同一張訂單的額度計算序列化；
        // 以及——關鍵——確保後續的一致性讀取拿到的是最新資料。
        //
        // InnoDB 在 REPEATABLE READ 下，交易的讀取快照建立於**第一次一致性讀取**。
        // 若在取鎖之前先跑了任何一般 SELECT，快照就在那一刻定型；
        // 之後即使等到鎖、對手也已 commit，後續的一般 SELECT 仍讀不到它的寫入。
        // 這不是假設：先前把冪等查詢放在取鎖之前，八個併發執行緒
        // 對一張只買 2 件的訂單全部申請成功，累計 16 件
        // （ReturnConcurrencyIntegrationTest 會抓到）。
        //
        // FOR UPDATE 本身是當前讀、永遠讀得到最新已提交資料，
        // 但它不建立快照——所以只要它排在最前面，
        // 之後的一般 SELECT 才會以「拿到鎖的那一刻」為基準。
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

        // 第三層防重複：累計退款不可超過已收金額。前兩層都在退貨的脈絡裡，
        // 而 PaymentRefundScheduler 走的是另一條路，看不到退貨單
        payment.applyRefund(request.refundAmount(), now);
        paymentRepository.save(payment);

        request.markRefunded(now);
        // 事件先取出來。update() 可能回傳一個從 entity 重建的新物件，
        // 而重建出來的聚合根身上沒有剛剛註冊的事件——那會讓退款靜靜地不發生
        List<DomainEvent> events = request.pullDomainEvents();
        ReturnRequest updated = returnRepository.update(request);

        // 全額退完才動訂單狀態。用付款聚合根的判斷而不是自己再算一次——
        // 它剛剛才根據累計金額決定了 REFUNDED 還是 PARTIALLY_REFUNDED，
        // 這裡重算等於製造第二個真實來源
        if (payment.status() == PaymentStatus.REFUNDED) {
            Order order = requireOrder(request.orderNo().value());
            order.markFullyRefunded("退貨單 " + returnNo, now);
            orderRepository.update(order);
            log.info("訂單 {} 已全額退款", order.orderNo());
        }

        eventOutbox.append(events);
        log.info("已送出退款 returnNo={}, 金額={}, 付款狀態={}",
                returnNo, request.refundAmount(), payment.status());
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
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * pageSize;
        return returnRepository.findByUserId(userId, pageSize, offset).stream()
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

    /**
     * 每個 SKU 還能退幾個。
     *
     * <p>已佔用額度的退貨單<b>包含審核中與已核准</b>，不只已退款的。
     * 只扣已退款的，買家可以在第一張單還在審核時開第二張，兩張都會過。
     *
     * <p>反過來，被駁回與撤回的單必須把額度還回去，
     * 否則被駁回一次的商品就永遠不能再申請了。
     */
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

        // 單價取自訂單行的快照，不是重新查商品——商家調價後歷史訂單不能跟著變。
        //
        // 退款金額則另外算：整單折扣是折在訂單上、退貨卻是退一行，
        // 用「單價 × 數量」退的是使用者沒付過的錢。全額退貨會被付款金額上限擋下，
        // 但部分退貨不會——那筆多退的錢仍在上限之內。
        //
        // returnedBefore 用「原數量 − 尚可退」推導而不是另外記，
        // 因為 remaining 這張表本來就是為了額度檢查算出來的，兩者不該有兩個真實來源
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
