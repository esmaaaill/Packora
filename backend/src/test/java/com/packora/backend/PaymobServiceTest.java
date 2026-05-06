package com.packora.backend;

import com.packora.backend.model.Order;
import com.packora.backend.model.Payment;
import com.packora.backend.model.enums.OrderStatus;
import com.packora.backend.model.enums.PaymentStatus;
import com.packora.backend.payment.config.PaymobConfig;
import com.packora.backend.payment.dto.BillingData;
import com.packora.backend.payment.dto.PaymobCallbackPayload;
import com.packora.backend.payment.dto.PaymentInitResponse;
import com.packora.backend.repository.OrderRepository;
import com.packora.backend.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.packora.backend.payment.service.PaymobService;

/**
 * Unit tests for PaymobService.
 *
 * Test cases:
 *  TC-01  initiatePayment — happy path → PENDING payment saved, iframeUrl returned
 *  TC-02  initiatePayment — order not found → IllegalArgumentException (no Paymob call)
 *  TC-03  processCallback — null HMAC → SecurityException, zero DB writes
 *  TC-04  processCallback — wrong HMAC → SecurityException, zero DB writes
 *  TC-05  processCallback — success=true, found by placeholder → COMPLETED, order PAID
 *  TC-06  processCallback — success=false, found by placeholder → FAILED, order CANCELLED
 *  TC-07  processCallback — voided → REFUNDED
 *  TC-08  processCallback — already COMPLETED (idempotent) → save() NOT called again
 */
@ExtendWith(MockitoExtension.class)
class PaymobServiceTest {

    @Mock private PaymobConfig      paymobConfig;
    @Mock private RestTemplate      restTemplate;
    @Mock private OrderRepository   orderRepository;
    @Mock private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymobService paymobService;

    @Captor
    private ArgumentCaptor<Payment> paymentCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private Order   testOrder;
    private Payment pendingPayment;

