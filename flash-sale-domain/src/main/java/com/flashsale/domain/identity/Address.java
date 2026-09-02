package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 收貨地址簿條目。
 *
 * <p><b>是獨立的聚合根，不是 {@link User} 的一部分。</b>
 * 兩個理由：地址的增刪改與使用者本身無關，硬綁在一起會讓「改一個地址」
 * 變成「載入並寫回整個使用者」；而一個使用者可能有數十筆地址，
 * 載入使用者時把它們一起撈出來是純粹的浪費。
 *
 * <p><b>這個物件<u>不會</u>被放進訂單。</b>訂單存的是 Ordering 脈絡自己的
 * {@code ShippingInfo} 快照。兩者刻意分開，因為它們回答不同的問題：
 *
 * <ul>
 *   <li>{@code Address}：「這個使用者現在的收貨地址是什麼」——會變</li>
 *   <li>{@code ShippingInfo}：「這張訂單當初要寄到哪裡」——永遠不變</li>
 * </ul>
 *
 * <p>若訂單只存 {@code addressId}，使用者搬家改了地址簿之後，
 * 三個月前那張已送達的訂單會顯示成寄到新家。那不是顯示問題，
 * 是出貨紀錄與客訴處理的依據被竄改了。
 *
 * <p><b>這個類別刻意不認得 {@code ShippingInfo}。</b> 轉換由應用層負責
 * （{@code OrderPlacementService}）。讓 Identity 去 import Ordering 的型別，
 * 等於把兩個脈絡黏在一起——之後訂單那邊改一個欄位，地址簿就得跟著動。
 * 代價是 {@code fullAddress()} 的格式化在兩邊各有一份；
 * 那點重複遠比脈絡耦合便宜，而且它們本來就可能因為用途不同而分岔
 * （一個給使用者看，一個給物流單據用）。
 * ArchUnit 只管分層，抓不到脈絡間的耦合，只能靠 review 守住。
 */
public final class Address {

    private static final int MAX_RECIPIENT_LENGTH = 32;
    private static final int MAX_STREET_LENGTH = 128;

    private final Long id;
    private final Long userId;
    private final Instant createdAt;

    private String recipientName;
    private String phone;
    private String postalCode;
    private String region;
    private String district;
    private String streetAddress;
    private boolean defaultAddress;

    private Address(Long id, Long userId, String recipientName, String phone, String postalCode,
                    String region, String district, String streetAddress,
                    boolean defaultAddress, Instant createdAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.recipientName = requireText(recipientName, "收件人", MAX_RECIPIENT_LENGTH);
        this.phone = requireValidPhone(phone);
        this.postalCode = requirePostalCode(postalCode);
        this.region = requireText(region, "縣市", MAX_RECIPIENT_LENGTH);
        this.district = requireText(district, "鄉鎮市區", MAX_RECIPIENT_LENGTH);
        this.streetAddress = requireText(streetAddress, "地址", MAX_STREET_LENGTH);
        this.defaultAddress = defaultAddress;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
    }

    public static Address create(Long userId, String recipientName, String phone,
                                 String postalCode, String region, String district,
                                 String streetAddress, boolean defaultAddress, Instant now) {
        return new Address(null, userId, recipientName, phone, postalCode,
                region, district, streetAddress, defaultAddress, now);
    }

    public static Address restore(Long id, Long userId, String recipientName, String phone,
                                  String postalCode, String region, String district,
                                  String streetAddress, boolean defaultAddress, Instant createdAt) {
        return new Address(Objects.requireNonNull(id, "重建時 id 不可為 null"), userId,
                recipientName, phone, postalCode, region, district, streetAddress,
                defaultAddress, createdAt);
    }

    /**
     * 修改地址內容。
     *
     * <p>地址<b>可以</b>被修改——這正是它與訂單快照必須分開的理由。
     * 修改只影響「之後的訂單要寄到哪」，已經成立的訂單完全不受影響。
     */
    public void update(String recipientName, String phone, String postalCode,
                       String region, String district, String streetAddress) {
        this.recipientName = requireText(recipientName, "收件人", MAX_RECIPIENT_LENGTH);
        this.phone = requireValidPhone(phone);
        this.postalCode = requirePostalCode(postalCode);
        this.region = requireText(region, "縣市", MAX_RECIPIENT_LENGTH);
        this.district = requireText(district, "鄉鎮市區", MAX_RECIPIENT_LENGTH);
        this.streetAddress = requireText(streetAddress, "地址", MAX_STREET_LENGTH);
    }

    public void markAsDefault() {
        this.defaultAddress = true;
    }

    public void unmarkAsDefault() {
        this.defaultAddress = false;
    }

    /**
     * 確認這筆地址屬於指定使用者。
     *
     * <p>回傳 void 並拋例外，而不是回傳布林：權限檢查若能被忽略，
     * 遲早會有某個呼叫端忘記檢查，而那個 bug 的表現是「可以寄貨到別人家」。
     */
    public void requireOwnedBy(Long expectedUserId) {
        if (!Objects.equals(userId, expectedUserId)) {
            // 刻意回「不存在」而非「無權限」：後者等於告訴攻擊者這個 ID 是有效的，
            // 讓他能靠窮舉列舉出系統裡有多少地址
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
    }

    /** 供地址簿顯示的完整地址。 */
    public String fullAddress() {
        return "%s %s%s%s".formatted(postalCode, region, district, streetAddress);
    }

    /**
     * 台灣手機或市話。
     *
     * <p>刻意寬鬆：只擋明顯不是電話的輸入，不試圖窮舉所有合法格式。
     * 過嚴的電話驗證會擋掉真實存在的號碼（分機、境外門號），
     * 而那個損失遠大於放進一筆格式怪異的資料。
     */
    private static String requireValidPhone(String phone) {
        String trimmed = phone == null ? "" : phone.trim();
        if (!trimmed.matches("^[0-9+() -]{8,24}$")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "聯絡電話格式不正確");
        }
        return trimmed;
    }

    private static String requirePostalCode(String postalCode) {
        String trimmed = postalCode == null ? "" : postalCode.trim();
        if (!trimmed.matches("^[0-9]{3,6}$")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "郵遞區號格式不正確");
        }
        return trimmed;
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + "不可為空");
        }
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "%s不可超過 %d 字".formatted(field, maxLength));
        }
        return trimmed;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public String recipientName() {
        return recipientName;
    }

    public String phone() {
        return phone;
    }

    public String postalCode() {
        return postalCode;
    }

    public String region() {
        return region;
    }

    public String district() {
        return district;
    }

    public String streetAddress() {
        return streetAddress;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Address other && id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        // 不輸出收件人與電話：個資不該因為某人加了一行 log.debug(address) 就散進日誌
        return "Address{id=%s, userId=%d, region=%s, default=%s}"
                .formatted(id, userId, region, defaultAddress);
    }
}
