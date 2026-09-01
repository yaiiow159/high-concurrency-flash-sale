package com.flashsale.application.service;

import com.flashsale.application.port.in.UserRegistrationUseCase;
import com.flashsale.application.port.in.command.RegisterUserCommand;
import com.flashsale.application.port.in.dto.UserView;
import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 註冊服務。 */
@Service
public class UserRegistrationService implements UserRegistrationUseCase {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public UserRegistrationService(UserRepository userRepository, PasswordHasher passwordHasher, Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UserView register(RegisterUserCommand command) {
        Email email = Email.of(command.email());
        User user = User.register(
                email,
                passwordHasher.hash(command.rawPassword()),
                command.displayName(),
                clock.instant());

        // 不先查再寫：唯一索引才是真正的保證，先查只是減少衝突頻率。
        return userRepository.createIfAbsent(user)
                .map(created -> {
                    log.info("新帳號註冊完成 userId={}, email={}", created.id(), email.masked());
                    return UserView.from(created);
                })
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }
}
