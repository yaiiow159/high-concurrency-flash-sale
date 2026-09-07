package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.UserBlacklistEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserBlacklistJpaRepository extends JpaRepository<UserBlacklistEntity, Long> {

    @Query("select b from UserBlacklistEntity b order by b.createdAt desc")
    List<UserBlacklistEntity> findAllNewestFirst(Pageable pageable);
}
