import React from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { XCircle } from 'lucide-react';
import './PaymentResult.css';

/**
 * PaymentFailed — shown after a failed or cancelled Paymob payment.
 *
 * Paymob's GET callback redirects here as:
 *   /payment-failed?orderId=<our_order_id>
 *
 * The user can retry by going back to checkout.
 */
export default function PaymentFailed() {
  const [params]  = useSearchParams();
  const navigate  = useNavigate();
  const orderId   = params.get('orderId');

  return (
    <div className="pay-result-page">
      <div className="pay-result-card pay-result-card--failed">
        <div className="pay-result-icon pay-result-icon--failed">
          <XCircle size={64} strokeWidth={1.5} />
        </div>

        <h1 className="pay-result-title">Payment Failed</h1>
        <p className="pay-result-subtitle">
          Unfortunately, your payment could not be processed. No charges were made.
        </p>

        {orderId && (
          <div className="pay-result-order-badge pay-result-order-badge--failed">
            Order Reference: <strong>#{orderId}</strong>
          </div>
        )}

        <p className="pay-result-note">
          Please check your card details and try again, or use a different payment method.
          If the issue persists, contact our support team.
        </p>

        <div className="pay-result-actions">
          <button
            id="pay-failed-retry-btn"
            className="pay-result-btn pay-result-btn--primary"
            onClick={() => navigate('/Cart/checkout')}
          >
            Try Again
          </button>
          <button
            id="pay-failed-support-btn"
            className="pay-result-btn pay-result-btn--secondary"
            onClick={() => navigate('/Support')}
          >
            Contact Support
          </button>
        </div>
      </div>
    </div>
  );
}
