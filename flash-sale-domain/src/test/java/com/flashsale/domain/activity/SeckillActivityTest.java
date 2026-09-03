package com.flashsale.domain.activity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 活動聚合根的業務規則測試。
 *
 * <p>注意這裡<b>沒有任何 mock、沒有 Spring context、沒有資料庫</b>——
 * 純粹的物件與斷言，毫秒級執行完畢。這正是把業務規則放在無框架依賴的領域層的報酬：
 * 最核心的邏輯獲得最快、最穩定的測試回饋。
 */
class SeckillActivityTest {

    private static final Instant START = Instant.parse("2025-06-01T10:00:00Z");
    private static final Instant END = Instant.parse("2025-06-01T12:00:00Z");

    @Nested
    @DisplayName("可搶購性判定")
    class Purchasability {

        @Test
        @DisplayName("上架且在時間窗口內：允許搶購")
        void allowsPurchaseWhenOnlineAndWithinPeriod() {
            SeckillActivity activity = onlineActivity();

            assertThatCode(() -> activity.ensurePurchasableAt(START.plusSeconds(600)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("尚未開始：回傳 ACTIVITY_NOT_STARTED 而非籠統的錯誤")
        void rejectsBeforeStart() {
            SeckillActivity activity = onlineActivity();

            assertThatThrownBy(() -> activity.ensurePurchasableAt(START.minusSeconds(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACTIVITY_NOT_STARTED);
        }

        @Test
        @DisplayName("開始時刻本身即可搶購（左閉區間）")
        void allowsExactlyAtStart() {
            assertThatCode(() -> onlineActivity().ensurePurchasableAt(START))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("結束時刻已不可搶購（右開區間）")
        void rejectsExactlyAtEnd() {
            assertThatThrownBy(() -> onlineActivity().ensurePurchasableAt(END))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACTIVITY_ENDED);
        }

        @Test
        @DisplayName("未上架：即使在時間窗口內也不可搶購")
        void rejectsWhenNotOnline() {
            SeckillActivity draft = activityBuilder().status(ActivityStatus.DRAFT).build();

            assertThatThrownBy(() -> draft.ensurePurchasableAt(START.plusSeconds(600)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACTIVITY_OFFLINE);
        }
    }

    @Nested
    @DisplayName("限購與金額")
    class LimitAndAmount {

        @Test
        @DisplayName("數量等於限購上限：允許")
        void allowsQuantityEqualToLimit() {
            assertThatCode(() -> onlineActivity().ensureQuantityWithinLimit(2))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("數量超過限購上限：拒絕")
        void rejectsQuantityOverLimit() {
            assertThatThrownBy(() -> onlineActivity().ensureQuantityWithinLimit(3))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.USER_PURCHASE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("數量為 0 或負數：拒絕（否則會變成庫存回補漏洞）")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> onlineActivity().ensureQuantityWithinLimit(0))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> onlineActivity().ensureQuantityWithinLimit(-5))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("金額由聚合計算，不接受外部傳入")
        void calculatesAmountFromPrice() {
            assertThat(onlineActivity().calculateAmount(2))
                    .isEqualByComparingTo(new BigDecimal("59800.00"));
        }
    }

    @Nested
    @DisplayName("建構期不變條件")
    class Invariants {

        @Test
        @DisplayName("結束時間不晚於開始時間：直接拒絕建立")
        void rejectsInvalidPeriod() {
            assertThatThrownBy(() -> new ActivityPeriod(END, START))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("秒殺價非正數：直接拒絕建立")
        void rejectsNonPositivePrice() {
            assertThatThrownBy(() -> activityBuilder().seckillPrice(BigDecimal.ZERO).build())
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("上下架")
    class Listing {

        @Test
        @DisplayName("下架後任何時間都不可搶購，即使還在活動窗口內")
        void offlineBlocksPurchaseInsideWindow() {
            SeckillActivity offline = onlineActivity().takeOffline();

            assertThat(offline.status()).isEqualTo(ActivityStatus.OFFLINE);
            // START 與 END 之間的時刻，上架時本來是可以買的
            assertThat(offline.isPurchasableAt(START.plusSeconds(60))).isFalse();
        }

        @Test
        @DisplayName("回傳新實例，原本那個不受影響——熱路徑上每個請求都讀得到它")
        void transitionsReturnNewInstance() {
            SeckillActivity online = onlineActivity();

            SeckillActivity offline = online.takeOffline();

            assertThat(offline).isNotSameAs(online);
            assertThat(online.status()).isEqualTo(ActivityStatus.ONLINE);
        }

        @Test
        @DisplayName("誤下架可以重新上架——不給回頭路只會逼營運去直接改資料庫")
        void offlineCanBeRepublished() {
            SeckillActivity republished = onlineActivity().takeOffline().publish();

            assertThat(republished.status()).isEqualTo(ActivityStatus.ONLINE);
        }

        @Test
        @DisplayName("重複下架會被擋下，避免把「已經下架了」誤讀成「這次才生效」")
        void cannotTakeOfflineTwice() {
            SeckillActivity offline = onlineActivity().takeOffline();

            assertThatThrownBy(offline::takeOffline)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_ACTIVITY_STATE_TRANSITION);
        }

        @Test
        @DisplayName("草稿不能直接下架——它從來沒上架過，那個轉移沒有意義")
        void draftCannotBeTakenOffline() {
            SeckillActivity draft = activityBuilder().status(ActivityStatus.DRAFT).build();

            assertThatThrownBy(draft::takeOffline)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ILLEGAL_ACTIVITY_STATE_TRANSITION);
        }

        @Test
        @DisplayName("下架不改動價格、庫存與時間窗口——那些改了會讓既有訂單對不上")
        void transitionPreservesEverythingElse() {
            SeckillActivity online = onlineActivity();

            SeckillActivity offline = online.takeOffline();

            assertThat(offline.id()).isEqualTo(online.id());
            assertThat(offline.skuId()).isEqualTo(online.skuId());
            assertThat(offline.seckillPrice()).isEqualByComparingTo(online.seckillPrice());
            assertThat(offline.totalStock()).isEqualTo(online.totalStock());
            assertThat(offline.perUserLimit()).isEqualTo(online.perUserLimit());
            assertThat(offline.period()).isEqualTo(online.period());
        }
    }

    private static SeckillActivity onlineActivity() {
        return activityBuilder().status(ActivityStatus.ONLINE).build();
    }

    private static SeckillActivity.Builder activityBuilder() {
        return SeckillActivity.builder()
                .id(1001L)
                .skuId(2001L)
                .productName("測試商品")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000)
                .perUserLimit(2)
                .period(START, END)
                .status(ActivityStatus.ONLINE);
    }
}
