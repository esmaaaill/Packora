import React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { CheckCircle, Package, ShoppingBag } from 'lucide-react';
import './PaymentResult.css';

/**
 * PaymentSuccess — shown after a successful Paymob transaction.
 *
 * Paymob redirects the browser to:
 *   GET /api/payment/callback?success=true&id=<txnId>
 *
 * Our backend then redirects to:
 *   http://localhost:3000/payment/success?txn=<txnId>
 *
 * This component reads the ?txn= param for display purposes.
 * The page stays visible until the user manually navigates away.
 */
export default function PaymentSuccess() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const txnId = params.get('txn');

  return (
    <div className="payment-result-page payment-result-success">
      <div className="payment-result-card">
        <div className="payment-result-icon success">
          <CheckCircle size={64} />
        </div>
        <h1 className="payment-result-title">Payment Successful!</h1>
        <p className="payment-result-msg">
          Your order has been placed and payment confirmed.
          Our team will start processing it shortly.
        </p>
        {txnId && (
          <p className="payment-result-ref">
            Transaction Reference: <strong>{txnId}</strong>
          </p>
        )}
        <div className="payment-result-actions" style={{ marginTop: '2rem', display: 'flex', gap: '1rem', flexWrap: 'wrap', justifyContent: 'center' }}>
          <button
            className="payment-result-btn"
            onClick={() => navigate('/Track')}
          >
            <Package size={18} /> View My Orders
          </button>
          <button
            className="payment-result-btn secondary"
            onClick={() => navigate('/Catalog')}
          >
            <ShoppingBag size={18} /> Continue Shopping
          </button>
        </div>
      </div>
    </div>
  );
}
