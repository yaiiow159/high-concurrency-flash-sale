package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Objects;

/** 商品類目（樹狀）。 */
public final class Category {

    /** 根類目的層級。從 1 起算而非 0，與營運後台的顯示習慣一致。 */
    public static final int ROOT_LEVEL = 1;
    private static final int MAX_LEVEL = 4;

    private final Long id;
    private final Long parentId;
    private final String name;
    private final int level;
    private final int sortOrder;

    private Category(Long id, Long parentId, String name, int level, int sortOrder) {
        this.id = id;
        this.parentId = parentId;
        this.name = requireValidName(name);
        this.level = requireValidLevel(level, parentId);
        this.sortOrder = sortOrder;
    }

    public static Category root(Long id, String name, int sortOrder) {
        return new Category(id, null, name, ROOT_LEVEL, sortOrder);
    }

    public static Category child(Long id, Category parent, String name, int sortOrder) {
        Objects.requireNonNull(parent, "父類目不可為 null");
        return new Category(id, parent.id(), name, parent.level() + 1, sortOrder);
    }

    public static Category restore(Long id, Long parentId, String name, int level, int sortOrder) {
        return new Category(id, parentId, name, level, sortOrder);
    }

    public boolean isRoot() {
        return parentId == null;
    }

    private static int requireValidLevel(int level, Long parentId) {
        boolean root = parentId == null;
        if (root && level != ROOT_LEVEL) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "根類目的層級必須為 " + ROOT_LEVEL);
        }
        if (!root && level <= ROOT_LEVEL) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "子類目的層級必須大於 " + ROOT_LEVEL);
        }
        if (level > MAX_LEVEL) {
            // 深度沒有上限的類目樹，遲早會出現一條沒有人維護得動的路徑
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "類目層級不可超過 " + MAX_LEVEL);
        }
        return level;
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "類目名稱不可為空");
        }
        return name.trim();
    }

    public Long id() {
        return id;
    }

    public Long parentId() {
        return parentId;
    }

    public String name() {
        return name;
    }

    public int level() {
        return level;
    }

    public int sortOrder() {
        return sortOrder;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Category other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
