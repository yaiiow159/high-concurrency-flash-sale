package com.flashsale.application.service;

import com.flashsale.application.port.in.CouponQueryUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.CouponView;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.identity.Address;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderDiscount;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.promotion.AppliedDiscount;
import com.flashsale.domain.promotion.Coupon;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.PricedItem;
import com.flashsale.domain.promotion.PricingEngine;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.stock.StockDeductionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
public class OrderPlacementService implements PlaceOrderUseCase, CouponQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final ProductRepository productRepository;
    private final AddressRepository addressRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final EventOutbox eventOutbox;
    private final PromotionRepository promotionRepository;
    private final Clock clock;

    public OrderPlacementService(ProductRepository productRepository,
                                 AddressRepository addressRepository,
                                 InventoryService inventoryService,
                                 OrderRepository orderRepository,
                                 OrderNoGenerator orderNoGenerator,
                                 EventOutbox eventOutbox,
                                 PromotionRepository promotionRepository,
                                 Clock clock) {
        this.promotionRepository = promotionRepository;
        this.productRepository = productRepository;
        this.addressRepository = addressRepository;
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
        ShippingInfo shippingInfo = resolveShippingInfo(command);
        List<OrderLine> lines = resolveLines(command.lines());

        deductInventory(command, orderNo, lines);

        // 定價在扣完庫存之後：庫存不足是最常見的失敗，先把它擋掉就不必為
        // 註定失敗的請求算優惠。而且券的核銷排在最後，扣庫存失敗時
        // 交易一起回滾，券自然不會被消耗掉
        Priced priced = price(command.couponId(), command.userId(), lines, now);
        redeemCouponIfUsed(command.couponId(), priced, orderNo, now);

        Order order = Order.place(orderNo, command.userId(), command.requestId(),
                priced.lines(), shippingInfo, priced.discounts(), priced.payable(), now);
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
     * {@inheritDoc}
     *
     * <p>{@code readOnly}：試算不該有能力改變任何東西。
     * 這不只是最佳化——它讓「試算會不會不小心把券核銷掉」
     * 從一個需要讀程式碼確認的問題，變成資料庫會擋下的事。
     */
    @Override
    @Transactional(readOnly = true)
    public CheckoutPreview preview(PreviewCommand command) {
        List<OrderLine> lines = resolveLines(command.lines());
        Priced priced = price(command.couponId(), command.userId(), lines, clock.instant());

        BigDecimal subtotal = lines.stream()
                .map(OrderLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CheckoutPreview.Line> previewLines = priced.lines().stream()
                .map(line -> new CheckoutPreview.Line(line.skuId(), line.skuSnapshot(),
                        line.unitPrice(), line.quantity(), line.subtotal(),
                        line.allocatedAmount()))
                .toList();

        List<OrderView.Discount> discounts = priced.discounts().stream()
                .map(discount -> new OrderView.Discount(discount.sourceType(),
                        discount.sourceId(), discount.name(), discount.amount()))
                .toList();

        return new CheckoutPreview(subtotal, discounts,
                subtotal.subtract(priced.payable()), priced.payable(), previewLines);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponView> myUsableCoupons(Long userId) {
        Instant now = clock.instant();
        return promotionRepository.findUsableCoupons(userId, now).stream()
                .flatMap(coupon -> promotionRepository.findPromotionById(coupon.promotionId())
                        // 規則被硬刪時券就沒有意義了。跳過而不是拋例外——
                        // 一張壞掉的券不該讓使用者連結帳頁都打不開
                        .filter(promotion -> promotion.isApplicableAt(now))
                        .map(promotion -> CouponView.of(coupon, promotion))
                        .stream())
                .toList();
    }

    /**
     * 套用優惠，並把折後金額分攤回每一行。
     *
     * <p><b>候選優惠由伺服器決定，不由呼叫端傳入。</b>
     * 呼叫端只能說「我要用這張券」，不能說「折我 500」——
     * 與價格一律由目錄決定同一個道理。
     *
     * <p>券的核銷是這裡的最後一步，而且刻意留在下單交易之內（ADR-0013 決策 7）。
     * 拆出去的話兩種順序都會壞：先核銷後建單則建單失敗時券白白消失，
     * 先建單後核銷則訂單享了折扣但券還在。
     */
    private Priced price(Long couponId, Long userId, List<OrderLine> lines, Instant now) {
        List<Promotion> candidates = new ArrayList<>(
                promotionRepository.findActivePromotions(now));

        addCouponRule(couponId, userId, now, candidates);

        List<PricedItem> items = lines.stream()
                .map(line -> new PricedItem(line.skuId(), line.unitPrice(),
                        line.quantity(), line.sourceActivityId()))
                .toList();
        PricingEngine.PricingResult result = PricingEngine.calculate(items, candidates, now);

        if (result.discounts().isEmpty()) {
            // 沒有任何優惠適用時，連券都不核銷——使用者選了券卻沒折到，
            // 券必須還在他手上。這也涵蓋了含秒殺行的訂單
            return new Priced(lines, List.of(), result.payable());
        }

        // 分攤結果寫回每一行。退款按這個數字退，不是單價 × 數量
        List<OrderLine> pricedLines = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            pricedLines.add(lines.get(i).withAllocatedAmount(result.lineAllocations().get(i)));
        }

        List<OrderDiscount> discounts = result.discounts().stream()
                .map(applied -> new OrderDiscount(applied.type().name(), applied.sourceId(),
                        applied.name(), applied.amount()))
                .toList();

        return new Priced(pricedLines, discounts, result.payable());
    }

    /**
     * 券真的折到錢時才核銷它。
     *
     * <p>沒折到就不消耗——使用者選了一張未達門檻的券，券必須還在他手上。
     *
     * <p><b>擋住重複使用的是那句條件式 UPDATE，不是前面的 {@code ensureUsableBy}。</b>
     * 「查券沒用過 → 建立訂單 → 標記已使用」是 read-modify-write，
     * 兩個並行請求都會通過檢查。真正的防線是「檢查與寫入在同一個 SQL 語句內」，
     * 因此這裡看的是受影響列數，不是任何 Java 端的判斷。
     */
    private void redeemCouponIfUsed(Long couponId, Priced priced,
                                    OrderNo orderNo, Instant now) {
        boolean couponApplied = couponId != null
                && priced.discounts().stream()
                        .anyMatch(discount -> DiscountType.COUPON.name()
                                .equals(discount.sourceType()));
        if (!couponApplied) {
            return;
        }
        if (!promotionRepository.redeem(couponId, orderNo.value(), now)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_USED, "這張優惠券已經使用過了");
        }
        log.info("訂單 {} 核銷優惠券 {}", orderNo.value(), couponId);
    }

    /**
     * 檢查使用者指定的券，並把它的折抵規則加進候選清單。
     *
     * <p>券的擁有權檢查在聚合根裡，且回「不存在」而非「不是你的」——
     * 後者等於確認這個券號有效，讓人可以靠窮舉找出別人的券。
     */
    private void addCouponRule(Long couponId, Long userId, Instant now,
                               List<Promotion> candidates) {
        if (couponId == null) {
            return;
        }
        Coupon coupon = promotionRepository.findCoupon(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND, "優惠券不存在"));
        coupon.ensureUsableBy(userId, now);

        Promotion rule = promotionRepository.findPromotionById(coupon.promotionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND,
                        "優惠券對應的規則不存在"));
        candidates.add(rule);
    }

    /** 定價結果：帶分攤金額的行、折扣明細、折後應付。 */
    private record Priced(List<OrderLine> lines, List<OrderDiscount> discounts,
                          BigDecimal payable) {
    }

    /**
     * 從地址簿取出地址，並<b>當場快照</b>成訂單的收貨資訊。
     *
     * <p>訂單不存 {@code addressId}。使用者搬家改了地址簿之後，
     * 三個月前那張已送達的訂單仍要顯示當初寄去的地方——
     * 那是出貨紀錄與客訴處理的依據，不是一個顯示欄位。
     *
     * <p>擁有者檢查在聚合根裡，且刻意在扣庫存<b>之前</b>做：
     * 用別人的地址 ID 下單應該直接被拒，不該先扣掉庫存再回滾。
     */
    private ShippingInfo resolveShippingInfo(PlaceOrderCommand command) {
        Address address = addressRepository.findById(command.addressId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
        address.requireOwnedBy(command.userId());

        // 轉換放在這裡而非 Address 上：讓 Identity 去認得 Ordering 的型別，
        // 等於把兩個脈絡黏在一起。應用層本來就是負責跨脈絡編排的地方。
        return new ShippingInfo(address.recipientName(), address.phone(),
                address.postalCode(), address.region(),
                address.district(), address.streetAddress());
    }

    /**
     * 依 SKU 查目錄，組出帶快照與價格的訂單行。
     *
     * <p>快照是<b>寫死進訂單</b>的字串，不是對商品的引用：
     * 商家日後改名或調價，歷史訂單不能跟著變。那是財務問題，不是顯示問題。
     */
    private List<OrderLine> resolveLines(List<OrderItem> items) {
        List<OrderLine> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
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
