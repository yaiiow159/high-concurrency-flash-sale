package com.flashsale.application.service;

import com.flashsale.application.port.in.UserQueryUseCase;
import com.flashsale.application.port.in.dto.UserView;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用者查詢服務。 */
@Service
public class UserQueryService implements UserQueryUseCase {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserView findById(Long userId) {
        return userRepository.findById(userId)
                .map(UserView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
