package com.flashsale.domain.identity;

import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("收貨地址")
class AddressTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final long USER = 88L;

    @Nested
    @DisplayName("快照與引用的分野")
    class SnapshotVsReference {

        @Test
        @DisplayName("改地址簿不會動到已經產生的快照——這是整個設計的重點")
        void editingAddressDoesNotAffectExistingSnapshot() {
            Address address = persisted();
            ShippingInfo snapshot = snapshotOf(address);

            address.update("李小華", "0987654321", "220",
                    "新北市", "板橋區", "文化路 99 號");

            assertThat(snapshot.recipientName()).isEqualTo("王小明");
            assertThat(snapshot.fullAddress()).contains("信義區").doesNotContain("板橋區");
            // 地址簿本身確實變了——會變正是它與快照必須分開的理由
            assertThat(address.recipientName()).isEqualTo("李小華");
        }

        @Test
        @DisplayName("快照六個欄位都要齊，缺一就不是一個能寄出去的地址")
        void snapshotRequiresEveryField() {
            assertThatThrownBy(() ->
                    new ShippingInfo("王小明", "0912345678", "110", "臺北市", "信義區", " "))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() ->
                    new ShippingInfo(null, "0912345678", "110", "臺北市", "信義區", "市府路 1 號"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("快照保留結構化欄位——物流 API 要的是分開的縣市與區")
        void snapshotKeepsStructuredFields() {
            ShippingInfo snapshot = snapshotOf(persisted());

            assertThat(snapshot.region()).isEqualTo("臺北市");
            assertThat(snapshot.district()).isEqualTo("信義區");
            assertThat(snapshot.fullAddress()).isEqualTo("110 臺北市信義區市府路 1 號");
        }

        @Test
        @DisplayName("電話遮蔽：看得出是不是這一筆，但拿不到完整號碼")
        void masksPhoneForLogs() {
            assertThat(snapshotOf(persisted()).maskedPhone()).isEqualTo("09******78");
        }
    }

    @Nested
    @DisplayName("擁有者")
    class Ownership {

        @Test
        @DisplayName("不是自己的地址一律當成不存在——回「無權限」等於確認這個 ID 有效")
        void foreignAddressLooksMissing() {
            Address address = persisted();

            assertThatThrownBy(() -> address.requireOwnedBy(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
        }

        @Test
        @DisplayName("自己的地址可以通過")
        void ownAddressPasses() {
            assertThatCode(() -> persisted().requireOwnedBy(USER)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("欄位驗證")
    class Validation {

        @Test
        @DisplayName("電話格式刻意寬鬆：只擋明顯不是電話的輸入")
        void phoneValidationIsDeliberatelyLoose() {
            assertThatCode(() -> withPhone("0912345678")).doesNotThrowAnyException();
            assertThatCode(() -> withPhone("02-2345-6789")).doesNotThrowAnyException();
            assertThatCode(() -> withPhone("+886 912 345 678")).doesNotThrowAnyException();

            assertThatThrownBy(() -> withPhone("不是電話")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> withPhone("123")).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("必填欄位空白一律拒絕，且錯誤訊息要指出是哪一個")
        void rejectsBlankRequiredFields() {
            assertThatThrownBy(() -> Address.create(USER, " ", "0912345678", "110",
                    "臺北市", "信義區", "市府路 1 號", false, NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("收件人");

            assertThatThrownBy(() -> Address.create(USER, "王小明", "0912345678", "110",
                    "臺北市", "信義區", " ", false, NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("地址");
        }

        @Test
        @DisplayName("郵遞區號必須是數字")
        void rejectsNonNumericPostalCode() {
            assertThatThrownBy(() -> Address.create(USER, "王小明", "0912345678", "ABC",
                    "臺北市", "信義區", "市府路 1 號", false, NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("前後空白會被去掉，不會存進一個看起來一樣卻比對不到的字串")
        void trimsInput() {
            Address address = Address.create(USER, "  王小明  ", " 0912345678 ", " 110 ",
                    " 臺北市 ", " 信義區 ", " 市府路 1 號 ", false, NOW);

            assertThat(address.recipientName()).isEqualTo("王小明");
            assertThat(address.region()).isEqualTo("臺北市");
        }
    }

    @Nested
    @DisplayName("個資保護")
    class PrivacyByDefault {

        @Test
        @DisplayName("toString 不吐收件人與電話——不該因為有人加了一行 log 就散進日誌")
        void toStringOmitsPersonalData() {
            String text = persisted().toString();

            assertThat(text).doesNotContain("王小明").doesNotContain("0912345678");
            assertThat(text).contains("id=").contains("userId=");
        }
    }

    // ---- fixtures ----

    private static Address persisted() {
        return Address.restore(7L, USER, "王小明", "0912345678", "110",
                "臺北市", "信義區", "市府路 1 號", true, NOW);
    }

    private static Address withPhone(String phone) {
        return Address.create(USER, "王小明", phone, "110",
                "臺北市", "信義區", "市府路 1 號", false, NOW);
    }

    /**
     * 模擬應用層的轉換。
     *
     * <p>刻意寫在測試裡而不是 {@code Address} 上：讓 Identity 認得 Ordering 的型別
     * 會把兩個脈絡黏在一起。正式的轉換在 {@code OrderPlacementService}。
     */
    private static ShippingInfo snapshotOf(Address address) {
        return new ShippingInfo(address.recipientName(), address.phone(), address.postalCode(),
                address.region(), address.district(), address.streetAddress());
    }
}
