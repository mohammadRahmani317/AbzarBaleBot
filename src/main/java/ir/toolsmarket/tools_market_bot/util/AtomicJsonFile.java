package ir.toolsmarket.tools_market_bot.util;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

public final class AtomicJsonFile {
    private AtomicJsonFile() { }

    public static void write(ObjectMapper mapper, Path path, Object value) {
        Path target = path.toAbsolutePath();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try (FileChannel channel = FileChannel.open(temporary, WRITE)) { channel.force(true); }
            // Refuse a non-atomic replacement: a failed commit must leave the previous snapshot intact.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(target.getParent(), READ)) { directory.force(true); }
        } catch (IOException exception) {
            throw new IllegalStateException("خطا در ذخیره اطلاعات؛ دوباره تلاش کنید.", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }
}
