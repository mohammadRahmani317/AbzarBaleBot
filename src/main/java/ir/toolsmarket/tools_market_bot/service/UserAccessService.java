
package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.User;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserAccessService {

    private final ObjectMapper objectMapper;

    private final Path filePath = Path.of("data/users.json");

    public UserAccessService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                        status
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
                        user.status()
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
        try {
            Files.createDirectories(filePath.getParent());

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(filePath.toFile(), users);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "خطا در ذخیره اطلاعات کاربران",
                    exception
            );
        }
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

                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        UserRole.SUPER_ADMIN,
                        UserStatus.APPROVED
                ));

                writeUsers(users);
                return;
            }
        }

        users.add(new User(
                chatId,
                "مدیر اصلی",
                UserRole.SUPER_ADMIN,
                UserStatus.APPROVED
        ));

        writeUsers(users);
    }

    public synchronized List<User> findAllPending() {
        return readUsers().stream()
                .filter(user -> user.status() == UserStatus.PENDING)
                .toList();
    }
}
