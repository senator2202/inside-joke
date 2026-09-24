package com.insidejoke.billing;

import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.billing.dto.CheckoutConfigDto;
import com.insidejoke.billing.dto.OfferDto;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything the pass picker (H4) needs to open the Paddle overlay (H5) for the signed-in host. */
@RestController
public class CheckoutController {

    private final PaddleProperties paddle;

    public CheckoutController(PaddleProperties paddle) {
        this.paddle = paddle;
    }

    @GetMapping("/api/billing/checkout")
    public CheckoutConfigDto checkout(@AuthenticationPrincipal AppPrincipal user) {
        if (!paddle.checkoutEnabled()) {
            return new CheckoutConfigDto(
                    false, paddle.environment(), null, List.of(), user.email(), Map.of(), paddle.supportEmail());
        }
        List<OfferDto> offers = Arrays.stream(Product.values())
                .map(p -> new OfferDto(
                        p,
                        paddle.priceFor(p),
                        p.priceUsdCents(),
                        p.monthlyGameLimit(),
                        p.validity().toHours()))
                .toList();
        return new CheckoutConfigDto(
                true,
                paddle.environment(),
                paddle.clientToken(),
                offers,
                user.email(),
                Map.of("userId", user.id().toString()),
                paddle.supportEmail());
    }
}
