package com.flashsale.application.service;

import com.flashsale.application.port.in.RiskAdminUseCase;
import com.flashsale.application.port.in.dto.BlacklistView;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.out.BlacklistRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.risk.BlacklistEntry;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.shared.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class RiskAdminService implements RiskAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(RiskAdminService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final BlacklistRepository blacklistRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public RiskAdminService(BlacklistRepository blacklistRepository, UserRepository userRepository, Clock clock) {
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<BlacklistView> list(int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        List<BlacklistEntry> entries = blacklistRepository.findAll(paging.size(), paging.offset());
        Map<Long, String> names = userRepository.findDisplayNames(entries.stream().map(BlacklistEntry::userId).toList());
        List<BlacklistView> items = entries.stream()
                .map(entry -> BlacklistView.from(entry,
                        userRepository.findById(entry.userId()).map(user -> user.email().value()).orElse(null),
                        names.get(entry.userId())))
                .toList();
        return PageView.of(items, blacklistRepository.count(), paging.number(), paging.size());
    }

    @Override
    @Transactional
    public BlacklistView add(Long targetUserId, String reason, Instant expiresAt, Long operatorUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        BlacklistEntry saved = blacklistRepository.save(
                new BlacklistEntry(targetUserId, reason, operatorUserId, clock.instant(), expiresAt));
        log.info("使用者 {} 列入黑名單（操作者 {}）：{}", targetUserId, operatorUserId, saved.reason());
        return BlacklistView.from(saved, user.email().value(), user.displayName());
    }

    @Override
    @Transactional
    public void remove(Long targetUserId) {
        blacklistRepository.delete(targetUserId);
        log.info("使用者 {} 移出黑名單", targetUserId);
    }
}
