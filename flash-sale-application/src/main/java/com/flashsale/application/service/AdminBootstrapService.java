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

/** 初始管理員。 */
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

    /** 提升既有帳號。 */
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
