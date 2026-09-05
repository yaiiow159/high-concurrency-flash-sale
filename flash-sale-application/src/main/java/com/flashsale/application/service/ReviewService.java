package com.flashsale.application.service;

import com.flashsale.application.port.in.ReviewUseCase;
import com.flashsale.application.port.in.dto.ProductRatingView;
import com.flashsale.application.port.in.dto.ReviewView;
import com.flashsale.application.port.in.dto.ReviewableView;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ReviewRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.review.DisplayNameMask;
import com.flashsale.domain.review.ProductRating;
import com.flashsale.domain.review.Rating;
import com.flashsale.domain.review.Review;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.Page;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品評價。
 *
 * <h2>可信度是這個功能的全部價值</h2>
 *
 * <p>評價一旦可以任意張貼，它就一文不值——而那不是慢慢發生的，
 * 是從第一則刷出來的評價開始的。因此三道條件缺一不可（ADR-0014 決策 1）：
 *
 * <ol>
 *   <li>訂單屬於這個使用者</li>
 *   <li>訂單已 {@code COMPLETED}——付了錢但還沒收到貨的人，對商品還沒有意見</li>
 *   <li>這筆訂單行還沒被評價過</li>
 * </ol>
 *
 * <p><b>第三道的最後防線是資料庫的唯一索引，不是這裡的查詢。</b>
 * 兩個並行請求會同時通過任何 Java 端的檢查。
 */
@Service
public class ReviewService implements ReviewUseCase {

    /** 列表頁大小上限。對外開放的端點沒有上限，等於讓人用一個參數掃全表。 */
    private static final int MAX_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public ReviewService(ReviewRepository reviewRepository,
                         OrderRepository orderRepository,
                         ProductRepository productRepository,
                         UserRepository userRepository,
                         Clock clock) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReviewView write(WriteReviewCommand command) {
        Instant now = clock.instant();
        Order order = requireReviewableOrder(command.orderNo(), command.userId());
        OrderLine line = requireLine(order, command.skuId());

        Product product = productRepository.findBySkuId(command.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND,
                        "找不到規格 %d".formatted(command.skuId())));

        // 遮蔽在寫入時就做完，完整姓名根本不進評價表——
        // 在畫面上遮等於它仍然出現在 API 回應裡
        String authorName = DisplayNameMask.apply(userRepository.findById(command.userId())
                .map(user -> user.displayName())
                .orElse(null));

        Review review = Review.create(product.id(), command.skuId(), order.orderNo().value(),
                command.userId(), authorName, Rating.of(command.stars()), command.content(), now);

        // 唯一索引擋下重複時回空。先查再寫在兩個並行請求下兩邊都會通過
        Review saved = reviewRepository.saveIfAbsent(review)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALREADY_REVIEWED,
                        "「%s」在這張訂單上已經評價過了".formatted(line.skuSnapshot())));

        // 聚合走增量 UPDATE，不是讀出來加完寫回去——後者在兩個人同時評價時會吃掉一則
        reviewRepository.addRating(product.id(), saved.rating());

        log.info("評價建立 productId={}, orderNo={}, skuId={}, 星等={}",
                product.id(), command.orderNo(), command.skuId(), command.stars());
        return ReviewView.from(saved, now);
    }

    /**
     * {@inheritDoc}
     *
     * <p>舊評分從<b>資料庫當場讀出來的那一則</b>取，不是從請求帶進來——
     * 呼叫端若能宣告舊評分是多少，它就能把商品的平均分改成任何值。
     */
    @Override
    @Transactional
    public ReviewView edit(EditReviewCommand command) {
        Instant now = clock.instant();
        Review existing = reviewRepository.findById(command.reviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND, "評價不存在"));
        existing.requireOwnedBy(command.userId());

        Rating newRating = Rating.of(command.stars());
        Review updated = existing.edit(newRating, command.content(), now);
        reviewRepository.update(updated);

        // 筆數不變，只動總和與兩個分佈桶
        reviewRepository.replaceRating(existing.productId(), existing.rating(), newRating);

        log.info("評價更新 reviewId={}, {} → {} 星",
                command.reviewId(), existing.rating().stars(), command.stars());
        return ReviewView.from(updated, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> byProduct(Long productId, int page, int size) {
        Instant now = clock.instant();
        // 在**服務層**夾，與其他所有列表一致。
        // 先前只有 Controller 夾，於是任何非 Controller 的呼叫端
        // 都會把未夾的 size 送進倉庫——接上倉庫的 offset/limit 換算就是除以零
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        return reviewRepository.findByProductId(productId, paging.offset(), paging.size()).stream()
                .map(review -> ReviewView.from(review, now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> mine(Long userId, int page, int size) {
        Instant now = clock.instant();
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        return reviewRepository.findByUserId(userId, paging.offset(), paging.size()).stream()
                .map(review -> ReviewView.from(review, now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductRatingView ratingOf(Long productId) {
        return ProductRatingView.from(reviewRepository.findRating(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductRatingView> ratingsOf(List<Long> productIds) {
        Map<Long, ProductRating> found = reviewRepository.findRatings(productIds);
        // 沒有評價的商品補上空聚合——讓呼叫端不必為「查不到」寫第二條路徑
        return productIds.stream().distinct().collect(java.util.stream.Collectors.toMap(
                productId -> productId,
                productId -> ProductRatingView.from(
                        found.getOrDefault(productId, ProductRating.empty(productId)))));
    }

    /**
     * {@inheritDoc}
     *
     * <p>把「哪些項目還能評」算好給前端，而不是讓前端自己比對。
     * 前端再實作一次的話，症狀會是「畫面說可以評，送出卻被拒絕」——
     * 與可退數量交給後端算是同一個理由。
     */
    @Override
    @Transactional(readOnly = true)
    public ReviewableView reviewable(String orderNo, Long userId) {
        Order order = requireOwnedOrder(orderNo, userId);
        if (order.status() != OrderStatus.COMPLETED) {
            return ReviewableView.notYet(orderNo,
                    "訂單送達後才能評價（目前狀態：%s）".formatted(order.status().name()));
        }

        Set<Long> reviewed = Set.copyOf(reviewRepository.findReviewedSkuIds(orderNo));
        List<ReviewableView.Line> lines = order.lines().stream()
                .map(line -> new ReviewableView.Line(line.skuId(), line.skuSnapshot(),
                        !reviewed.contains(line.skuId())))
                .toList();
        return ReviewableView.of(orderNo, lines);
    }

    private Order requireReviewableOrder(String orderNo, Long userId) {
        Order order = requireOwnedOrder(orderNo, userId);
        if (order.status() != OrderStatus.COMPLETED) {
            // 付了錢但還沒收到貨的人，對商品本身還沒有意見
            throw new BusinessException(ErrorCode.ORDER_NOT_REVIEWABLE,
                    "訂單送達後才能評價（目前狀態：%s）".formatted(order.status().name()));
        }
        return order;
    }

    private Order requireOwnedOrder(String orderNo, Long userId) {
        Order order = orderRepository.findByOrderNo(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "訂單不存在: " + orderNo));
        if (!order.userId().equals(userId)) {
            // 回「不存在」而非「不是你的」：後者等於確認這個單號有效
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "訂單不存在: " + orderNo);
        }
        return order;
    }

    private static OrderLine requireLine(Order order, Long skuId) {
        return order.lines().stream()
                .filter(line -> line.skuId().equals(skuId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "訂單 %s 沒有 SKU %d".formatted(order.orderNo().value(), skuId)));
    }
}
