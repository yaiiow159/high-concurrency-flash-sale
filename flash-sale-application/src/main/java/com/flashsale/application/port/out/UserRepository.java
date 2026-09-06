package com.flashsale.application.port.out;

import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;

import java.util.Optional;

/** 使用者持久化埠（出站）。 */
public interface UserRepository {

    /** 建立帳號；信箱已存在時回傳 {@code Optional.empty()}。 */
    Optional<User> createIfAbsent(User user);

    User update(User user);

    Optional<User> findByEmail(Email email);

    Optional<User> findById(Long userId);

    /**
     * 批次取顯示名稱。
     *
     * <p>逐筆 {@code findById} 在列表上就是 N+1——同一個作者會被 JPA 的
     * 一級快取蓋掉，所以那個問題只在作者不同時才現形，也就是真實情況。
     */
    java.util.Map<Long, String> findDisplayNames(java.util.Collection<Long> userIds);

    /** 系統中是否已經有這個角色的帳號。 */
    boolean existsByRole(UserRole role);
}
