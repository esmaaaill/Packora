import React, { useState, useCallback } from 'react';
import { Shield, Loader2, CreditCard, AlertCircle } from 'lucide-react';
import { useCart } from '../../context/CartContext';
import './PaymobCheckout.css';

const TAX_RATE = 0.08;
const API_BASE  = process.env.REACT_APP_API_URL || 'http://localhost:8080';

/**
 * PaymobCheckout — replaces the mock Payment step.
 *
 * Flow:
 *  1. User is on the "payment" checkout step (arrived from ShippingAddress).
 *  2. "Pay Now" button calls our backend POST /api/payment/initiate with
 *     the orderId, amount, and billing data derived from the shipping form.
 *  3. Backend returns an iframeUrl.
 *  4. We render the Paymob iframe inside the component.
 *  5. After the user completes payment, Paymob redirects the browser to
 *     GET /api/payment/callback?success=true|false → which redirects to
 *     /payment-success or /payment-failed.
 */
export default function PaymobCheckout() {
  const {
    cartItems,
    shippingAddress,
    setCheckoutStep,
  } = useCart();

  const [iframeUrl,  setIframeUrl]  = useState(null);  // set after initiate succeeds
  const [isLoading,  setIsLoading]  = useState(false);
  const [error,      setError]      = useState(null);

  const subtotal = cartItems.reduce((sum, i) => sum + i.price * i.quantity, 0);
  const tax      = subtotal * TAX_RATE;
  const total    = subtotal + tax;

  /**
   * Calls our backend to start the Paymob 3-step flow.
   * Uses the shippingAddress already saved in CartContext as billing data.
   *
   * NOTE: orderId must come from a real backend-created order.
   * For now we use a placeholder of 1 — replace this with your actual
   * order creation flow (POST /api/orders → get back orderId → then call this).
   */
  const handlePayNow = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    // Build billing data from the shipping address form
    const nameParts = (shippingAddress.fullName || '').split(' ');
    const firstName = nameParts[0] || 'NA';
    const lastName  = nameParts.slice(1).join(' ') || 'NA';

    const body = {
      orderId: 1,                        // ← Replace with real orderId from your order-creation step
      amount: parseFloat(total.toFixed(2)),
      billingData: {
        first_name:      firstName,
        last_name:       lastName,
        email:           'customer@example.com', // ← Replace with logged-in user's email
        phone_number:    shippingAddress.phone || 'NA',
        street:          shippingAddress.street || 'NA',
        city:            shippingAddress.city || 'NA',
        state:           shippingAddress.state || 'NA',
        postal_code:     shippingAddress.zip || 'NA',
        country:         'EG',
        apartment:       'NA',
        floor:           'NA',
        building:        'NA',
        shipping_method: 'NA',
      },
    };

    try {
      const token = localStorage.getItem('token'); // JWT from your AuthContext
      const res = await fetch(`${API_BASE}/api/payment/initiate`, {
        method:  'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || `Server error: ${res.status}`);
      }

      const data = await res.json();
      if (!data.iframeUrl) throw new Error('No iframe URL returned from server');

      setIframeUrl(data.iframeUrl);

    } catch (err) {
      setError(err.message || 'Payment initialization failed. Please try again.');
    } finally {
      setIsLoading(false);
    }
  }, [shippingAddress, total]);

  // ── Render: iframe loaded ──────────────────────────────────────────────
  if (iframeUrl) {
    return (
      <div className="paymob-iframe-wrapper">
        <div className="paymob-iframe-header">
          <Shield size={18} className="paymob-iframe-shield" />
          <span>Secure Payment via Paymob</span>
        </div>
        <iframe
          id="paymob-payment-iframe"
          src={iframeUrl}
          title="Paymob Secure Payment"
          className="paymob-iframe"
          allow="payment"
          sandbox="allow-forms allow-scripts allow-same-origin allow-top-navigation"
        />
        <p className="paymob-iframe-note">
          Complete your payment above. You will be redirected automatically when done.
        </p>
      </div>
    );
  }

  // ── Render: payment initiation panel ──────────────────────────────────
  return (
    <>
      <div className="paymob-panel">
        <div className="paymob-header">
          <CreditCard size={20} />
          <h2 className="paymob-title">Secure Online Payment</h2>
        </div>

        {/* Order summary before paying */}
        <div className="paymob-summary-card">
          <div className="paymob-summary-rows">
            {cartItems.map((item) => (
              <div key={item.productId} className="paymob-summary-row">
                <span>{item.quantity}× {item.name}</span>
                <span>EGP {(item.price * item.quantity).toFixed(2)}</span>
              </div>
            ))}
          </div>
          <div className="paymob-summary-divider" />
          <div className="paymob-summary-row">
            <span>Subtotal</span>
            <span>EGP {subtotal.toFixed(2)}</span>
          </div>
          <div className="paymob-summary-row">
            <span>Tax (8%)</span>
            <span>EGP {tax.toFixed(2)}</span>
          </div>
          <div className="paymob-summary-total">
            <span>Total</span>
            <span>EGP {total.toFixed(2)}</span>
          </div>
        </div>

        {/* Error message */}
        {error && (
          <div className="paymob-error" role="alert">
            <AlertCircle size={18} />
            <span>{error}</span>
          </div>
        )}

        {/* Security badge */}
        <div className="paymob-secure-badge">
          <Shield size={16} />
          <span>
            Your payment is processed securely by <strong>Paymob</strong>.
            We never store your card details.
          </span>
        </div>

        {/* Actions */}
        <div className="paymob-actions">
          <button
            id="paymob-back-btn"
            type="button"
            className="paymob-back-btn"
            onClick={() => setCheckoutStep('shipping')}
            disabled={isLoading}
          >
            Back
          </button>
          <button
            id="paymob-pay-btn"
            type="button"
            className="paymob-pay-btn"
            onClick={handlePayNow}
            disabled={isLoading || cartItems.length === 0}
          >
            {isLoading ? (
              <>
                <Loader2 size={18} className="paymob-spin" />
                Connecting to Paymob…
              </>
            ) : (
              <>
                <Shield size={18} />
                Pay EGP {total.toFixed(2)} Securely
              </>
            )}
          </button>
        </div>
      </div>

      {/* Order summary aside */}
      <aside className="paymob-aside">
        <h2 className="paymob-aside-title">Order Summary</h2>
        <div className="paymob-aside-rows">
          <div className="paymob-aside-row"><span>Subtotal</span><span>EGP {subtotal.toFixed(2)}</span></div>
          <div className="paymob-aside-row"><span>Shipping</span><span>FREE</span></div>
          <div className="paymob-aside-row"><span>Tax (8%)</span><span>EGP {tax.toFixed(2)}</span></div>
        </div>
        <div className="paymob-aside-total"><span>Total</span><span>EGP {total.toFixed(2)}</span></div>
        <p className="paymob-aside-items">
          {cartItems.map((i) => `${i.quantity}× ${i.name}`).join(', ')}
        </p>
      </aside>
    </>
  );
}
