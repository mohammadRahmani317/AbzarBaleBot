package ir.toolsmarket.tools_market_bot.integration.bale;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public class BaleBotClient {

    private final RestClient restClient;

    public BaleBotClient(String baseUrl, String token) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/bot" + token)
                .build();
    }

    // غیرفعال کردن Webhook قبلی
    public JsonNode deleteWebhook() {
        return restClient.post()
                .uri("/deleteWebhook")
                .retrieve()
                .body(JsonNode.class);
    }

    // دریافت پیام‌های جدید
    public JsonNode getUpdates(long offset) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", 10)
                        .queryParam("limit", 100)
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode sendMessage(String chatId, String text) {
        return restClient.post()
                .uri("/sendMessage")
                .body(Map.of(
                        "chat_id", chatId,
                        "text", text
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode sendMessage(
            String chatId,
            String text,
            Map<String, Object> replyMarkup
    ) {
        return restClient.post()
                .uri("/sendMessage")
                .body(Map.of(
                        "chat_id", chatId,
                        "text", text,
                        "reply_markup", replyMarkup
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    // پاسخ به کلیک روی دکمه Inline
    public JsonNode answerCallbackQuery(String callbackQueryId) {
        return restClient.post()
                .uri("/answerCallbackQuery")
                .body(Map.of(
                        "callback_query_id", callbackQueryId
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode sendPhoto(
            String chatId,
            byte[] photoBytes,
            String fileName,
            String caption,
            Map<String, Object> replyMarkup
    ) {
        // آپلود عکس به API بله با multipart/form-data
        var resource = new org.springframework.core.io.ByteArrayResource(photoBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();

        parts.add("chat_id", chatId);
        parts.add("photo", resource);
        parts.add("caption", caption);

        if (replyMarkup != null) {
            parts.add("reply_markup",
                    new org.springframework.http.HttpEntity<>(
                            new tools.jackson.databind.ObjectMapper().writeValueAsString(replyMarkup),
                            new org.springframework.http.HttpHeaders()
                    )
            );
        }

        return restClient.post()
                .uri("/sendPhoto")
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(JsonNode.class);
    }
}