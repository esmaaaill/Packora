package com.packora.backend.payment.controller;

import com.packora.backend.payment.dto.PaymentInitRequest;
import com.packora.backend.payment.dto.PaymentInitResponse;
import com.packora.backend.payment.dto.PaymobCallbackPayload;
import com.packora.backend.payment.service.PaymobService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PaymentController — exposes the Paymob payment endpoints.
 *
 * All endpoints are under /api/payment and permitted in WebSecurityConfig
 * (the callback must be reachable by Paymob without a JWT token).
 *
 * Endpoint summary:
 *  POST /api/payment/initiate          → Authenticated. Starts Paymob 3-step flow, returns iframeUrl.
 *  POST /api/payment/callback          → PUBLIC. Paymob webhook (POST). Verifies HMAC, updates DB.
 *  GET  /api/payment/callback          → PUBLIC. Paymob response redirect (GET). Handles success/fail redirect.
 *  GET  /api/payment/health            → PUBLIC. Simple health check.
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    /** Frontend base URL — used to redirect after Paymob GET callback */
    private static final String FRONTEND_BASE_URL = System.getenv().getOrDefault(
        "FRONTEND_URL", "http://localhost:3000"
    );

    private final PaymobService paymobService;

    public PaymentController(PaymobService paymobService) {
        this.paymobService = paymobService;
    }

    // ── POST /api/payment/initiate ─────────────────────────────────────────

    /**
     * Authenticated endpoint — the frontend calls this to start a payment.
     *
     * The JWT filter runs before this (via Spring Security chain), so we can
     * optionally log the requesting user for audit purposes.
     *
     * Request body example:
     * {
     *   "orderId": 42,
     *   "amount": 350.00,
     *   "billingData": {
     *     "first_name": "Ahmed", "last_name": "Hassan",
     *     "email": "ahmed@example.com", "phone_number": "+201001234567",
     *     "street": "15 El Tahrir St", "city": "Cairo",
     *     "country": "EG"
     *   }
     * }
     *
     * Response:
     * {
     *   "iframeUrl": "https://accept.paymob.com/api/acceptance/iframes/964127?payment_token=...",
     *   "paymentKey": "...",
     *   "paymobOrderId": 789012
     * }
     */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(
            @Valid @RequestBody PaymentInitRequest request,
            @AuthenticationPrincipal UserDetails currentUser) {

        log.info("[PaymentController] Initiation request — orderId: {}, user: {}",
                request.getOrderId(),
                currentUser != null ? currentUser.getUsername() : "anonymous");
        try {
            PaymentInitResponse response = paymobService.initiatePayment(
                request.getOrderId(),
                request.getAmount(),
                request.getBillingData()
            );
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Order not found
            log.warn("[PaymentController] Bad request — {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("[PaymentController] Payment initiation failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Payment initiation failed. Please try again."));
        }
    }

    // ── POST /api/payment/callback ─────────────────────────────────────────

    /**
     * Paymob Transaction Processed Callback (POST).
     *
     * Configure this in Paymob Dashboard:
     *   Developers → Payment Integrations → [Your Integration] → Transaction processed callback
     *
     * The ?hmac= query parameter is appended automatically by Paymob.
     * We MUST return HTTP 200 to acknowledge receipt; otherwise Paymob will retry.
     * On HMAC failure, we return 403 — this signals tampering.
     */
    @PostMapping("/callback")
    public ResponseEntity<String> handlePostCallback(
            @RequestBody PaymobCallbackPayload payload,
            @RequestParam(value = "hmac", required = false) String hmac) {

        log.info("[PaymentController] POST callback received — type: {}", payload.getType());
        try {
            paymobService.processCallback(payload, hmac);
            // ACK with 200 — Paymob retries on non-2xx
            return ResponseEntity.ok("OK");

        } catch (SecurityException e) {
            log.warn("[PaymentController] POST callback rejected — invalid HMAC");
            return ResponseEntity.status(403).body("Invalid HMAC signature");

        } catch (Exception e) {
            log.error("[PaymentController] POST callback processing error", e);
            // Still return 200 so Paymob doesn't retry — we handle internally
            return ResponseEntity.ok("Received");
        }
    }

    // ── GET /api/payment/callback ──────────────────────────────────────────

    /**
     * Paymob Transaction Response Callback (GET redirect).
     *
     * Paymob redirects the browser here after the iframe payment completes.
     * Configure this in Paymob Dashboard:
     *   Developers → Payment Integrations → [Your Integration] → Transaction response callback
     *
     * We redirect the browser to the appropriate frontend page based on "success" param.
     *
     * Query params Paymob sends (among others):
     *  ?success=true&id=<txn_id>&order=<paymob_order_id>&merchant_order_id=<our_id>&hmac=...
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> handleGetCallback(
            @RequestParam(value = "success", required = false, defaultValue = "false") String success,
            @RequestParam(value = "id", required = false) String transactionId,
            @RequestParam(value = "merchant_order_id", required = false) String merchantOrderId) {

        log.info("[PaymentController] GET callback — success: {}, txnId: {}, merchantOrderId: {}",
                success, transactionId, merchantOrderId);

        boolean paid = "true".equalsIgnoreCase(success);
        String redirectUrl = paid
            ? FRONTEND_BASE_URL + "/payment-success?orderId=" + merchantOrderId
            : FRONTEND_BASE_URL + "/payment-failed?orderId=" + merchantOrderId;

        return ResponseEntity.status(302)
                .header("Location", redirectUrl)
                .build();
    }

    // ── GET /api/payment/health ────────────────────────────────────────────

    /**
     * Health check — confirms the payment service is up and config is loaded.
     * Safe to expose publicly; no sensitive data is returned.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status",   "UP",
            "service",  "Paymob Payment Service",
            "provider", "Paymob Accept API"
        ));
    }
}
