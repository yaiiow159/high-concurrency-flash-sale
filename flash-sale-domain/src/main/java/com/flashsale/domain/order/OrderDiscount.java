package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 訂單上的一筆折扣<b>快照</b>。
 *
 * <h2>為什麼不直接存 Promotion 的 AppliedDiscount</h2>
 *
 * <p>那會讓 Ordering 永久依賴 Promotion——每一張訂單都帶著一個
 * 優惠脈絡的型別，於是「訂單」這件事再也無法脫離「優惠」被理解。
 *
 * <p>這與 {@code Address}（Identity）與 {@link ShippingInfo}（Ordering）
 * 刻意互不認得是同一個決定：兩邊各有一份形狀相近的值物件，
 * 轉換放在應用層。<b>為了避免耦合而產生的少量重複是可以接受的代價</b>，
 * 而反過來——為了少寫一個 record 就把兩個脈絡黏死——是不可以的。
 *
 * <p>內容是快照而非引用，理由與 {@link OrderLine} 的商品名稱、單價相同：
 * 優惠會下架、券會過期、規則會改，而三個月後的客訴要看的是<b>當時</b>折了什麼。
 *
 * @param sourceType 折扣來源的種類名稱。存字串而不是列舉，
 *                   是因為優惠脈絡日後新增種類時，歷史訂單上那些舊名稱
 *                   必須仍然讀得出來——把它綁成列舉，刪掉一個值就讀不回舊訂單了
 * @param amount     折抵金額，正數
 */
public record OrderDiscount(
        String sourceType,
        Long sourceId,
        String name,
        BigDecimal amount
) {

    public OrderDiscount {
        Objects.requireNonNull(sourceType, "sourceType 不可為 null");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折扣名稱不可為空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折抵金額必須為正數");
        }
    }

    /** 運費折抵的 {@code sourceType}。與 {@code DiscountType.SHIPPING} 的名稱一致。 */
    private static final String SHIPPING = "SHIPPING";

    /**
     * 這筆折抵是折運費還是折商品。
     *
     * <p>兩者在<b>金額恆等式裡的位置不同</b>（ADR-0019 決策 1）：
     * 商品折抵滿足「小計 − 折扣 == 折後應付」，
     * 而運費折抵折的是另一筆錢，放進那條等式會讓它失效。
     *
     * <p>用字串比對而不是加一個欄位：{@code sourceType} 存的本來就是
     * {@code DiscountType} 的名稱，多一個欄位等於把同一件事記兩次，
     * 而兩份記錄遲早會不一致。
     */
    public boolean appliesToShipping() {
        return SHIPPING.equals(sourceType);
    }
}
