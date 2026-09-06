package com.flashsale.application.service;

import com.flashsale.application.port.in.AdminBootstrapUseCase;
import com.flashsale.application.port.in.command.RegisterUserCommand;
import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 初始管理員。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>{@code User.register} 一律建立 {@code CUSTOMER}，而系統裡沒有任何端點能提升角色——
 * 這是對的（提權端點本身就要 admin 權限，是個先有雞還是先有蛋的問題），
 * 但代價是<b>第一個管理員只能靠人直接改資料庫</b>。
 *
 * <h2>三條守衛</h2>
 *
 * <ol>
 *   <li><b>已有管理員就完全不動作</b>——否則設定檔會變成永久有效的提權後門</li>
 *   <li><b>密碼套用與一般註冊相同的政策</b>，重用 {@link RegisterUserCommand} 的驗證，
 *       而不是自己再寫一份。政策只該有一處定義</li>
 *   <li><b>沒有預設密碼</b>——呼叫端必須明確提供。寫死一組預設管理員密碼
 *       是最典型的那種漏洞，而且它會安靜地存在很久</li>
 * </ol>
 */
@Service
public class AdminBootstrapService implements AdminBootstrapUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public AdminBootstrapService(UserRepository userRepository, PasswordHasher passwordHasher,
                                 Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Outcome bootstrap(String email, String rawPassword, String displayName) {
        // 走 RegisterUserCommand 是為了套用同一份密碼政策。
        // 自己複製一份長度檢查的話，哪天政策改了這裡會安靜地留在舊規則上
        RegisterUserCommand validated = new RegisterUserCommand(email, rawPassword, displayName);
        Email normalized = Email.of(validated.email());

        if (userRepository.existsByRole(UserRole.ADMIN)) {
            log.info("系統中已有管理員，略過初始管理員設定");
            return Outcome.SKIPPED;
        }

        return userRepository.findByEmail(normalized)
                .map(this::promote)
                .orElseGet(() -> create(validated, normalized));
    }

    /**
     * 提升既有帳號。
     *
     * <p><b>不改密碼。</b> 這條路徑的用途是「我已經註冊過了，把我變成管理員」，
     * 而順手重設密碼會讓設定檔多一個能力：覆寫任意既有帳號的密碼。
     * 那與「建立第一個管理員」是兩件事。
     */
    private Outcome promote(User existing) {
        existing.promoteTo(UserRole.ADMIN);
        userRepository.update(existing);
        log.warn("已將既有帳號提升為管理員 userId={}, email={}（密碼未變更）",
                existing.id(), existing.email().masked());
        return Outcome.PROMOTED;
    }

    private Outcome create(RegisterUserCommand command, Email normalized) {
        // 先以一般身分建立再提升，而不是另外開一個 registerAdmin 工廠方法：
        // 這樣註冊路徑上未來新增的任何不變量，這裡都會自動套用
        User created = userRepository.createIfAbsent(User.register(
                        normalized,
                        passwordHasher.hash(command.rawPassword()),
                        command.displayName(),
                        clock.instant()))
                // 走到這裡代表兩個節點同時開機、都通過了「還沒有管理員」的檢查，
                // 而唯一索引擋下了第二個。此時另一邊已經在建了，這一輪讓給它
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                        "初始管理員帳號正由另一個節點建立中"));

        created.promoteTo(UserRole.ADMIN);
        userRepository.update(created);
        log.warn("已建立初始管理員 userId={}, email={}。**請儘快改掉這組密碼並移除設定**",
                created.id(), created.email().masked());
        return Outcome.CREATED;
    }
}
