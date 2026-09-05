package com.flashsale.application.service;

import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.CheckoutPreview;
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
import com.flashsale.application.port.out.ShippingRateRepository;
import com.flashsale.domain.shipping.ShippingFeeCalculator;
import com.flashsale.domain.shipping.ShippingMethod;
import com.flashsale.domain.shipping.ShippingZone;
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
import java.util.Map;
import java.util.stream.Collectors;
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
    private final AddressRepository addressRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final EventOutbox eventOutbox;
    private final PromotionRepository promotionRepository;
    private final ShippingRateRepository shippingRateRepository;
    private final Clock clock;

    public OrderPlacementService(ProductRepository productRepository,
                                 AddressRepository addressRepository,
                                 InventoryService inventoryService,
                                 OrderRepository orderRepository,
                                 OrderNoGenerator orderNoGenerator,
                                 EventOutbox eventOutbox,
                                 PromotionRepository promotionRepository,
                                 ShippingRateRepository shippingRateRepository,
                                 Clock clock) {
        this.promotionRepository = promotionRepository;
        this.shippingRateRepository = shippingRateRepository;
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
        Priced priced = price(command.couponId(), command.userId(), lines,
                shippingInfo.postalCode(), command.shippingMethod(), now);
        redeemCouponIfUsed(command.couponId(), priced, orderNo, now);

        Order order = Order.place(orderNo, command.userId(), command.requestId(),
                priced.lines(), shippingInfo, priced.discounts(), priced.payable(),
                priced.shippingFee(), command.shippingMethod(), now);
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
        Priced priced = price(command.couponId(), command.userId(), lines,
                command.postalCode(), command.shippingMethod(), clock.instant());

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
                subtotal.subtract(priced.payable()), priced.payable(),
                priced.shippingFee(),
                // 有算出區域才代表運費是真的。沒選地址時 fee 是 0，
                // 但那不是免運——畫面要說得出差別
                priced.zone() != null,
                priced.zone() == null ? null : priced.zone().displayName(),
                priced.total(), previewLines);
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
    private Priced price(Long couponId, Long userId, List<OrderLine> lines,
                         String postalCode, ShippingMethod method, Instant now) {
        List<Promotion> candidates = new ArrayList<>(
                promotionRepository.findActivePromotions(now));

        addCouponRule(couponId, userId, now, candidates);

        List<PricedItem> items = lines.stream()
                .map(line -> new PricedItem(line.skuId(), line.unitPrice(),
                        line.quantity(), line.sourceActivityId()))
                .toList();
        PricingEngine.PricingResult result = PricingEngine.calculate(items, candidates, now);

        // 分攤結果寫回每一行。退款按這個數字退，不是單價 × 數量。
        // 沒有折扣時分攤就等於各行小計，因此這一段對兩種情況都適用
        List<OrderLine> pricedLines = lines;
        if (!result.discounts().isEmpty()) {
            pricedLines = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                pricedLines.add(lines.get(i).withAllocatedAmount(result.lineAllocations().get(i)));
            }
        }

        List<OrderDiscount> discounts = new ArrayList<>(result.discounts().stream()
                .map(applied -> new OrderDiscount(applied.type().name(), applied.sourceId(),
                        applied.name(), applied.amount()))
                .toList());

        // 運費算在商品定價**之後**：免運門檻看的是折後金額（ADR-0013 決策 2）。
        // 這也是 DiscountType.SHIPPING 排在最後一位的理由
        Shipping shipping = resolveShipping(lines, postalCode, method, result.payable(),
                candidates, now);
        if (shipping.discount() != null) {
            discounts.add(new OrderDiscount(shipping.discount().type().name(),
                    shipping.discount().sourceId(), shipping.discount().name(),
                    shipping.discount().amount()));
        }

        return new Priced(pricedLines, List.copyOf(discounts), result.payable(),
                shipping.netFee(), shipping.zone());
    }

    /**
     * 算運費，並套用免運優惠。
     *
     * <p>沒有收貨地址時運費為 0——秒殺通道與試算尚未選地址的情況都會走到這裡。
     * 那不是「免運」而是「還算不出來」，畫面要說得出差別。
     */
    private Shipping resolveShipping(List<OrderLine> lines, String postalCode,
                                     ShippingMethod method, BigDecimal goodsPayable,
                                     List<Promotion> promotions, Instant now) {
        if (postalCode == null || postalCode.isBlank()) {
            return new Shipping(BigDecimal.ZERO, null, null);
        }

        int totalWeight = totalWeightOf(lines);
        ShippingFeeCalculator.Result computed = ShippingFeeCalculator.calculate(
                totalWeight, postalCode, method, shippingRateRepository.findAll());

        AppliedDiscount discount = PricingEngine.shippingDiscount(
                computed.fee(), goodsPayable, promotions, now);
        BigDecimal netFee = discount == null
                ? computed.fee()
                : computed.fee().subtract(discount.amount());

        return new Shipping(netFee, discount, computed.zone());
    }

    /**
     * 訂單總重。
     *
     * <p>重量來自<b>商品目錄的當下值</b>而不是訂單行快照——
     * 訂單行存的是價格與名稱的快照（那些是成交條件），
     * 而重量是物流事實，它不該被凍結。商家把包裝改小了，
     * 下一單就該用新的重量算。
     */
    private int totalWeightOf(List<OrderLine> lines) {
        Map<Long, Sku> skusById = Product.skusById(productRepository
                .findBySkuIds(lines.stream().map(OrderLine::skuId).toList()));

        int total = 0;
        for (OrderLine line : lines) {
            Sku sku = skusById.get(line.skuId());
            int unitWeight = sku == null ? Sku.DEFAULT_WEIGHT_GRAMS : sku.weightGrams();
            total += unitWeight * line.quantity();
        }
        return total;
    }

    /** @param discount 免運折抵；{@code null} 代表沒有適用的優惠 */
    private record Shipping(BigDecimal netFee, AppliedDiscount discount, ShippingZone zone) {
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

    /**
     * 定價結果。
     *
     * @param payable     <b>商品</b>折後應付。運費不在裡面——那條恆等式
     *                    （各行分攤加總 == payable）是退款按行退的基礎
     * @param shippingFee 已扣掉免運折抵的實收運費
     */
    private record Priced(List<OrderLine> lines, List<OrderDiscount> discounts,
                          BigDecimal payable, BigDecimal shippingFee,
                          ShippingZone zone) {

        /** 這張訂單總共要付多少。付款金額用它，不是 payable。 */
        BigDecimal total() {
            return payable.add(shippingFee);
        }
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
        // **一次批次查完，不要逐筆查。**
        //
        // 先前是在迴圈裡呼叫 findBySkuId，而單筆訂單最多 50 個品項——
        // 那是下單路徑上的 50 次資料庫往返。在 200ms 延遲的環境下光這一段就 10 秒，
        // 而它們彼此不相依，本來就該一次問完。
        //
        // 批次方法本來就存在（findBySkuIds），先前只是沒有用它。
        Map<Long, Product> bySkuId = Product.bySkuId(productRepository
                .findBySkuIds(items.stream().map(OrderItem::skuId).toList()));

        List<OrderLine> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            Product product = bySkuId.get(item.skuId());
            if (product == null) {
                throw new BusinessException(ErrorCode.SKU_NOT_FOUND,
                        "找不到規格 %d".formatted(item.skuId()));
            }

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
