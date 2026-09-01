package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 使用者聚合根。
 *
 * <p><b>密碼比對刻意不在這裡。</b> 聚合根只持有雜湊，比對需要演算法知識
 * （BCrypt 的 salt 藏在雜湊字串裡），那是基礎設施的職責。
 * 若把比對放進聚合根，領域層就得認得 BCrypt——正是 CLAUDE.md 禁止的事。
 *
 * <p>聚合根負責的是<b>「這個帳號現在能不能登入」</b>這個業務判斷，
 * 而非「這串密碼對不對」這個技術問題。兩者要分開。
 */
public final class User {

    /** 尚未持久化時為 {@code null}——由資料庫自增產生。 */
    private final Long id;
    private final Email email;
    private final Instant createdAt;

    private PasswordHash passwordHash;
    private String displayName;
    private UserRole role;
    private UserStatus status;
    private final long version;

    private User(Long id, Email email, PasswordHash passwordHash, String displayName,
                 UserRole role, UserStatus status, Instant createdAt, long version) {
        this.id = id;
        this.email = Objects.requireNonNull(email, "email 不可為 null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash 不可為 null");
        this.displayName = requireValidDisplayName(displayName);
        this.role = Objects.requireNonNull(role, "role 不可為 null");
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.version = version;
    }

    /** 註冊新帳號。此時尚無 id，由 repository 儲存後回填。 */
    public static User register(Email email, PasswordHash passwordHash, String displayName, Instant now) {
        return new User(null, email, passwordHash, displayName, UserRole.CUSTOMER, UserStatus.ACTIVE, now, 0L);
    }

    /** 從持久化狀態重建。 */
    public static User restore(Long id, Email email, PasswordHash passwordHash, String displayName,
                               UserRole role, UserStatus status, Instant createdAt, long version) {
        return new User(Objects.requireNonNull(id, "重建時 id 不可為 null"),
                email, passwordHash, displayName, role, status, createdAt, version);
    }

    /**
     * 確認此帳號當下可以通過認證。
     *
     * <p>刻意與密碼是否正確分開判斷，但<b>呼叫端必須先驗密碼再問這個問題</b>——
     * 反過來的話，攻擊者用任意密碼就能從錯誤訊息的差異推斷出
     * 「這個信箱存在且已被停權」，那是帳號枚舉漏洞。
     */
    public void ensureCanAuthenticate() {
        if (!status.canAuthenticate()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }
    }

    public void changePassword(PasswordHash newHash) {
        this.passwordHash = Objects.requireNonNull(newHash, "newHash 不可為 null");
    }

    public void rename(String displayName) {
        this.displayName = requireValidDisplayName(displayName);
    }

    /**
     * 停權。
     *
     * <p><b>呼叫端有義務同時撤銷此使用者所有的 refresh token</b>，
     * 否則停權會有最長一個 access token 生命週期的空窗。
     * 這件事無法由聚合根自己完成——它碰不到 token 儲存區。
     */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    public void promoteTo(UserRole newRole) {
        this.role = Objects.requireNonNull(newRole, "newRole 不可為 null");
    }

    public boolean isPersisted() {
        return id != null;
    }

    public Long id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public UserRole role() {
        return role;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    private static String requireValidDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "顯示名稱不可為空");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 50) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "顯示名稱不可超過 50 字");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof User other && id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /** 刻意不輸出信箱全文與雜湊——這個字串可能出現在日誌裡。 */
    @Override
    public String toString() {
        return "User{id=%s, email=%s, role=%s, status=%s}".formatted(id, email.masked(), role, status);
    }
}
