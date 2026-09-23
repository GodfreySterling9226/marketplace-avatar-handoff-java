package example.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class MarketplaceAvatarTest {
    @Test void orderHandoffDistinguishesSellerAssetsFromBuyerUpdates() {
        assertEquals("seller_asset_ready", MarketplaceAvatar.handoff("seller"));
        assertEquals("buyer_update_ready", MarketplaceAvatar.handoff("buyer"));
    }
}
