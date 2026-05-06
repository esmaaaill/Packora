package com.packora.backend.payment.service;

import com.packora.backend.model.Order;
import com.packora.backend.model.Payment;
import com.packora.backend.model.enums.OrderStatus;
import com.packora.backend.model.enums.PaymentStatus;
import com.packora.backend.payment.config.PaymobConfig;
import com.packora.backend.payment.dto.*;
import com.packora.backend.repository.OrderRepository;
import com.packora.backend.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PaymobService — orchestrates the Paymob Accept API 3-step payment flow:
 *
 *  Step 1 → POST /auth/tokens              : authenticate → get auth_token
 *  Step 2 → POST /ecommerce/orders         : register order → get paymob_order_id
 *  Step 3 → POST /acceptance/payment_keys  : get payment key → build iframe URL
 *
 * Security:
 *  - All credentials read from environment variables via PaymobConfig (never hardcoded).
 *  - HMAC-SHA512 verification on every incoming webhook before any DB write.
 *  - Idempotent webhook processing: re-delivery of the same callback is a no-op.
 *  - @Transactional on webhook processing to ensure DB consistency.
 */
@Service
public class PaymobService {

    private static final Logger log = LoggerFactory.getLogger(PaymobService.class);

    // Paymob HMAC algorithm — Accept API uses SHA-512
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private final PaymobConfig paymobConfig;
    private final RestTemplate restTemplate;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public PaymobService(PaymobConfig paymobConfig,
                         RestTemplate restTemplate,
                         OrderRepository orderRepository,
                         PaymentRepository paymentRepository) {
        this.paymobConfig = paymobConfig;
        this.restTemplate = restTemplate;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    // ── PUBLIC API ─────────────────────────────────────────────────────────

    /**
     * Executes the full Paymob 3-step flow and returns a PaymentInitResponse
     * containing the iframe URL ready for the frontend to embed.
     *
     * @param orderId     our internal Packora order ID
     * @param amountEGP   total amount in EGP (will be converted to cents)
     * @param billingData buyer billing info required by Paymob
     * @return PaymentInitResponse with iframeUrl, paymentKey, paymobOrderId
     * @throws IllegalArgumentException if the orderId does not exist in our DB
     * @throws RuntimeException         if any Paymob API step fails
     */
    @Transactional
    public PaymentInitResponse initiatePayment(Long orderId, Double amountEGP, BillingData billingData) {
        log.info("[Paymob] Initiating payment for Packora order ID: {}", orderId);

        // Fail fast if the order doesn't exist — don't call Paymob for ghost orders
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // ── Step 1: Authenticate ───────────────────────────────────────────
        String authToken = authenticate();
        log.info("[Paymob] Step 1 complete — auth token obtained");

        // ── Step 2: Register order ─────────────────────────────────────────
        long amountCents = Math.round(amountEGP * 100);
        Long paymobOrderId = registerOrder(authToken, orderId, amountCents);
        log.info("[Paymob] Step 2 complete — Paymob order ID: {}", paymobOrderId);

        // ── Step 3: Get payment key ────────────────────────────────────────
        String paymentKey = getPaymentKey(authToken, paymobOrderId, amountCents, billingData);
        log.info("[Paymob] Step 3 complete — payment key obtained");

        // ── Build iframe URL ───────────────────────────────────────────────
        String iframeUrl = buildIframeUrl(paymentKey);
        log.info("[Paymob] iframe URL built: {}", iframeUrl);

        // ── Persist a PENDING payment record ──────────────────────────────
        // We store "PAYMOB_ORDER_<id>" as a placeholder until the webhook arrives
        // with the real Paymob transaction ID.
        persistPendingPayment(order, amountEGP, paymobOrderId);

        return new PaymentInitResponse(iframeUrl, paymentKey, paymobOrderId);
    }

    /**
     * Processes an incoming Paymob webhook callback.
     *
     * Security model:
     *  1. Verify HMAC — reject anything that didn't come from Paymob.
     *  2. Idempotency — if we already processed this Paymob txn, skip it.
     *  3. DB update — update Payment status then sync Order status.
     *
     * @param payload   parsed callback JSON body
     * @param hmacValue HMAC signature from query param ?hmac=...
     * @throws SecurityException if the HMAC signature is missing or invalid
     */
    @Transactional
    public void processCallback(PaymobCallbackPayload payload, String hmacValue) {
        // ── Security gate: verify HMAC before ANY database operation ──────
        if (!verifyHmac(payload, hmacValue)) {
            log.warn("[Paymob] HMAC verification FAILED — callback rejected (possible tampering attempt)");
            throw new SecurityException("Invalid Paymob HMAC signature");
        }

        PaymobCallbackPayload.TransactionObj txn = payload.getObj();
        if (txn == null) {
            log.warn("[Paymob] Callback received with null transaction object — skipping");
            return;
        }

        String paymobTxnId  = String.valueOf(txn.getId());
        boolean success     = txn.isSuccess();

        log.info("[Paymob] Verified callback — txnId: {}, success: {}", paymobTxnId, success);

        // ── Idempotency check: if txn was already processed, skip ─────────
        Optional<Payment> existingByTxnId = paymentRepository.findByTransactionId(paymobTxnId);
        if (existingByTxnId.isPresent()) {
            Payment existing = existingByTxnId.get();
            if (existing.getStatus() != PaymentStatus.PENDING) {
                log.info("[Paymob] Txn {} already processed with status {} — skipping (idempotent)",
                        paymobTxnId, existing.getStatus());
                return;
            }
            // Still PENDING — update it
            updatePaymentStatus(existing, success, txn);
            return;
        }

        // ── Look up by Paymob order placeholder ───────────────────────────
        if (txn.getOrder() != null) {
            Long paymobOrderId = txn.getOrder().getId();
            if (paymobOrderId != null) {
                String placeholder = "PAYMOB_ORDER_" + paymobOrderId;
                Optional<Payment> byPlaceholder = paymentRepository
                        .findByTransactionIdAndStatus(placeholder, PaymentStatus.PENDING);

                if (byPlaceholder.isPresent()) {
                    updatePaymentStatus(byPlaceholder.get(), success, txn);
                    return;
                }
            }

            // ── Fallback: look up by merchant_order_id (our Packora order ID) ─
            // merchant_order_id format: "<orderId>_<timestamp>" (timestamp appended for uniqueness)
            String merchantOrderId = txn.getOrder().getMerchantOrderId();
            if (merchantOrderId != null) {
                try {
                    // Strip the "_<timestamp>" suffix if present
                    String rawId = merchantOrderId.contains("_")
                            ? merchantOrderId.split("_")[0]
                            : merchantOrderId;
                    Long packOrderId = Long.parseLong(rawId);
                    List<Payment> pendingPayments = paymentRepository.findByOrderId(packOrderId)
                            .stream()
                            .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                            .toList();
                    if (!pendingPayments.isEmpty()) {
                        updatePaymentStatus(pendingPayments.get(0), success, txn);
                        return;
                    }
                } catch (NumberFormatException e) {
                    log.warn("[Paymob] Could not parse merchantOrderId '{}' as Long", merchantOrderId);
                }
            }
        }

        log.warn("[Paymob] No matching PENDING payment found for txnId: {} — callback ignored", paymobTxnId);
    }

    // ── PRIVATE — Paymob Step 1: Authentication ────────────────────────────

    @SuppressWarnings("unchecked")
    private String authenticate() {
        String url = paymobConfig.getBaseUrl() + "/auth/tokens";

        Map<String, Object> body = new HashMap<>();
        body.put("api_key", paymobConfig.getApiKey());

        ResponseEntity<Map> response = restTemplate.postForEntity(url, buildJsonRequest(body), Map.class);
        validateResponse(response, "authentication");

        String token = (String) response.getBody().get("token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException("[Paymob] Authentication failed — no token in response");
        }
        return token;
    }

    // ── PRIVATE — Paymob Step 2: Order Registration ────────────────────────

    @SuppressWarnings("unchecked")
    private Long registerOrder(String authToken, Long packOrderId, long amountCents) {
        String url = paymobConfig.getBaseUrl() + "/ecommerce/orders";

        // Append a timestamp suffix to make merchant_order_id globally unique per attempt.
        // Paymob rejects duplicate merchant_order_ids across all time (not just per session).
        // The webhook handler strips the suffix back to get our real orderId.
        String uniqueMerchantOrderId = packOrderId + "_" + System.currentTimeMillis();

        Map<String, Object> body = new HashMap<>();
        body.put("auth_token", authToken);
        body.put("delivery_needed", false);
        body.put("amount_cents", amountCents);
        body.put("currency", "EGP");
        body.put("merchant_order_id", uniqueMerchantOrderId);
        body.put("items", List.of());

        log.info("[Paymob] Registering order — merchant_order_id: {}", uniqueMerchantOrderId);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, buildJsonRequest(body), Map.class);
        validateResponse(response, "order registration");

        Object idObj = response.getBody().get("id");
        if (idObj == null) {
            throw new RuntimeException("[Paymob] Order registration failed — no id in response");
        }
        return Long.parseLong(idObj.toString());
    }