    @BeforeEach
    void setUp() {
        testOrder = new Order();
        testOrder.setId(42L);
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setTotalAmount(350.0);

        pendingPayment = new Payment();
        pendingPayment.setId(1L);
        pendingPayment.setOrder(testOrder);
        pendingPayment.setAmount(350.0);
        pendingPayment.setStatus(PaymentStatus.PENDING);
        pendingPayment.setTransactionId("PAYMOB_ORDER_789");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-01 — initiatePayment happy path
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-01: initiatePayment — success → saves PENDING payment, returns iframeUrl")
    void initiatePayment_success_createsPendingRecord() {
        // Arrange
        stubPaymobConfig();
        when(orderRepository.findById(42L)).thenReturn(Optional.of(testOrder));

        // Step 1 — auth
        when(restTemplate.postForEntity(contains("/auth/tokens"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("token", "auth-abc")));

        // Step 2 — order registration
        when(restTemplate.postForEntity(contains("/ecommerce/orders"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", 789)));

        // Step 3 — payment key
        when(restTemplate.postForEntity(contains("/payment_keys"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("token", "pkey-xyz")));

        BillingData billing = new BillingData(
                "Ahmed", "Hassan", "ahmed@test.com", "+201001234567", "El Tahrir St", "Cairo");

        // Act
        PaymentInitResponse result = paymobService.initiatePayment(42L, 350.0, billing);

        // Assert — response
        assertThat(result.getIframeUrl())
                .contains("964127")
                .contains("pkey-xyz");
        assertThat(result.getPaymobOrderId()).isEqualTo(789L);
        assertThat(result.getPaymentKey()).isEqualTo("pkey-xyz");

        // Assert — DB: exactly one Payment saved as PENDING
        verify(paymentRepository, times(1)).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getTransactionId()).isEqualTo("PAYMOB_ORDER_789");
        assertThat(saved.getMethod()).isEqualTo("CARD");
        assertThat(saved.getAmount()).isEqualTo(350.0);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-02 — initiatePayment: order not found
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-02: initiatePayment — order not found → IllegalArgumentException, no Paymob API calls")
    void initiatePayment_orderNotFound_throwsWithoutCallingPaymob() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        BillingData billing = new BillingData(
                "Test", "User", "t@t.com", "+201000000000", "Street", "Cairo");

        assertThatThrownBy(() -> paymobService.initiatePayment(999L, 100.0, billing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order not found: 999");

        // CRITICAL: Paymob API must NOT be called for non-existent orders
        verifyNoInteractions(restTemplate);
        // DB must NOT be touched
        verify(paymentRepository, never()).save(any());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-03 — processCallback: null HMAC
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-03: processCallback — null HMAC → SecurityException, zero DB writes")
    void processCallback_nullHmac_throwsSecurityException() {
        PaymobCallbackPayload payload = buildPayload(true, false, false, 101L, 42L);

        assertThatThrownBy(() -> paymobService.processCallback(payload, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("HMAC");

        // No DB interaction on rejected requests
        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(orderRepository);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-04 — processCallback: wrong HMAC
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-04: processCallback — wrong HMAC → SecurityException, zero DB writes")
    void processCallback_wrongHmac_throwsSecurityException() {
        when(paymobConfig.getHmacSecret()).thenReturn("correct-secret-key");

        PaymobCallbackPayload payload = buildPayload(true, false, false, 101L, 42L);

        // A clearly wrong HMAC — will not match HMAC-SHA512 of "correct-secret-key"
        assertThatThrownBy(() -> paymobService.processCallback(payload, "totally-wrong-hmac"))
                .isInstanceOf(SecurityException.class);

        // CRITICAL security: DB must not be touched when HMAC is wrong
        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(orderRepository);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-05 — processCallback: success=true → COMPLETED + Order PAID
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-05: processCallback — success=true, found by txn placeholder → COMPLETED + Order PAID")
    void processCallback_success_updatesPaymentCompletedAndOrderPaid() throws Exception {
        // Compute the real HMAC for this payload so verifyHmac passes
        String secret  = "test-hmac-secret";
        PaymobCallbackPayload payload = buildPayload(true, false, false, 101L, 42L);
        String realHmac = computeExpectedHmac(payload, secret);

        when(paymobConfig.getHmacSecret()).thenReturn(secret);

        // txn ID "101" not found by exact ID yet (placeholder lookup)
        when(paymentRepository.findByTransactionId("101")).thenReturn(Optional.empty());
        // Found by PAYMOB_ORDER_ placeholder
        when(paymentRepository.findByTransactionIdAndStatus("PAYMOB_ORDER_789", PaymentStatus.PENDING))
                .thenReturn(Optional.of(pendingPayment));

        // Act
        paymobService.processCallback(payload, realHmac);

        // Assert — payment updated
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(saved.getTransactionId()).isEqualTo("101");

        // Assert — order status synced to PAID
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-06 — processCallback: success=false → FAILED + Order CANCELLED
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-06: processCallback — success=false → FAILED + Order CANCELLED")
    void processCallback_failure_updatesPaymentFailedAndOrderCancelled() throws Exception {
        String secret  = "test-hmac-secret";
        PaymobCallbackPayload payload = buildPayload(false, false, false, 101L, 42L);
        String realHmac = computeExpectedHmac(payload, secret);

        when(paymobConfig.getHmacSecret()).thenReturn(secret);
        when(paymentRepository.findByTransactionId("101")).thenReturn(Optional.empty());
        when(paymentRepository.findByTransactionIdAndStatus("PAYMOB_ORDER_789", PaymentStatus.PENDING))
                .thenReturn(Optional.of(pendingPayment));

        paymobService.processCallback(payload, realHmac);

        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);

        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-07 — processCallback: voided → REFUNDED
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-07: processCallback — voided transaction → REFUNDED")
    void processCallback_voided_updatesPaymentRefunded() throws Exception {
        String secret  = "test-hmac-secret";
        // voided=true, success=false typically for voided txns
        PaymobCallbackPayload payload = buildPayload(false, true, false, 101L, 42L);
        String realHmac = computeExpectedHmac(payload, secret);

        when(paymobConfig.getHmacSecret()).thenReturn(secret);
        when(paymentRepository.findByTransactionId("101")).thenReturn(Optional.empty());
        when(paymentRepository.findByTransactionIdAndStatus("PAYMOB_ORDER_789", PaymentStatus.PENDING))
                .thenReturn(Optional.of(pendingPayment));

        paymobService.processCallback(payload, realHmac);

        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TC-08 — processCallback: idempotent (already COMPLETED)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-08: processCallback — txn already COMPLETED → idempotent, save() NOT called")
    void processCallback_alreadyCompleted_idempotentSkip() throws Exception {
        String secret = "test-hmac-secret";
        PaymobCallbackPayload payload = buildPayload(true, false, false, 101L, 42L);
        String realHmac = computeExpectedHmac(payload, secret);

        // Simulate payment already processed
        Payment alreadyCompleted = new Payment();
        alreadyCompleted.setId(1L);
        alreadyCompleted.setTransactionId("101");
        alreadyCompleted.setStatus(PaymentStatus.COMPLETED);
        alreadyCompleted.setOrder(testOrder);

        when(paymobConfig.getHmacSecret()).thenReturn(secret);
        when(paymentRepository.findByTransactionId("101")).thenReturn(Optional.of(alreadyCompleted));

        // Act
        paymobService.processCallback(payload, realHmac);

        // CRITICAL: No save calls — idempotent processing
        verify(paymentRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Stubs the non-sensitive PaymobConfig values */
    private void stubPaymobConfig() {
        when(paymobConfig.getBaseUrl()).thenReturn("https://accept.paymob.com/api");
        when(paymobConfig.getApiKey()).thenReturn("test-api-key");
        when(paymobConfig.getIntegrationId()).thenReturn(5310712);
        when(paymobConfig.getIframeId()).thenReturn(964127);
    }

    /**
     * Builds a PaymobCallbackPayload matching the test scenario.
     * The paymobOrderId is fixed at 789 to match the "PAYMOB_ORDER_789" placeholder.
     */
    private PaymobCallbackPayload buildPayload(boolean success, boolean voided,
                                               boolean refunded,
                                               Long txnId, Long packOrderId) {
        PaymobCallbackPayload payload = new PaymobCallbackPayload();
        payload.setType("TRANSACTION");

        PaymobCallbackPayload.TransactionObj txn = new PaymobCallbackPayload.TransactionObj();
        txn.setId(txnId);
        txn.setSuccess(success);
        txn.setVoided(voided);
        txn.setRefunded(refunded);
        txn.setAmountCents(35000L);
        txn.setCurrency("EGP");
        txn.setCreatedAt("2024-01-15T10:30:00.000000");
        txn.setIntegrationId(5310712L);
        txn.setOwner(1L);
        txn.setAuth(false);
        txn.setCapture(false);
        txn.setStandalonePayment(true);
        txn.set3dSecure(false);
        txn.setErrorOccured(!success && !voided);
        txn.setHasParentTransaction(false);
        txn.setPending(false);

        PaymobCallbackPayload.OrderRef orderRef = new PaymobCallbackPayload.OrderRef();
        orderRef.setId(789L);  // Paymob order ID — matches placeholder "PAYMOB_ORDER_789"
        orderRef.setMerchantOrderId(String.valueOf(packOrderId));
        txn.setOrder(orderRef);

        PaymobCallbackPayload.SourceData sd = new PaymobCallbackPayload.SourceData();
        sd.setPan("1234");
        sd.setType("card");
        sd.setSubType("MasterCard");
        txn.setSourceData(sd);

        payload.setObj(txn);
        return payload;
    }

    /**
     * Replicates the HMAC-SHA512 computation from PaymobService so we can
     * generate a valid HMAC for test payloads.
     *
     * Field concatenation order (must match PaymobService exactly):
     *   amount_cents, created_at, currency, error_occured, has_parent_transaction,
     *   id, integration_id, is_3d_secure, is_auth, is_capture, is_refunded,
     *   is_standalone_payment, is_voided, order.id, owner, pending,
     *   source_data.pan, source_data.sub_type, source_data.type, success
     */
    private String computeExpectedHmac(PaymobCallbackPayload payload, String secret) throws Exception {
        PaymobCallbackPayload.TransactionObj t = payload.getObj();

        String data = String.valueOf(t.getAmountCents())          // amount_cents
                + safeStr(t.getCreatedAt())                        // created_at
                + safeStr(t.getCurrency())                         // currency
                + t.isErrorOccured()                               // error_occured
                + t.isHasParentTransaction()                       // has_parent_transaction
                + t.getId()                                        // id
                + t.getIntegrationId()                             // integration_id
                + t.is3dSecure()                                   // is_3d_secure
                + t.isAuth()                                       // is_auth
                + t.isCapture()                                    // is_capture
                + t.isRefunded()                                   // is_refunded
                + t.isStandalonePayment()                          // is_standalone_payment
                + t.isVoided()                                     // is_voided
                + t.getOrder().getId()                             // order.id
                + t.getOwner()                                     // owner
                + t.isPending()                                    // pending
                + safeStr(t.getSourceData().getPan())              // source_data.pan
                + safeStr(t.getSourceData().getSubType())          // source_data.sub_type
                + safeStr(t.getSourceData().getType())             // source_data.type
                + t.isSuccess();                                   // success

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
        javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA512");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder(rawHmac.length * 2);
        for (byte b : rawHmac) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String safeStr(String value) {
        return (value != null && !value.isBlank()) ? value : "NA";
    }
}
