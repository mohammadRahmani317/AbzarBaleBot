package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.User;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserAccessService {

    private final ObjectMapper objectMapper;

    private final Path filePath;

    public UserAccessService(ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${tools-market.storage.users-file:data/users.json}") String file) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(file);
    }

    // دریافت تمام کاربران
    public synchronized List<User> findAll() {
        return readUsers();
    }

    // پیدا کردن کاربر با شناسه بله
    public synchronized Optional<User> findByChatId(String chatId) {
        return readUsers().stream()
                .filter(user -> user.chatId().equals(chatId))
                .findFirst();
    }

    // ثبت درخواست دسترسی جدید
    public synchronized User registerPending(String chatId, String name) {

        List<User> users = readUsers();

        Optional<User> existingUser = users.stream()
                .filter(user -> user.chatId().equals(chatId))
                .findFirst();

        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        User user = new User(
                chatId,
                name,
                UserRole.SELLER,
                UserStatus.PENDING
        );

        users.add(user);
        writeUsers(users);

        return user;
    }

    // تغییر وضعیت کاربر
    public synchronized void updateStatus(String chatId, UserStatus status) {

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        user.role(),
                        status,
                        user.permissions()
                ));

                writeUsers(users);
                return;
            }
        }
    }

    // تغییر نقش کاربر
    public synchronized void updateRole(String chatId, UserRole role) {

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        role,
                        user.status(),
                        user.permissions()
                ));

                writeUsers(users);
                return;
            }
        }
    }

    // خواندن کاربران از فایل JSON
    private List<User> readUsers() {
        if (Files.notExists(filePath)) {
            return new ArrayList<>();
        }

        return objectMapper.readValue(
                filePath.toFile(),
                new TypeReference<>() {
                }
        );

    }

    // ذخیره کاربران در فایل JSON
    private void writeUsers(List<User> users) {
        ir.toolsmarket.tools_market_bot.util.AtomicJsonFile.write(objectMapper, filePath, users);
    }

    // ثبت یا اصلاح اطلاعات مدیر اصلی
    public synchronized void ensureSuperAdmin(String chatId) {

        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException(
                    "شناسه مدیر اصلی در تنظیمات مشخص نشده است"
            );
        }

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {

                Set<UserPermission> permissions = new HashSet<>(user.permissions());
                permissions.add(UserPermission.VIEW_PRICE_HISTORY);
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        UserRole.SUPER_ADMIN,
                        UserStatus.APPROVED,
                        permissions
                ));

                writeUsers(users);
                return;
            }
        }

        users.add(new User(
                chatId,
                "مدیر اصلی",
                UserRole.SUPER_ADMIN,
                UserStatus.APPROVED,
                Set.of(UserPermission.VIEW_PRICE_HISTORY)
        ));

        writeUsers(users);
    }

    // بررسی مجوز مستقل از نقش کاربر
    public synchronized boolean hasPermission(String chatId, UserPermission permission) {
        return findByChatId(chatId)
                .filter(user -> user.status() == UserStatus.APPROVED)
                .map(user -> switch (permission) {
                    case PRICE_MANAGER -> isOperationalRole(user.role());
                    case VIEW_PRICE_HISTORY -> user.role() == UserRole.SUPER_ADMIN;
                })
                .orElse(false);
    }

    // اعطا یا لغو یک مجوز؛ کنترل اینکه درخواست‌دهنده سوپرادمین است در لایه سرویس فراخواننده انجام می‌شود
    public synchronized void setPermission(
            String chatId,
            UserPermission permission,
            boolean enabled
    ) {
        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (!user.chatId().equals(chatId)) {
                continue;
            }

            Set<UserPermission> permissions = new HashSet<>(user.permissions());
            if (enabled) {
                permissions.add(permission);
            } else {
                permissions.remove(permission);
            }

            users.set(i, new User(
                    user.chatId(), user.name(), user.role(), user.status(), permissions
            ));
            writeUsers(users);
            return;
        }
    }

    public synchronized List<User> findAllPending() {
        return readUsers().stream()
                .filter(user -> user.status() == UserStatus.PENDING)
                .toList();
    }

    private boolean isOperationalRole(UserRole role) {
        return role == UserRole.SELLER || role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN;
    }

    public User requirePriceManager(String chatId) {
        if (!hasPermission(chatId, UserPermission.PRICE_MANAGER))
            throw new IllegalArgumentException("شما اجازه مدیریت محصولات و موجودی را ندارید.");
        return requireOperator(chatId);
    }

    public User requireOperator(String chatId) {
        return findByChatId(chatId).filter(user -> user.status() == UserStatus.APPROVED)
                .filter(user -> isOperationalRole(user.role()))
                .orElseThrow(() -> new IllegalArgumentException("شما اجازه انجام این عملیات را ندارید."));
    }

    public boolean canComplete(User user, String responsibleChatId) {
        return user.status() == UserStatus.APPROVED && (user.role() == UserRole.ADMIN
                || user.role() == UserRole.SUPER_ADMIN
                || (user.role() == UserRole.SELLER && user.chatId().equals(responsibleChatId)));
    }

    public List<User> orderNotificationRecipients() {
        return findAll().stream().filter(user -> user.status() == UserStatus.APPROVED)
                .filter(user -> user.role() == UserRole.SELLER || user.role() == UserRole.ADMIN).toList();
    }
}
