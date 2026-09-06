package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.CartRepository;
import com.flashsale.domain.cart.Cart;
import com.flashsale.domain.cart.CartItem;
import com.flashsale.infrastructure.adapter.out.persistence.entity.CartItemEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.CartItemJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 購物車持久化埠的 JPA 實作。 */
@Repository
public class JpaCartRepository implements CartRepository {

    private final CartItemJpaRepository jpaRepository;

    public JpaCartRepository(CartItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Cart findByUserId(Long userId) {
        List<CartItem> items = jpaRepository.findByUserIdOrderByIdAsc(userId).stream()
                .map(entity -> new CartItem(entity.getSkuId(), entity.getQuantity(),
                        entity.getUpdatedAt()))
                .toList();
        // 查無資料回空車而非 empty()：沒加過東西的人也有購物車，只是空的
        return Cart.restore(userId, items);
    }

    /** 全量覆寫。 */
    @Override
    @Transactional
    public void save(Cart cart) {
        jpaRepository.deleteByUserId(cart.userId());
        // flush 讓刪除先於插入送到資料庫，否則同一個交易內的
        // (user_id, sku_id) 唯一索引會在插入時撞上還沒被刪掉的舊列
        jpaRepository.flush();

        if (cart.isEmpty()) {
            return;
        }
        jpaRepository.saveAll(cart.items().stream()
                .map(item -> new CartItemEntity(cart.userId(), item.skuId(),
                        item.quantity(), item.updatedAt()))
                .toList());
    }

    @Override
    @Transactional
    public void clear(Long userId) {
        jpaRepository.deleteByUserId(userId);
    }

    @Override
    @Transactional
    public int deleteStale(Instant updatedBefore, int limit) {
        List<Long> ids = jpaRepository.findStaleIds(updatedBefore, PageRequest.of(0, limit));
        return ids.isEmpty() ? 0 : jpaRepository.deleteByIds(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastUpdatedAt(Long userId) {
        return Optional.ofNullable(jpaRepository.findLastUpdatedAt(userId));
    }
}
