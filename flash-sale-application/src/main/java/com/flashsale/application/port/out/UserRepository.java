package com.flashsale.application.port.out;

import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.User;

import java.util.Optional;

/** 使用者持久化埠（出站）。 */
public interface UserRepository {

    /**
     * 建立帳號；信箱已存在時回傳 {@code Optional.empty()}。
     *
     * <p>與 {@code OrderRepository.saveIfAbsent} 同樣的理由：
     * 「先查再寫」在並發下有競態窗口，真正保證唯一的是資料庫的唯一索引。
     * 實作端必須把唯一鍵衝突翻譯成 {@code Optional.empty()}，
     * <b>不可讓框架例外洩漏到應用層</b>。
     */
    Optional<User> createIfAbsent(User user);

    User update(User user);

    Optional<User> findByEmail(Email email);

    Optional<User> findById(Long userId);
}
