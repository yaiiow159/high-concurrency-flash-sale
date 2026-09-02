package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.domain.identity.Address;
import com.flashsale.infrastructure.adapter.out.persistence.entity.AddressEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.AddressJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 收貨地址持久化埠的 JPA 實作。 */
@Repository
public class JpaAddressRepository implements AddressRepository {

    private final AddressJpaRepository jpaRepository;

    public JpaAddressRepository(AddressJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Address save(Address address) {
        AddressEntity entity = address.id() == null
                ? new AddressEntity(address.userId(), address.recipientName(), address.phone(),
                        address.postalCode(), address.region(), address.district(),
                        address.streetAddress(), address.isDefaultAddress(), address.createdAt())
                : updateExisting(address);
        return toDomain(jpaRepository.save(entity));
    }

    private AddressEntity updateExisting(Address address) {
        AddressEntity entity = jpaRepository.findById(address.id())
                .orElseThrow(() -> new IllegalStateException("更新時找不到地址 " + address.id()));
        entity.applyChanges(address.recipientName(), address.phone(), address.postalCode(),
                address.region(), address.district(), address.streetAddress(),
                address.isDefaultAddress());
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Address> findById(Long addressId) {
        return jpaRepository.findById(addressId).map(JpaAddressRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Address> findByUserId(Long userId) {
        return jpaRepository.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
                .map(JpaAddressRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int countByUserId(Long userId) {
        return jpaRepository.countByUserId(userId);
    }

    @Override
    @Transactional
    public void deleteById(Long addressId) {
        jpaRepository.deleteById(addressId);
    }

    @Override
    @Transactional
    public int clearDefaultExcept(Long userId, Long exceptAddressId) {
        return jpaRepository.clearDefaultExcept(userId, exceptAddressId);
    }

    private static Address toDomain(AddressEntity entity) {
        return Address.restore(entity.getId(), entity.getUserId(), entity.getRecipientName(),
                entity.getPhone(), entity.getPostalCode(), entity.getRegion(),
                entity.getDistrict(), entity.getStreetAddress(),
                entity.isDefaultAddress(), entity.getCreatedAt());
    }
}
