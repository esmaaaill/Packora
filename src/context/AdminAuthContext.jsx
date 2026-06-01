import React, { createContext, useCallback, useContext, useMemo } from 'react';
import { useAuth } from './AuthContext';

const AdminAuthContext = createContext(null);

/**
 * AdminAuthProvider — derives admin status from the JWT role
 * stored in AuthContext. No more hardcoded email or sessionStorage flag.
 */
export function AdminAuthProvider({ children }) {
  const { isAdmin: isAdminRole, logout } = useAuth();

  // Admin status is derived from the JWT role — not a manual flag
  const isAdmin = isAdminRole;

  // loginAdmin is now a no-op — admin status comes from the JWT
  const loginAdmin = useCallback(() => {
    // Admin login happens through the normal login flow.
    // The role is set by the server in the JWT response.
  }, []);

  const logoutAdmin = useCallback(() => {
    // Log out the whole session
    logout();
  }, [logout]);

  const value = useMemo(
    () => ({ isAdmin, loginAdmin, logoutAdmin }),
    [isAdmin, loginAdmin, logoutAdmin]
  );

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>;
}

export function useAdminAuth() {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) throw new Error('useAdminAuth must be used within AdminAuthProvider');
  return ctx;
}
