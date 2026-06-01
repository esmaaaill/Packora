import React, { createContext, useContext, useCallback, useEffect, useMemo, useState } from 'react';
import { cartApi } from '../utils/api';
import { useAuth } from './AuthContext';

const CartContext = createContext(null);

export function CartProvider({ children }) {
  const { isLoggedIn } = useAuth();

  // ── Cart items (synced with backend) ──────────────────────────
  const [cartItems, setCartItems] = useState([]);
  const [cartLoading, setCartLoading] = useState(false);
  const [cartError, setCartError] = useState(null);

  // ── Fetch cart from backend on login ──────────────────────────
  useEffect(() => {
    if (!isLoggedIn) {
      setCartItems([]);
      return;
    }
    let cancelled = false;
    setCartLoading(true);
    setCartError(null);

    cartApi
      .getCart()
      .then((cart) => {
        if (!cancelled) setCartItems(cart.items);
      })
      .catch((err) => {
        if (!cancelled) setCartError(err.message || 'Failed to load cart.');
      })
      .finally(() => {
        if (!cancelled) setCartLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [isLoggedIn]);

  // ── Helpers: sync from CartResponse ──────────────────────────
  const syncCart = useCallback((cart) => {
    setCartItems(cart?.items || []);
  }, []);

  // ── addToCart: POST /api/cart/items ──────────────────────────
  const addToCart = useCallback(async (item) => {
    try {
      const cart = await cartApi.addItem(
        item.productId,
        item.quantity || 1,
        item.size || null,
        item.material || null,
        item.customBoxConfigId || null
      );
      syncCart(cart);
    } catch (err) {
      console.error('addToCart error:', err);
      throw err;
    }
  }, [syncCart]);

  // ── removeFromCart: DELETE /api/cart/items/{itemId} ──────────
  // Now accepts backend item.id (number), NOT productId+size+material
  const removeFromCart = useCallback(async (itemId) => {
    try {
      const cart = await cartApi.removeItem(itemId);
      syncCart(cart);
    } catch (err) {
      console.error('removeFromCart error:', err);
      throw err;
    }
  }, [syncCart]);

  // ── updateQuantity: PUT /api/cart/items/{itemId}?quantity={qty}
  // Now accepts backend item.id (number) and qty
  const updateQuantity = useCallback(async (itemId, quantity) => {
    if (quantity < 1) return;
    try {
      const cart = await cartApi.updateItem(itemId, quantity);
      syncCart(cart);
    } catch (err) {
      console.error('updateQuantity error:', err);
      throw err;
    }
  }, [syncCart]);

  // ── clearCart: DELETE /api/cart ───────────────────────────────
  const clearCart = useCallback(async () => {
    try {
      await cartApi.clearCart();
      setCartItems([]);
      // Reset checkout state after successful order
      const emptyAddress = { fullName: '', company: '', street: '', city: '', state: '', zip: '', phone: '', email: '' };
      setShippingAddress(emptyAddress);
      setCheckoutStep('shipping');
      setCurrentOrderId(null);
      try {
        localStorage.removeItem('packora_shipping_address');
        localStorage.removeItem('packora_checkout_step');
        localStorage.removeItem('packora_current_order_id');
      } catch { }
    } catch (err) {
      console.error('clearCart error:', err);
      throw err;
    }
  }, []);

  // ── Checkout / Shipping state (persisted to localStorage) ────────
  const [shippingAddress, setShippingAddress] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem('packora_shipping_address')) || {
        fullName: '', company: '', street: '', city: '', state: '', zip: '', phone: '', email: '',
      };
    } catch {
      return { fullName: '', company: '', street: '', city: '', state: '', zip: '', phone: '', email: '' };
    }
  });

  // ── Payment state (persisted to localStorage) ────────────────
  const [paymentMethods, setPaymentMethods] = useState(() => {
    try { return JSON.parse(localStorage.getItem('packora_payment_methods')) || []; } catch { return []; }
  });
  const [selectedPaymentId, setSelectedPaymentId] = useState(() => {
    try { return localStorage.getItem('packora_selected_payment') || null; } catch { return null; }
  });
  const [checkoutStep, setCheckoutStep] = useState(() => {
    try { return localStorage.getItem('packora_checkout_step') || 'shipping'; } catch { return 'shipping'; }
  });
  const [iframeUrl, setIframeUrl] = useState(null);
  const [currentOrderId, setCurrentOrderId] = useState(() => {
    try {
      const v = localStorage.getItem('packora_current_order_id');
      return v ? Number(v) : null;
    } catch { return null; }
  });

  // Sync payment methods to localStorage on change
  useEffect(() => {
    try { localStorage.setItem('packora_payment_methods', JSON.stringify(paymentMethods)); } catch { }
  }, [paymentMethods]);

  useEffect(() => {
    try {
      if (selectedPaymentId) localStorage.setItem('packora_selected_payment', selectedPaymentId);
      else localStorage.removeItem('packora_selected_payment');
    } catch { }
  }, [selectedPaymentId]);

  // Sync checkout state to localStorage on change
  useEffect(() => {
    try { localStorage.setItem('packora_shipping_address', JSON.stringify(shippingAddress)); } catch { }
  }, [shippingAddress]);

  useEffect(() => {
    try { localStorage.setItem('packora_checkout_step', checkoutStep); } catch { }
  }, [checkoutStep]);

  useEffect(() => {
    try {
      if (currentOrderId != null) localStorage.setItem('packora_current_order_id', String(currentOrderId));
      else localStorage.removeItem('packora_current_order_id');
    } catch { }
  }, [currentOrderId]);

  // ── Bulk order state ──────────────────────────────────────────
  const [bulkExcelData, setBulkExcelData] = useState([]);
  const [bulkWarehouseData, setBulkWarehouseData] = useState({
    warehouseName: '',
    addressLine: '',
    city: '',
    postalCode: '',
    contactNumber: '',
  });

  const addPaymentMethod = (card) => {
    const id = String(Date.now());
    setPaymentMethods((prev) => {
      const newCard = { ...card, id, isDefault: prev.length === 0 };
      return [...prev, newCard];
    });
    setSelectedPaymentId(id);
  };

  const removePaymentMethod = (id) => {
    setPaymentMethods((prev) => {
      const next = prev.filter((c) => c.id !== id);
      if (selectedPaymentId === id) {
        setSelectedPaymentId(next[0]?.id || null);
      }
      if (next.length && !next.some((c) => c.isDefault)) {
        return next.map((c, i) => ({ ...c, isDefault: i === 0 }));
      }
      return next;
    });
  };

  const setDefaultPaymentMethod = (id) => {
    setPaymentMethods((prev) => prev.map((c) => ({ ...c, isDefault: c.id === id })));
    setSelectedPaymentId(id);
  };

  const value = useMemo(
    () => ({
      cartItems,
      cartLoading,
      cartError,
      addToCart,
      removeFromCart,
      updateQuantity,
      clearCart,
      shippingAddress,
      setShippingAddress,
      paymentMethods,
      selectedPaymentId,
      setSelectedPaymentId,
      addPaymentMethod,
      removePaymentMethod,
      setDefaultPaymentMethod,
      checkoutStep,
      setCheckoutStep,
      iframeUrl,
      setIframeUrl,
      currentOrderId,
      setCurrentOrderId,
      bulkExcelData,
      setBulkExcelData,
      bulkWarehouseData,
      setBulkWarehouseData,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      cartItems,
      cartLoading,
      cartError,
      addToCart,
      removeFromCart,
      updateQuantity,
      clearCart,
      shippingAddress,
      paymentMethods,
      selectedPaymentId,
      checkoutStep,
      iframeUrl,
      currentOrderId,
      bulkExcelData,
      bulkWarehouseData,
    ]
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart() {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used within CartProvider');
  return ctx;
}
