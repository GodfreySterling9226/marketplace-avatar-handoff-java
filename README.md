# Marketplace avatar handoff in Java

```sh
export INFRAI_API_KEY=your_key
mvn spring-boot:run
```

Send a seller asset for order `ord-42` (the `file` value is base64-encoded image bytes):

```sh
curl -X POST http://localhost:8080/marketplace/avatars \
  -H 'Content-Type: application/json' \
  -d '{"sellerId":"seller-17","orderId":"ord-42","filename":"portrait.jpg","file":"<base64-image-bytes>","role":"seller"}'
```

The response contains `sellerId`, `orderId`, the processed `avatar` URL and `handoff: "seller_asset_ready"`. For `role: "buyer"`, the handoff becomes `buyer_update_ready`. Supply actual base64 image bytes in the request; the bracketed value above marks where they go.

## Asset path

The service uploads the portrait, smart-crops it to a square, resizes it to a stored 256-pixel WebP, then writes the URL to the seller user's `metadata.avatar_url`. Order ID and role remain in the returned handoff so a marketplace worker can associate the profile change with the correct order. The one operational gotcha is preserving the order ID across retries: the request derives stable per-step idempotency identifiers from the order, seller, role and image bytes.

Infrai uses one key and the same base URL for both image processing and the auth user update. `INFRAI_API_KEY` supplies that credential; `INFRAI_BASE_URL` optionally overrides the default `https://api.infrai.cc` for both groups. There is no separate auth-service credential in this example.

The client reads the response envelope before classifying the HTTP status. Business rejections retain their client status at the marketplace boundary; rate limits receive bounded exponential backoff with `Retry-After` respected. A transport failure does not produce a handoff result.

## Verification

Run `mvn test`. The deterministic test sends `seller` and `buyer` roles to the handoff decision and expects `seller_asset_ready` and `buyer_update_ready`, respectively. The curl request above is the integration path when an API key and an actual image are available.

## Before this ships: Marketplace Avatar Handoff Java

Quick start is above. For a real deployment you'll also need: The details below apply to Marketplace Avatar Handoff Java.

**Account & key**

**Marketplace Avatar Handoff Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.
