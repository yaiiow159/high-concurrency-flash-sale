package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 收貨地址簿條目。 */
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

    /** 修改地址內容。 */
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

    /** 確認這筆地址屬於指定使用者。 */
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

    /** 台灣手機或市話。 */
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
