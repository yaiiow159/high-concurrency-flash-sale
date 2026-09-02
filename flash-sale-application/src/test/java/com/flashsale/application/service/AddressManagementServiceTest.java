package com.flashsale.application.service;

import com.flashsale.application.port.in.AddressUseCase.AddressCommand;
import com.flashsale.application.port.in.dto.AddressView;
import com.flashsale.application.port.out.AddressRepository;
import com.flashsale.domain.identity.Address;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地址簿。
 *
 * <p>兩件事必須守住：<b>擁有者檢查</b>（少了它任何人都能讀改別人的住家地址），
 * 以及<b>每人最多一筆預設地址</b>（資料庫表達不了，只能靠這一層）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("收貨地址簿")
class AddressManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER = 88L;
    private static final long OTHER_USER = 999L;
    private static final long ADDRESS_ID = 7L;

    @Mock
    private AddressRepository addressRepository;

    @Nested
    @DisplayName("擁有者檢查")
    class Ownership {

        @Test
        @DisplayName("讀別人的地址：當成不存在，且不洩漏任何內容")
        void cannotReadForeignAddress() {
            givenStored(ownedBy(OTHER_USER));

            assertThatThrownBy(() -> service().setDefault(USER, ADDRESS_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
        }

        @Test
        @DisplayName("改別人的地址：拒絕，且什麼都不寫")
        void cannotUpdateForeignAddress() {
            givenStored(ownedBy(OTHER_USER));

            assertThatThrownBy(() -> service().update(USER, ADDRESS_ID, command(false)))
                    .isInstanceOf(BusinessException.class);

            verify(addressRepository, never()).save(any());
        }

        @Test
        @DisplayName("刪別人的地址：拒絕，且什麼都不刪")
        void cannotDeleteForeignAddress() {
            givenStored(ownedBy(OTHER_USER));

            assertThatThrownBy(() -> service().delete(USER, ADDRESS_ID))
                    .isInstanceOf(BusinessException.class);

            verify(addressRepository, never()).deleteById(anyLong());
        }
    }

    @Nested
    @DisplayName("預設地址")
    class DefaultAddress {

        @Test
        @DisplayName("第一筆自動成為預設——否則使用者結帳時面對空選單卻不知道要先去哪設定")
        void firstAddressBecomesDefault() {
            when(addressRepository.countByUserId(USER)).thenReturn(0);
            when(addressRepository.save(any())).thenAnswer(this::persist);

            AddressView view = service().add(USER, command(false));

            assertThat(view.defaultAddress()).isTrue();
        }

        @Test
        @DisplayName("設為預設時清掉其他筆——資料庫表達不了這條，只能靠這一層")
        void settingDefaultClearsOthers() {
            givenStored(ownedBy(USER));
            when(addressRepository.save(any())).thenAnswer(this::persist);

            service().setDefault(USER, ADDRESS_ID);

            verify(addressRepository).clearDefaultExcept(USER, ADDRESS_ID);
        }

        @Test
        @DisplayName("新增非預設地址時不動其他筆")
        void addingNonDefaultLeavesOthersAlone() {
            when(addressRepository.countByUserId(USER)).thenReturn(3);
            when(addressRepository.save(any())).thenAnswer(this::persist);

            service().add(USER, command(false));

            verify(addressRepository, never()).clearDefaultExcept(anyLong(), anyLong());
        }

        @Test
        @DisplayName("刪掉預設地址後補上另一筆——否則下次結帳沒有預設可選卻不知為何")
        void deletingDefaultPromotesAnother() {
            Address stored = ownedBy(USER);
            stored.markAsDefault();
            givenStored(stored);
            Address remaining = Address.restore(8L, USER, "李小華", "0987654321", "220",
                    "新北市", "板橋區", "文化路 99 號", false, NOW);
            when(addressRepository.findByUserId(USER)).thenReturn(List.of(remaining));

            service().delete(USER, ADDRESS_ID);

            ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
            verify(addressRepository).save(captor.capture());
            assertThat(captor.getValue().isDefaultAddress()).isTrue();
        }

        @Test
        @DisplayName("刪掉非預設地址不觸發遞補")
        void deletingNonDefaultPromotesNothing() {
            givenStored(ownedBy(USER));

            service().delete(USER, ADDRESS_ID);

            verify(addressRepository, never()).save(any());
        }

        private Address persist(org.mockito.invocation.InvocationOnMock invocation) {
            Address input = invocation.getArgument(0);
            return Address.restore(ADDRESS_ID, input.userId(), input.recipientName(),
                    input.phone(), input.postalCode(), input.region(), input.district(),
                    input.streetAddress(), input.isDefaultAddress(), NOW);
        }
    }

    @Nested
    @DisplayName("數量上限")
    class Limits {

        @Test
        @DisplayName("超過上限就拒絕——地址簿是登入後可無限次呼叫的端點")
        void rejectsBeyondLimit() {
            when(addressRepository.countByUserId(USER)).thenReturn(20);

            assertThatThrownBy(() -> service().add(USER, command(false)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ADDRESS_LIMIT_EXCEEDED);

            verify(addressRepository, never()).save(any());
        }
    }

    // ---- fixtures ----

    private AddressManagementService service() {
        return new AddressManagementService(addressRepository, CLOCK);
    }

    private void givenStored(Address address) {
        when(addressRepository.findById(ADDRESS_ID)).thenReturn(Optional.of(address));
    }

    private static Address ownedBy(Long userId) {
        return Address.restore(ADDRESS_ID, userId, "王小明", "0912345678", "110",
                "臺北市", "信義區", "市府路 1 號", false, NOW);
    }

    private static AddressCommand command(boolean asDefault) {
        return new AddressCommand("王小明", "0912345678", "110",
                "臺北市", "信義區", "市府路 1 號", asDefault);
    }
}
