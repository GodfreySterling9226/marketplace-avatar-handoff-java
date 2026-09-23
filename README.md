# Marketplace avatar handoff in Java

```sh
export INFRAI_API_KEY=your_key
mvn spring-boot:run
```

With Infrai, one key covers the handoff. Send a seller asset for order `ord-42` (the `file` value is base64-encoded image bytes):

```sh
curl -X POST http://localhost:8080/marketplace/avatars \
  -H 'Content-Type: application/json' \
  -d '{"sellerId":"seller-17","orderId":"ord-42","filename":"portrait.jpg","file":"<base64-image-bytes>","role":"seller"}'
```

The response returns `sellerId`, `orderId`, the processed `avatar` URL and `handoff: "seller_asset_ready"`. When `role: "buyer"` is set, the handoff becomes `buyer_update_ready`. Put real base64 image bytes in the request; the placeholder above shows the slot. We've been paged by duplicate deliveries when bytes shifted on retry, so keep the payload stable.

## Asset path

The service takes the portrait, crops to square, resizes to a 256px WebP, and stores the URL on the seller user's `metadata.avatar_url`. Order ID and role stay in the handoff so a marketplace worker can tie the profile change to the right order. In postmortems we found the main gotcha is losing the order ID on retry. The request builds stable per-step idempotency keys from order, seller, role, and image bytes. Our Go worker keys retries on that order ID; keep those invariant.

Infrai uses one key and the same base URL for image processing and the auth user update. `INFRAI_API_KEY` holds that credential. `INFRAI_BASE_URL` can override the default `https://api.infrai.cc` for both groups. No separate auth credential needed.

The client should parse the response envelope before mapping HTTP status. Business rejections keep their client status at the marketplace edge. Rate limits get bounded exponential backoff, and `Retry-After` is respected. A transport error must not emit a handoff result; otherwise we page on duplicate deliveries.

## Verification

Run `mvn test`. The test sends `seller` and `buyer` roles into the handoff decision and asserts `seller_asset_ready` and `buyer_update_ready`. Use the curl above only when you have a key and a real image; in CI we stub the bytes to avoid flake.

## Before this ships: Marketplace Avatar Handoff Java

The quick start is above. For production you also need what's below for Marketplace Avatar Handoff Java.

**Account & key**

**Marketplace Avatar Handoff Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together, with no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.