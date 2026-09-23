package example.avatar;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class MarketplaceAvatar {
    private final InfraiClient infrai;

    public MarketplaceAvatar(@Value("${infrai.base-url}") String baseUrl,
                             @Value("${infrai.api-key}") String key) {
        this.infrai = new InfraiClient(baseUrl, key);
    }

    public static void main(String[] args) {
        SpringApplication.run(MarketplaceAvatar.class, args);
    }

    public record AvatarRequest(String sellerId, String orderId, String filename, String file,
                                String role) {}
    public record AvatarResult(String sellerId, String orderId, String avatar, String handoff) {}

    static String handoff(String role) {
        return "seller".equals(role) ? "seller_asset_ready" : "buyer_update_ready";
    }

    @PostMapping("/marketplace/avatars")
    public AvatarResult publish(@RequestBody AvatarRequest request) throws Exception {
        if (request.sellerId() == null || request.sellerId().isBlank()
                || request.orderId() == null || request.orderId().isBlank()
                || request.filename() == null || request.filename().isBlank()
                || request.file() == null || request.file().isBlank()
                || !("seller".equals(request.role()) || "buyer".equals(request.role()))) {
            throw new IllegalArgumentException("sellerId, orderId, filename, file and role (seller or buyer) are required");
        }
        Base64.getDecoder().decode(request.file());
        String operation = UUID.nameUUIDFromBytes((request.sellerId() + ":" + request.orderId()
                + ":" + request.role() + ":" + request.file()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JsonNode uploaded = infrai.post("/v1/image/upload",
                Map.of("file", request.file(), "filename", request.filename()), operation + ":upload");
        String source = uploaded.path("url").asText();
        if (source.isBlank()) throw new IllegalStateException("Upload response omitted the asset URL");
        JsonNode cropped = infrai.post("/v1/image/smart_crop",
                Map.of("image", source, "aspect", "1:1"), operation + ":crop");
        String square = cropped.path("url").asText();
        if (square.isBlank()) throw new IllegalStateException("Crop response omitted the asset URL");
        JsonNode resized = infrai.post("/v1/image/resize",
                Map.of("image", square, "width", 256, "height", 256,
                        "fit", "cover", "format", "webp", "store", true), operation + ":resize");
        String avatar = resized.path("url").asText();
        if (avatar.isBlank()) {
            throw new IllegalStateException("Image response omitted the asset URL");
        }
        infrai.patch("/v1/auth/user/update/" + java.net.URLEncoder.encode(request.sellerId(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.of("user_id", request.sellerId(),
                        "metadata", Map.of("avatar_url", avatar)), operation + ":profile");
        return new AvatarResult(request.sellerId(), request.orderId(), avatar, handoff(request.role()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(InfraiClient.Rejection.class)
    ResponseEntity<Map<String, String>> rejected(InfraiClient.Rejection error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("error", error.code));
    }
}

final class InfraiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String key;

    InfraiClient(String baseUrl, String key) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.key = key;
    }

    JsonNode post(String path, Object body, String operation) throws Exception {
        return send("POST", path, body, operation);
    }

    JsonNode patch(String path, Object body, String operation) throws Exception {
        return send("PATCH", path, body, operation);
    }

    private JsonNode send(String method, String path, Object body, String operation) throws Exception {
        if (key.isBlank()) throw new IllegalStateException("INFRAI_API_KEY is required");
        String payload = json.writeValueAsString(body);
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", operation)
                    .method(method, HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3) {
                long seconds = response.headers().firstValue("Retry-After")
                        .map(value -> { try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; } })
                        .orElse(0L);
                Thread.sleep(Math.max(seconds * 1000, 250L * (1L << attempt)));
                continue;
            }
            if (!envelope.path("ok").asBoolean()) {
                JsonNode error = envelope.path("error");
                throw new Rejection(error.path("code").asText("REQUEST_REJECTED"), response.statusCode());
            }
            if (response.statusCode() >= 500) throw new IllegalStateException("Upstream request failed");
            return envelope.path("data");
        }
        throw new IllegalStateException("Retry budget exhausted");
    }

    static final class Rejection extends RuntimeException {
        final String code;
        final int status;
        Rejection(String code, int status) { super(code); this.code = code; this.status = status; }
    }
}
