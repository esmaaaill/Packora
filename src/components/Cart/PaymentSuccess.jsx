import React, { useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { CheckCircle2 } from 'lucide-react';
import './PaymentResult.css';

/**
 * PaymentSuccess — shown after a successful Paymob payment.
 *
 * Paymob's GET callback redirects here as:
 *   /payment-success?orderId=<our_order_id>
 *
 * The CartContext is cleared when the user places the order, so this
 * page can simply show a confirmation and link back to the homepage.
 */
export default function PaymentSuccess() {
  const [params]  = useSearchParams();
  const navigate  = useNavigate();
  const orderId   = params.get('orderId');

  // Auto-redirect to homepage after 8 seconds
  useEffect(() => {
    const timer = setTimeout(() => navigate('/HomePage'), 8000);
    return () => clearTimeout(timer);
  }, [navigate]);

  return (
    <div className="pay-result-page">
      <div className="pay-result-card pay-result-card--success">
        <div className="pay-result-icon pay-result-icon--success">
          <CheckCircle2 size={64} strokeWidth={1.5} />
        </div>

        <h1 className="pay-result-title">Payment Successful! 🎉</h1>
        <p className="pay-result-subtitle">
          Your order has been confirmed and is now being processed.
        </p>

        {orderId && (
          <div className="pay-result-order-badge">
            Order Reference: <strong>#{orderId}</strong>
          </div>
        )}

        <p className="pay-result-note">
          You will receive a confirmation email shortly. We'll notify you when
          your packaging is on its way.
        </p>

        <p className="pay-result-redirect-note">
          Redirecting to homepage in 8 seconds…
        </p>

        <div className="pay-result-actions">
          <button
            id="pay-success-home-btn"
            className="pay-result-btn pay-result-btn--primary"
            onClick={() => navigate('/HomePage')}
          >
            Back to Home
          </button>
          <button
            id="pay-success-track-btn"
            className="pay-result-btn pay-result-btn--secondary"
            onClick={() => navigate('/Track')}
          >
            Track Order
          </button>
        </div>
      </div>
    </div>
  );
}