    // ── PRIVATE — Paymob Step 3: Payment Key ──────────────────────────────

    @SuppressWarnings("unchecked")
    private String getPaymentKey(String authToken, Long paymobOrderId,
                                  long amountCents, BillingData billingData) {
        String url = paymobConfig.getBaseUrl() + "/acceptance/payment_keys";

        Map<String, Object> body = new HashMap<>();
        body.put("auth_token", authToken);
        body.put("expiration", 3600);            // token valid for 1 hour
        body.put("order_id", paymobOrderId);
        body.put("billing_data", billingDataToMap(billingData));
        body.put("amount_cents", amountCents);
        body.put("currency", "EGP");
        body.put("integration_id", paymobConfig.getIntegrationId());

        ResponseEntity<Map> response = restTemplate.postForEntity(url, buildJsonRequest(body), Map.class);
        validateResponse(response, "payment key");

        String token = (String) response.getBody().get("token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException("[Paymob] Payment key request failed — no token in response");
        }
        return token;
    }

    // ── PRIVATE — Helpers ──────────────────────────────────────────────────

    /** Constructs the Paymob iFrame URL from the payment key and configured iframe ID */
    private String buildIframeUrl(String paymentKey) {
        return String.format(
            "https://accept.paymob.com/api/acceptance/iframes/%d?payment_token=%s",
            paymobConfig.getIframeId(),
            paymentKey
        );
    }

