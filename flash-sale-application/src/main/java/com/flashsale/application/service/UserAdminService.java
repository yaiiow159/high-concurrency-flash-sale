package com.flashsale.application.service;

import com.flashsale.application.port.in.UserAdminUseCase;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.UserView;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.shared.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class UserAdminService implements UserAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RefreshTokenRevoker tokenRevoker;
    private final Clock clock;

    public UserAdminService(UserRepository userRepository, RefreshTokenRevoker tokenRevoker, Clock clock) {
        this.userRepository = userRepository;
        this.tokenRevoker = tokenRevoker;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<UserView> search(String keyword, String status, int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        UserStatus statusFilter = parseStatus(status);
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        var items = userRepository.search(trimmed, statusFilter, paging.size(), paging.offset()).stream()
                .map(UserView::from)
                .toList();
        return PageView.of(items, userRepository.countSearch(trimmed, statusFilter),
                paging.number(), paging.size());
    }

    @Override
    @Transactional(readOnly = true)
    public UserView find(Long userId) {
        return UserView.from(load(userId));
    }

    @Override
    @Transactional
    public UserView suspend(Long targetUserId, Long operatorUserId) {
        if (targetUserId.equals(operatorUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不可停權自己");
        }
        User user = load(targetUserId);
        user.suspend();
        userRepository.update(user);
        // 撤銷所有 refresh token：access token 到期後就再也換不到新的，停權才會真的生效。
        // 走 REQUIRES_NEW 的 Revoker——與外層交易脫鉤，外層回滾也不會把撤銷一起還原
        int revoked = tokenRevoker.revokeAllForUser(targetUserId, clock.instant());
        log.info("停權使用者 {}（操作者 {}），撤銷 {} 個 refresh token", targetUserId, operatorUserId, revoked);
        return UserView.from(user);
    }

    @Override
    @Transactional
    public UserView reactivate(Long targetUserId) {
        User user = load(targetUserId);
        user.reactivate();
        userRepository.update(user);
        log.info("恢復使用者 {}", targetUserId);
        return UserView.from(user);
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private static UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "參數「status」的值不正確");
        }
    }
}
