package com.flashsale.application.service;

import com.flashsale.application.port.in.ReviewUseCase.EditReviewCommand;
import com.flashsale.application.port.in.ReviewUseCase.WriteReviewCommand;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ReviewRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.ShippingInfo;
import com.flashsale.domain.review.Rating;
import com.flashsale.domain.review.Review;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品評價。
 *
 * <p>這裡盯的是「誰能評」——那是這個功能的全部價值。
 * 評價一旦可以任意張貼，它就一文不值，而那不是慢慢發生的，
 * 是從第一則刷出來的評價開始的。
 *
 * <p>另外盯聚合的更新方式：新增用 {@code addRating}、修改用 {@code replaceRating}。
 * 把修改寫成「先移除再新增」會讓中間有一瞬間的筆數少一，
 * 而 mock 測試看得到那個差別。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品評價")
class ReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER = 42L;
    private static final long SKU = 2001L;
    private static final long PRODUCT = 1L;
    private static final String ORDER_NO = "220600000000000001";

    @Mock private ReviewRepository reviewRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;

    private ReviewService service() {
        return new ReviewService(reviewRepository, orderRepository,
                productRepository, userRepository, CLOCK);
    }

    @Nested
    @DisplayName("誰能評價")
    class WhoCanReview {

        @Test
        @DisplayName("訂單已送達才能評——付了錢還沒收到貨的人對商品還沒有意見")
        void onlyCompletedOrdersCanBeReviewed() {
            givenOrder(OrderStatus.PAID);

            assertThatThrownBy(() -> service().write(command()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.ORDER_NOT_REVIEWABLE));

            verify(reviewRepository, never()).saveIfAbsent(any());
        }

        @Test
        @DisplayName("別人的訂單當作不存在——回「不是你的」等於確認這個單號有效")
        void othersOrderLooksMissing() {
            givenOrder(OrderStatus.COMPLETED, USER + 1);

            assertThatThrownBy(() -> service().write(command()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("不在訂單上的 SKU 評不了——那等於替沒買過的商品評價")
        void skuMustBeOnTheOrder() {
            givenOrder(OrderStatus.COMPLETED);
            givenProduct();

            assertThatThrownBy(() -> service().write(
                    new WriteReviewCommand(USER, ORDER_NO, 9999L, 5, "沒買過也想評")))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("唯一索引擋下重複時翻譯成 B0045，而不是讓約束例外冒上去")
        void duplicateIsTranslated() {
            givenOrder(OrderStatus.COMPLETED);
            givenProduct();
            givenUser();
            // 儲存庫回空 = 唯一索引擋下了。這是併發下的正常結果，不是系統錯誤
            when(reviewRepository.saveIfAbsent(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().write(command()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.ALREADY_REVIEWED));

            // 沒存成功就不該動聚合，否則平均分會多算一則不存在的評價
            verify(reviewRepository, never()).addRating(any(), any());
        }
    }

    @Nested
    @DisplayName("聚合的更新")
    class Aggregate {

        @Test
        @DisplayName("新增評價走 addRating，而不是先讀出來加完再寫回")
        void writeIncrementsTheAggregate() {
            givenOrder(OrderStatus.COMPLETED);
            givenProduct();
            givenUser();
            givenSaveEcho();

            service().write(command());

            verify(reviewRepository).addRating(PRODUCT, Rating.of(5));
        }

        @Test
        @DisplayName("修改走 replaceRating 帶著新舊兩個評分——筆數不能變")
        void editReplacesRatherThanRemoveAndAdd() {
            Review existing = Review.create(PRODUCT, SKU, ORDER_NO, USER, "王＊＊",
                    Rating.of(5), "當時覺得很好", NOW);
            when(reviewRepository.findById(7L)).thenReturn(Optional.of(existing));

            service().edit(new EditReviewCommand(USER, 7L, 2, "用久了才發現問題"));

            // 「先移除再新增」會讓中間有一瞬間筆數少一，
            // 那一瞬間剛好有人讀到就會看到錯的平均分
            verify(reviewRepository).replaceRating(PRODUCT, Rating.of(5), Rating.of(2));
            verify(reviewRepository, never()).addRating(any(), any());
        }

        @Test
        @DisplayName("舊評分取自資料庫，不是請求——否則呼叫端能把平均分改成任何值")
        void oldRatingComesFromTheDatabase() {
            Review existing = Review.create(PRODUCT, SKU, ORDER_NO, USER, "王＊＊",
                    Rating.of(1), "本來很差", NOW);
            when(reviewRepository.findById(7L)).thenReturn(Optional.of(existing));

            // 請求裡只有新評分，沒有舊評分的欄位可以填
            service().edit(new EditReviewCommand(USER, 7L, 5, "後來變好了"));

            verify(reviewRepository).replaceRating(eq(PRODUCT), eq(Rating.of(1)), eq(Rating.of(5)));
        }

        @Test
        @DisplayName("別人的評價改不動")
        void cannotEditOthersReview() {
            Review existing = Review.create(PRODUCT, SKU, ORDER_NO, USER + 1, "李＊＊",
                    Rating.of(5), "別人寫的", NOW);
            when(reviewRepository.findById(7L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service().edit(new EditReviewCommand(USER, 7L, 1, "改掉")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                            .isEqualTo(ErrorCode.REVIEW_NOT_FOUND));

            verify(reviewRepository, never()).replaceRating(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("作者名稱")
    class AuthorName {

        @Test
        @DisplayName("遮蔽在寫入時就做完——完整姓名根本不進評價表")
        void nameIsMaskedBeforePersisting() {
            givenOrder(OrderStatus.COMPLETED);
            givenProduct();
            givenUser("王小明");
            givenSaveEcho();

            var view = service().write(command());

            assertThat(view.authorName()).isEqualTo("王＊＊");
            assertThat(view.authorName()).doesNotContain("小明");
        }
    }

    @Nested
    @DisplayName("可評價項目")
    class Reviewable {

        @Test
        @DisplayName("已評價過的項目標成不可選，而不是從清單消失")
        void reviewedLinesAreMarkedNotHidden() {
            givenOrder(OrderStatus.COMPLETED);
            when(reviewRepository.findReviewedSkuIds(ORDER_NO)).thenReturn(List.of(SKU));

            var view = service().reviewable(ORDER_NO, USER);

            // 消失的話使用者會以為系統把他的訂單弄丟了一項
            assertThat(view.lines()).hasSize(1);
            assertThat(view.lines().get(0).pending()).isFalse();
            assertThat(view.reviewable()).isFalse();
            assertThat(view.reason()).contains("已經評價過");
        }

        @Test
        @DisplayName("訂單還沒送達時附上原因，而不是回一張空表單")
        void notYetDeliveredExplainsWhy() {
            givenOrder(OrderStatus.SHIPPED);

            var view = service().reviewable(ORDER_NO, USER);

            assertThat(view.reviewable()).isFalse();
            assertThat(view.reason()).contains("送達");
        }
    }

    @Nested
    @DisplayName("批次評分")
    class BatchRatings {

        @Test
        @DisplayName("沒有評價的商品補上空聚合——呼叫端不必為「查不到」寫第二條路徑")
        void missingProductsGetAnEmptyAggregate() {
            when(reviewRepository.findRatings(any())).thenReturn(Map.of());

            var ratings = service().ratingsOf(List.of(1L, 2L, 3L));

            assertThat(ratings).hasSize(3);
            assertThat(ratings.get(2L).count()).isZero();
            assertThat(ratings.get(2L).average()).isEqualByComparingTo("0.0");
        }
    }

    // ---- fixtures ----

    private static WriteReviewCommand command() {
        return new WriteReviewCommand(USER, ORDER_NO, SKU, 5, "用起來很好");
    }

    private void givenOrder(OrderStatus status) {
        givenOrder(status, USER);
    }

    private void givenOrder(OrderStatus status, long ownerId) {
        Order order = Order.restore(OrderNo.of(ORDER_NO), ownerId, OrderChannel.NORMAL, "req-1",
                List.of(new OrderLine(SKU, "iPhone 16 Pro（256G）", new BigDecimal("29900"), 1, null)),
                new BigDecimal("29900"),
                new ShippingInfo("王小明", "0912345678", "110", "臺北市", "信義區", "市府路 1 號"),
                status, NOW.minusSeconds(864000), NOW.minusSeconds(863000), null, 0L);
        when(orderRepository.findByOrderNo(OrderNo.of(ORDER_NO))).thenReturn(Optional.of(order));
    }

    private void givenProduct() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("容量", "256G");
        when(productRepository.findBySkuId(SKU)).thenReturn(Optional.of(
                Product.restore(PRODUCT, 2L, "iPhone 16 Pro", "Apple", "旗艦機種",
                        ProductStatus.ON_SHELF,
                        List.of(Sku.restore(SKU, PRODUCT, SkuSpec.of(attributes),
                                new BigDecimal("29900"), "IP16P-256", ProductStatus.ON_SHELF)),
                        NOW)));
    }

    private void givenUser() {
        givenUser("王小明");
    }

    private void givenUser(String displayName) {
        when(userRepository.findById(USER)).thenReturn(Optional.of(User.restore(
                USER, Email.of("a@b.com"), new PasswordHash("$2a$10$abcdefghijklmnopqrstuv"),
                displayName, UserRole.CUSTOMER, UserStatus.ACTIVE, NOW, 0L)));
    }

    private void givenSaveEcho() {
        when(reviewRepository.saveIfAbsent(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
    }
}
