package com.flashsale.application.port.out;

import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;

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

    /**
     * 系統中是否已經有這個角色的帳號。
     *
     * <p>只有初始管理員的建立會用到：它必須是「第一個」才成立，
     * 否則設定檔就變成一條永久有效的提權後門。
     */
    boolean existsByRole(UserRole role);
}
