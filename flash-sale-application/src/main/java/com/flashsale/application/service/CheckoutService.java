package com.flashsale.application.service;

import com.flashsale.application.port.in.CartUseCase;
import com.flashsale.application.port.in.CheckoutUseCase;
import com.flashsale.application.port.in.PlaceOrderUseCase;
import com.flashsale.application.port.in.dto.CartView;
import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.domain.identity.Address;
import com.flashsale.domain.shipping.ShippingMethod;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 從購物車結帳。
 *
 * <p><b>刻意做成薄薄一層，把下單邏輯完全交給 {@link PlaceOrderUseCase}。</b>
 * 價格重新取、庫存扣減、地址快照、冪等——那些規則只該有一份實作。
 * 若這裡自己再寫一次下單流程，兩份實作遲早會分岔，
 * 而分岔的那一天不會有任何錯誤訊息。
 *
 * <p>整個流程在同一個交易裡：下單失敗則購物車不清空，
 * 購物車清空失敗則訂單一起回滾。少了這個保證，
 * 使用者會看到「訂單建立了但購物車還在」而重複下單。
 */
@Service
public class CheckoutService implements CheckoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final CartUseCase cartUseCase;
    private final PlaceOrderUseCase placeOrderUseCase;
    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;

    public CheckoutService(CartUseCase cartUseCase,
                           PlaceOrderUseCase placeOrderUseCase,
                           OrderRepository orderRepository,
                           AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
        this.cartUseCase = cartUseCase;
        this.placeOrderUseCase = placeOrderUseCase;
        this.orderRepository = orderRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code readOnly}：試算不該有能力改變任何東西。
     * 這不只是最佳化——它讓「試算會不會不小心核銷掉券」
     * 從一個需要讀程式碼確認的問題，變成資料庫會擋下的事。
     *
     * <p>空購物車回<b>全零的試算</b>而不是拋例外：使用者清空購物車時
     * 頁面會重新試算一次，那不是錯誤，不該讓畫面跳出一個紅框。
     * 真正該擋下空購物車的是 {@link #checkout}。
     */
    @Override
    @Transactional(readOnly = true)
    public CheckoutPreview preview(Long userId, Long couponId, Long addressId) {
        CartView cart = cartUseCase.view(userId);
        List<PlaceOrderUseCase.OrderItem> items = cart.items().stream()
                .filter(CartView.Item::purchasable)
                .map(item -> new PlaceOrderUseCase.OrderItem(item.skuId(), item.quantity()))
                .toList();
        if (items.isEmpty()) {
            return new CheckoutPreview(BigDecimal.ZERO, List.of(), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false, null,
                    BigDecimal.ZERO, List.of());
        }
        // 沒選地址就算不出運費。查地址而不是要求前端傳郵遞區號——
        // 讓呼叫端傳郵遞區號等於讓它決定運費區域，而離島是本島的兩三倍
        String postalCode = addressId == null ? null : addressRepository.findById(addressId)
                .filter(address -> address.userId().equals(userId))
                .map(Address::postalCode)
                .orElse(null);

        return placeOrderUseCase.preview(new PlaceOrderUseCase.PreviewCommand(
                userId, items, couponId, postalCode, ShippingMethod.HOME_DELIVERY));
    }

    @Override
    @Transactional
    public OrderView checkout(Long userId, String requestId, Long addressId, Long couponId,
                              ShippingMethod shippingMethod) {
        // 沒指定就宅配。多數人不會特別選，而讓它變成必填只是多一個會出錯的欄位
        ShippingMethod method = shippingMethod == null
                ? ShippingMethod.HOME_DELIVERY : shippingMethod;
        // 冪等檢查必須排在「購物車是空的」之前。
        //
        // 順序反了的話，網路逾時後重送會撞上「購物車是空的」——
        // 因為第一次其實成功了，只是回應在路上掉了，而購物車已經被清空。
        // 使用者看到的是「購物車是空的」，他會以為訂單沒成立而重新加購再下一次，
        // 結果買了兩份。冪等鍵存在的意義就是讓重送拿回原本那張訂單，
        // 而不是一個看起來像失敗的錯誤。
        Optional<OrderView> existing = orderRepository.findByRequestId(requestId)
                .map(OrderView::from);
        if (existing.isPresent()) {
            log.debug("requestId {} 已有訂單，回傳既有結果", requestId);
            return existing.get();
        }

        CartView cart = cartUseCase.view(userId);
        if (cart.items().isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        // 已下架的品項擋在這裡，錯誤訊息才說得出是哪一件。
        // 讓它一路走到庫存扣減才失敗的話，使用者只會看到一句
        // 籠統的「庫存不足」，而那甚至不是真正的原因。
        List<CartView.Item> unavailable = cart.items().stream()
                .filter(item -> !item.purchasable())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE,
                    "「%s」已下架，請先從購物車移除".formatted(
                            unavailable.get(0).productName()));
        }

        OrderView order = placeOrderUseCase.place(new PlaceOrderUseCase.PlaceOrderCommand(
                userId, requestId, addressId,
                cart.items().stream()
                        .map(item -> new PlaceOrderUseCase.OrderItem(item.skuId(), item.quantity()))
                        .toList(),
                couponId, method));

        cartUseCase.clear(userId);
        log.info("購物車結帳完成 userId={}, orderNo={}, 品項數={}",
                userId, order.orderNo(), cart.items().size());
        return order;
    }
}
