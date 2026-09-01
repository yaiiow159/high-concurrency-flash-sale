package com.flashsale.domain.inventory;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SKU 庫存")
class InventoryTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final long SKU = 2001L;

    @Nested
    @DisplayName("一般銷售")
    class NormalSales {

        @Test
        @DisplayName("扣減只動可售量")
        void deductTouchesAvailableOnly() {
            Inventory inventory = Inventory.restore(SKU, 100, 30, 0L);

            inventory.deduct(10);

            assertThat(inventory.available()).isEqualTo(90);
            assertThat(inventory.allocated()).isEqualTo(30);
        }

        @Test
        @DisplayName("可售量不足時拒絕——不可動用劃撥出去的量")
        void cannotDipIntoAllocated() {
            // 帳面總共 130 件，但只有 100 件是這條通道能賣的
            Inventory inventory = Inventory.restore(SKU, 100, 30, 0L);

            assertThatThrownBy(() -> inventory.deduct(101))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SOLD_OUT);

            assertThat(inventory.available()).isEqualTo(100);
        }

        @Test
        @DisplayName("剛好扣完可以，不會多扣一件")
        void canDeductExactlyAllAvailable() {
            Inventory inventory = Inventory.restore(SKU, 100, 30, 0L);

            inventory.deduct(100);

            assertThat(inventory.available()).isZero();
            assertThat(inventory.canFulfil(1)).isFalse();
        }

        @Test
        @DisplayName("非正數的扣減一律拒絕")
        void rejectsNonPositiveQuantity() {
            Inventory inventory = Inventory.create(SKU, 100);

            assertThatThrownBy(() -> inventory.deduct(0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> inventory.deduct(-5)).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("劃撥與釋放")
    class Allocation {

        @Test
        @DisplayName("劃撥搬動數字但不改變總量")
        void allocationConservesTotal() {
            Inventory inventory = Inventory.create(SKU, 100);
            int before = inventory.totalOnHand();

            inventory.allocate(30);

            assertThat(inventory.available()).isEqualTo(70);
            assertThat(inventory.allocated()).isEqualTo(30);
            assertThat(inventory.totalOnHand()).isEqualTo(before);
        }

        @Test
        @DisplayName("可售量不足以劃撥時拒絕，且錯誤碼要與「售罄」區分")
        void rejectsOverAllocation() {
            Inventory inventory = Inventory.create(SKU, 20);

            assertThatThrownBy(() -> inventory.allocate(21))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY_TO_ALLOCATE);
        }

        @Test
        @DisplayName("釋放後總量的減少量，正好等於秒殺實際賣出的量")
        void releaseReducesTotalByExactlySold() {
            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);

            // 活動期間賣掉 18 件，Redis 剩 12
            inventory.release(30, 12);

            assertThat(inventory.available()).isEqualTo(82);
            assertThat(inventory.allocated()).isZero();
            assertThat(inventory.totalOnHand()).isEqualTo(100 - 18);
        }

        @Test
        @DisplayName("一件都沒賣掉：總量完全復原")
        void releaseWithNoSalesRestoresEverything() {
            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);

            inventory.release(30, 30);

            assertThat(inventory.available()).isEqualTo(100);
            assertThat(inventory.allocated()).isZero();
        }

        @Test
        @DisplayName("全部賣光：可售量不變，劃撥量歸零")
        void releaseAfterSellOut() {
            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);

            inventory.release(30, 0);

            assertThat(inventory.available()).isEqualTo(70);
            assertThat(inventory.allocated()).isZero();
            assertThat(inventory.totalOnHand()).isEqualTo(70);
        }

        @Test
        @DisplayName("未售量不可超過劃撥量——Redis 剩得比劃撥的還多，代表帳已經壞了")
        void rejectsUnsoldExceedingAllocation() {
            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);

            assertThatThrownBy(() -> inventory.release(30, 31))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("釋放量超過劃撥中的量：拒絕而非硬做，留下可查的現場")
        void rejectsReleaseExceedingAllocated() {
            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);

            assertThatThrownBy(() -> inventory.release(40, 0))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVENTORY_RELEASE_EXCEEDS_ALLOCATION);

            // 失敗後狀態不可被改動一半
            assertThat(inventory.allocated()).isEqualTo(30);
            assertThat(inventory.available()).isEqualTo(70);
        }

        @Test
        @DisplayName("多場活動同時劃撥：allocated 累加，總量仍守恆")
        void multipleConcurrentAllocations() {
            Inventory inventory = Inventory.create(SKU, 100);

            inventory.allocate(30);
            inventory.allocate(20);

            assertThat(inventory.available()).isEqualTo(50);
            assertThat(inventory.allocated()).isEqualTo(50);
            assertThat(inventory.totalOnHand()).isEqualTo(100);

            // 其中一場結束，另一場不受影響
            inventory.release(30, 5);

            assertThat(inventory.allocated()).isEqualTo(20);
            assertThat(inventory.available()).isEqualTo(55);
        }
    }

    @Nested
    @DisplayName("守恆恆等式")
    class Conservation {

        @Test
        @DisplayName("劃撥、秒殺賣出、一般賣出、釋放交錯進行，總量帳仍然平")
        void totalIsConservedAcrossInterleavedOperations() {
            int initial = 500;
            Inventory inventory = Inventory.create(SKU, initial);

            inventory.allocate(100);      // 切 100 給秒殺
            inventory.deduct(30);         // 一般賣掉 30
            inventory.deduct(45);         // 一般再賣 45
            inventory.restoreQuantity(5); // 其中 5 件取消退回
            inventory.release(100, 22);   // 活動結束，秒殺賣了 78

            int normalSold = 30 + 45 - 5;
            int seckillSold = 100 - 22;

            assertThat(inventory.totalOnHand())
                    .as("可售 + 劃撥 = 期初 − 實際賣出")
                    .isEqualTo(initial - normalSold - seckillSold);
            assertThat(inventory.allocated()).isZero();
        }
    }

    @Nested
    @DisplayName("人工調整")
    class Adjustment {

        @Test
        @DisplayName("補貨與下修都走同一個入口")
        void adjustsBothDirections() {
            Inventory inventory = Inventory.create(SKU, 100);

            inventory.adjust(50);
            assertThat(inventory.available()).isEqualTo(150);

            inventory.adjust(-20);
            assertThat(inventory.available()).isEqualTo(130);
        }

        @Test
        @DisplayName("不可調成負數，也不可調 0")
        void rejectsInvalidAdjustments() {
            Inventory inventory = Inventory.create(SKU, 10);

            assertThatThrownBy(() -> inventory.adjust(-11)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> inventory.adjust(0)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("調整不影響劃撥出去的量")
        void adjustmentDoesNotTouchAllocated() {
            Inventory inventory = Inventory.restore(SKU, 100, 30, 0L);

            inventory.adjust(-100);

            assertThat(inventory.available()).isZero();
            assertThat(inventory.allocated()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("異動流水")
    class Movements {

        @Test
        @DisplayName("流水能重建庫存：期初 + 所有 availableDelta = 現在的可售量")
        void ledgerReconstructsAvailable() {
            // 期初 100 → 劃撥 30 → 賣 12 → 退 2 → 釋放（劃撥 30，剩 5）
            List<InventoryMovement> ledger = List.of(
                    InventoryMovement.adjust(SKU, 100, "SEED", NOW),
                    InventoryMovement.allocate(SKU, 30, 1001L, NOW),
                    InventoryMovement.deduct(SKU, 12, "ORD-1", NOW),
                    InventoryMovement.restore(SKU, 2, "ORD-1", NOW),
                    InventoryMovement.release(SKU, 30, 5, 1001L, NOW));

            Inventory inventory = Inventory.create(SKU, 100);
            inventory.allocate(30);
            inventory.deduct(12);
            inventory.restoreQuantity(2);
            inventory.release(30, 5);

            assertThat(ledger.stream().mapToInt(InventoryMovement::availableDelta).sum())
                    .as("流水重建出的可售量")
                    .isEqualTo(inventory.available());
            assertThat(ledger.stream().mapToInt(InventoryMovement::allocatedDelta).sum())
                    .as("流水重建出的劃撥量")
                    .isEqualTo(inventory.allocated());
        }

        @Test
        @DisplayName("釋放記兩個不同的數字——回到可售的量與從劃撥扣掉的量本來就不同")
        void releaseRecordsBothSides() {
            InventoryMovement movement = InventoryMovement.release(SKU, 30, 12, 1001L, NOW);

            assertThat(movement.availableDelta()).isEqualTo(12);
            assertThat(movement.allocatedDelta()).isEqualTo(-30);
        }

        @Test
        @DisplayName("全部賣光仍要記流水，否則 allocated 的減少就沒有憑據")
        void releaseWithZeroUnsoldIsStillRecorded() {
            InventoryMovement movement = InventoryMovement.release(SKU, 30, 0, 1001L, NOW);

            assertThat(movement.availableDelta()).isZero();
            assertThat(movement.allocatedDelta()).isEqualTo(-30);
        }

        @Test
        @DisplayName("兩邊都沒動的流水沒有意義，拒絕")
        void rejectsEmptyMovement() {
            assertThatThrownBy(() -> new InventoryMovement(
                    SKU, InventoryMovementType.ADJUST, 0, 0, "MANUAL", "X", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("沒有來源單號的流水無法追溯，等於沒記")
        void requiresReference() {
            assertThatThrownBy(() -> new InventoryMovement(
                    SKU, InventoryMovementType.DEDUCT, -1, 0, "ORDER", " ", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("劃撥與釋放記在活動上，扣減與退回記在訂單上")
        void referencesTheRightAggregate() {
            assertThat(InventoryMovement.allocate(SKU, 30, 1001L, NOW).refNo()).isEqualTo("1001");
            assertThat(InventoryMovement.allocate(SKU, 30, 1001L, NOW).refType())
                    .isEqualTo(InventoryMovement.RefType.ACTIVITY);
            assertThat(InventoryMovement.deduct(SKU, 1, "220575958220931072", NOW).refType())
                    .isEqualTo(InventoryMovement.RefType.ORDER);
        }

        @Test
        @DisplayName("扣減為負、退回為正——方向不靠呼叫端記得傳對符號")
        void factoriesFixTheSign() {
            assertThat(InventoryMovement.deduct(SKU, 5, "ORD-1", NOW).availableDelta())
                    .isEqualTo(-5);
            assertThat(InventoryMovement.restore(SKU, 5, "ORD-1", NOW).availableDelta())
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("建構不變式")
    class Invariants {

        @Test
        @DisplayName("負數庫存無法被建構出來")
        void rejectsNegativeQuantities() {
            assertThatThrownBy(() -> Inventory.restore(SKU, -1, 0, 0L))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Inventory.restore(SKU, 0, -1, 0L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("零庫存是合法狀態，不是錯誤")
        void zeroIsValid() {
            assertThatCode(() -> Inventory.create(SKU, 0)).doesNotThrowAnyException();
        }
    }
}
