package me.thesunnycode.store.payments;

import me.thesunnycode.store.orders.Order;
import me.thesunnycode.store.carts.CartEmptyException;
import me.thesunnycode.store.carts.CartNotFoundException;
import me.thesunnycode.store.carts.CartRepository;
import me.thesunnycode.store.orders.OrderRepository;
import me.thesunnycode.store.auth.AuthService;
import me.thesunnycode.store.carts.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class CheckoutService {
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final CartService cartService;
    private final PaymentGateway paymentGateway;

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request) {
        var cart = cartRepository.getCartWithItems(request.getCartId()).orElse(null);
        if (cart == null) {
            throw new CartNotFoundException();
        }

        if (cart.isEmpty()) {
            throw new CartEmptyException();
        }

        var order = Order.fromCart(cart, authService.getCurrentUser());

        orderRepository.save(order);

        try {
            var session = paymentGateway.createCheckoutSession(order);

            cartService.clearCart(cart.getId());

            return new CheckoutResponse(order.getId(), session.getCheckoutUrl());
        }
        catch (PaymentException ex) {
            orderRepository.delete(order);
            throw ex;
        }
    }

    public void handleWebhookEvent(WebhookRequest request) {
        paymentGateway
            .parseWebhookRequest(request)
            .ifPresent(paymentResult -> {
                var order = orderRepository.findById(paymentResult.getOrderId()).orElseThrow();
                order.setStatus(paymentResult.getPaymentStatus());
                orderRepository.save(order);
            });
    }
}
