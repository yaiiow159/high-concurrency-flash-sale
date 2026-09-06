package com.flashsale.application.service;

import com.flashsale.application.port.in.AddressUseCase;
import com.flashsale.application.port.in.dto.AddressView;
import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.domain.identity.Address;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** 收貨地址簿。 */
@Service
public class AddressManagementService implements AddressUseCase {

    private static final Logger log = LoggerFactory.getLogger(AddressManagementService.class);

    /** 每人地址上限。 */
    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addressRepository;
    private final Clock clock;

    public AddressManagementService(AddressRepository addressRepository, Clock clock) {
        this.addressRepository = addressRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressView> list(Long userId) {
        return addressRepository.findByUserId(userId).stream().map(AddressView::from).toList();
    }

    @Override
    @Transactional
    public AddressView add(Long userId, AddressCommand command) {
        if (addressRepository.countByUserId(userId) >= MAX_ADDRESSES_PER_USER) {
            throw new BusinessException(ErrorCode.ADDRESS_LIMIT_EXCEEDED,
                    "每個帳號最多 %d 筆收貨地址".formatted(MAX_ADDRESSES_PER_USER));
        }

        // 第一筆地址自動成為預設：讓使用者在結帳時有東西可選，
        // 而不是面對一個空的下拉選單卻不知道要先去哪裡設定
        boolean isFirst = addressRepository.countByUserId(userId) == 0;

        Address address = addressRepository.save(Address.create(userId,
                command.recipientName(), command.phone(), command.postalCode(),
                command.region(), command.district(), command.streetAddress(),
                command.defaultAddress() || isFirst, clock.instant()));

        if (address.isDefaultAddress()) {
            addressRepository.clearDefaultExcept(userId, address.id());
        }
        log.info("新增收貨地址 userId={}, addressId={}", userId, address.id());
        return AddressView.from(address);
    }

    @Override
    @Transactional
    public AddressView update(Long userId, Long addressId, AddressCommand command) {
        Address address = requireOwned(userId, addressId);
        address.update(command.recipientName(), command.phone(), command.postalCode(),
                command.region(), command.district(), command.streetAddress());

        if (command.defaultAddress()) {
            address.markAsDefault();
        }
        Address saved = addressRepository.save(address);
        if (saved.isDefaultAddress()) {
            addressRepository.clearDefaultExcept(userId, saved.id());
        }
        return AddressView.from(saved);
    }

    /** 刪除地址。 */
    @Override
    @Transactional
    public void delete(Long userId, Long addressId) {
        Address address = requireOwned(userId, addressId);
        addressRepository.deleteById(address.id());

        if (address.isDefaultAddress()) {
            // 刪掉的是預設地址，把剩下的第一筆補上，
            // 否則使用者下次結帳會發現沒有預設可選卻不知道為什麼
            addressRepository.findByUserId(userId).stream().findFirst().ifPresent(next -> {
                next.markAsDefault();
                addressRepository.save(next);
            });
        }
        log.info("刪除收貨地址 userId={}, addressId={}", userId, addressId);
    }

    @Override
    @Transactional
    public AddressView setDefault(Long userId, Long addressId) {
        Address address = requireOwned(userId, addressId);
        address.markAsDefault();
        Address saved = addressRepository.save(address);
        addressRepository.clearDefaultExcept(userId, saved.id());
        return AddressView.from(saved);
    }

    private Address requireOwned(Long userId, Long addressId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
        address.requireOwnedBy(userId);
        return address;
    }
}