    /** Wraps a map body into an HttpEntity with JSON Content-Type header */
    private HttpEntity<Map<String, Object>> buildJsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** Validates that a Paymob response is 2xx and has a non-null body */
    private void validateResponse(ResponseEntity<?> response, String step) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException(
                String.format("[Paymob] %s step failed — HTTP %s", step, response.getStatusCode())
            );
        }
    }

    /** Converts BillingData DTO to a plain Map for embedding in the Paymob request body */
    private Map<String, Object> billingDataToMap(BillingData bd) {
        Map<String, Object> map = new HashMap<>();
        map.put("first_name",       safeStr(bd.getFirstName()));
        map.put("last_name",        safeStr(bd.getLastName()));
        map.put("email",            safeStr(bd.getEmail()));
        map.put("phone_number",     safeStr(bd.getPhoneNumber()));
        map.put("apartment",        safeStr(bd.getApartment()));
        map.put("floor",            safeStr(bd.getFloor()));
        map.put("street",           safeStr(bd.getStreet()));
        map.put("building",         safeStr(bd.getBuilding()));
        map.put("shipping_method",  safeStr(bd.getShippingMethod()));
        map.put("postal_code",      safeStr(bd.getPostalCode()));
        map.put("city",             safeStr(bd.getCity()));
        map.put("country",          safeStr(bd.getCountry()));
        map.put("state",            safeStr(bd.getState()));
        return map;
    }

    /**
     * Returns the value or "NA" if null/blank.
     * Paymob requires ALL billing fields to be non-null strings; "NA" is the safe fallback.
     */
    private String safeStr(String value) {
        return (value != null && !value.isBlank()) ? value : "NA";
    }

    /**
     * Persists a PENDING Payment record so we can track state before the webhook arrives.
     * The placeholder transactionId "PAYMOB_ORDER_<id>" is replaced by the real Paymob txn ID
     * once the webhook is received and validated.
     */
    private void persistPendingPayment(Order order, Double amountEGP, Long paymobOrderId) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(amountEGP);
        payment.setMethod("CARD");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setTransactionId("PAYMOB_ORDER_" + paymobOrderId);
        paymentRepository.save(payment);
        log.info("[Paymob] PENDING payment record saved for order {}", order.getId());
    }

    /**
     * Updates the Payment entity status based on the verified Paymob callback result.
     * Also synchronises the parent Order status to keep them consistent.
     */
    private void updatePaymentStatus(Payment payment,
                                      boolean success,
                                      PaymobCallbackPayload.TransactionObj txn) {
        String paymobTxnId = String.valueOf(txn.getId());

        // Determine new status in priority order: voided > refunded > success/failed
        PaymentStatus newStatus;
        if (txn.isVoided()) {
            newStatus = PaymentStatus.REFUNDED;
        } else if (txn.isRefunded()) {
            newStatus = PaymentStatus.REFUNDED;
        } else if (success) {
            newStatus = PaymentStatus.COMPLETED;
        } else {
            newStatus = PaymentStatus.FAILED;
        }

        payment.setStatus(newStatus);
        payment.setTransactionId(paymobTxnId); // Replace placeholder with real Paymob txn ID
        paymentRepository.save(payment);

        log.info("[Paymob] Payment {} → status updated to {}", paymobTxnId, newStatus);

        // Sync parent Order status
        syncOrderStatus(payment.getOrder(), newStatus);
    }

    /**
     * Keeps the Order status in sync with the Payment status.
     * COMPLETED payment → mark Order as PAID
     * FAILED payment    → mark Order as CANCELLED
     * REFUNDED payment  → mark Order as CANCELLED (can be enhanced to REFUNDED order status)
     */
    private void syncOrderStatus(Order order, PaymentStatus paymentStatus) {
        if (order == null) return;

        OrderStatus newOrderStatus = switch (paymentStatus) {
            case COMPLETED -> OrderStatus.PAID;
            case FAILED, REFUNDED -> OrderStatus.CANCELLED;
            default -> null; // PENDING — no change
        };

        if (newOrderStatus != null) {
            order.setStatus(newOrderStatus);
            orderRepository.save(order);
            log.info("[Paymob] Order {} status synced → {}", order.getId(), newOrderStatus);
        }
    }

    // ── PRIVATE — HMAC Verification ────────────────────────────────────────

    /**
     * Verifies the Paymob HMAC-SHA512 signature.
     *
     * Paymob concatenates specific transaction fields (in strict order, no separator)
     * and hashes them with your HMAC secret using SHA-512.
     * We replicate the same calculation and compare (case-insensitive) against received.
     *
     * Official field order (from Paymob docs):
     *   amount_cents, created_at, currency, error_occured, has_parent_transaction,
     *   id, integration_id, is_3d_secure, is_auth, is_capture, is_refunded,
     *   is_standalone_payment, is_voided, order.id, owner, pending,
     *   source_data.pan, source_data.sub_type, source_data.type, success
     *
     * @param payload       the full parsed webhook body
     * @param receivedHmac  the ?hmac= query param value from Paymob
     * @return true if the computed HMAC matches the received one
     */
    private boolean verifyHmac(PaymobCallbackPayload payload, String receivedHmac) {
        // A missing HMAC is immediately rejected — Paymob always sends it
        if (receivedHmac == null || receivedHmac.isBlank()) {
            log.warn("[Paymob] No HMAC provided in callback query params");
            return false;
        }

        try {
            PaymobCallbackPayload.TransactionObj t = payload.getObj();
            if (t == null) return false;

            // Safe extraction of nested fields
            String pan     = (t.getSourceData() != null) ? safeStr(t.getSourceData().getPan())     : "NA";
            String subType = (t.getSourceData() != null) ? safeStr(t.getSourceData().getSubType()) : "NA";
            String srcType = (t.getSourceData() != null) ? safeStr(t.getSourceData().getType())    : "NA";
            String orderId = (t.getOrder() != null)      ? String.valueOf(t.getOrder().getId())     : "NA";

            // Build the concatenated string in the EXACT order Paymob specifies
            String data = String.valueOf(t.getAmountCents())
                    + safeStr(t.getCreatedAt())
                    + safeStr(t.getCurrency())
                    + t.isErrorOccured()
                    + t.isHasParentTransaction()
                    + t.getId()
                    + t.getIntegrationId()
                    + t.is3dSecure()
                    + t.isAuth()
                    + t.isCapture()
                    + t.isRefunded()
                    + t.isStandalonePayment()
                    + t.isVoided()
                    + orderId
                    + t.getOwner()
                    + t.isPending()
                    + pan
                    + subType
                    + srcType
                    + t.isSuccess();

            String calculated = hmacSha512(data, paymobConfig.getHmacSecret());

            // Use equalsIgnoreCase — Paymob may return uppercase or lowercase hex
            boolean valid = calculated.equalsIgnoreCase(receivedHmac);

            if (!valid) {
                log.warn("[Paymob] HMAC mismatch — calculated: {}, received: {}", calculated, receivedHmac);
            }
            return valid;

        } catch (Exception e) {
            log.error("[Paymob] HMAC verification threw an exception", e);
            return false;
        }
    }

    /** Computes HMAC-SHA512 and returns the result as a lowercase hex string */
    private String hmacSha512(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(
            secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM
        );
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder(rawHmac.length * 2);
        for (byte b : rawHmac) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
