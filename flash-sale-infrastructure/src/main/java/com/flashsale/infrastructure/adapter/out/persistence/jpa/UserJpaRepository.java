package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 使用者的 Spring Data 介面。 */
public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 批次取顯示名稱，避免列表上的 N+1。 */
    @org.springframework.data.jpa.repository.Query(
            "select u.id, u.displayName from UserEntity u where u.id in :ids")
    java.util.List<Object[]> findDisplayNamesByIds(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** 初始管理員的守衛條件：系統中是否已經有這個角色的帳號。 */
    boolean existsByRole(String role);

    @org.springframework.data.jpa.repository.Query("""
            select u from UserEntity u
             where (:status is null or u.status = :status)
               and (:keyword is null or u.email like :keyword or u.displayName like :keyword)
             order by u.createdAt desc
            """)
    java.util.List<UserEntity> search(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("status") String status,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            select count(u) from UserEntity u
             where (:status is null or u.status = :status)
               and (:keyword is null or u.email like :keyword or u.displayName like :keyword)
            """)
    long countSearch(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("status") String status);
}
