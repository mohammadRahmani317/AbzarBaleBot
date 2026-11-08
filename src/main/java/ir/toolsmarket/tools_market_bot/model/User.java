package ir.toolsmarket.tools_market_bot.model;

import java.util.Set;

public record User(
        String chatId,
        String name,
        UserRole role,
        UserStatus status,
        Set<UserPermission> permissions
) {

    public User {
        // سازگاری با کاربران قدیمی که فیلد permissions ندارند
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public User(String chatId, String name, UserRole role, UserStatus status) {
        this(chatId, name, role, status, Set.of());
    }

    public boolean hasPermission(UserPermission permission) {
        return permissions.contains(permission);
    }
}