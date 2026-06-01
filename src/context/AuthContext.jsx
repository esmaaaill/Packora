import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';

const STORAGE_KEY = 'packora_user_auth';

/**
 * Derives a display name from an email address.
 * e.g. "john.doe@mail.com" → "John Doe"
 */
export function emailToDisplayName(email) {
  const local = String(email || '').trim().split('@')[0] || 'User';
  const words = local.split(/[._-]+/).filter(Boolean);
  const titled = words
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ');
  return titled || local;
}

/**
 * Reads the stored auth data from localStorage.
 * Returns { token, id, username, email, role } or null.
 */
function readStoredUser() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    // Must have at minimum a token and email to be valid
    if (parsed && typeof parsed.token === 'string' && typeof parsed.email === 'string') {
      return parsed;
    }
  } catch {
    /* ignore corrupt data */
  }
  return null;
}

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => readStoredUser());

  /**
   * Log in by storing the full JWT response data.
   *
   * @param {Object} authData - The response from POST /api/auth/login
   * @param {string} authData.token - JWT token
   * @param {number} authData.id - User ID
   * @param {string} authData.username - Username
   * @param {string} authData.email - Email
   * @param {string} authData.role - Role (e.g. "ROLE_BUSINESS_OWNER", "ROLE_ADMIN")
   */
  const login = useCallback((authData) => {
    const userData = {
      token: authData.token,
      id: authData.id,
      username: authData.username,
      email: authData.email,
      role: authData.role,
      displayName: authData.username || emailToDisplayName(authData.email),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(userData));
    setUser(userData);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setUser(null);
  }, []);

  /**
   * Returns the stored JWT token, or null if not logged in.
   */
  const getToken = useCallback(() => {
    return user?.token || null;
  }, [user]);

  /**
   * Check if the current user has a specific role.
   * e.g. hasRole('ADMIN') checks for 'ROLE_ADMIN'
   */
  const hasRole = useCallback((roleName) => {
    if (!user?.role) return false;
    // Support both "ADMIN" and "ROLE_ADMIN" input
    const normalized = roleName.startsWith('ROLE_') ? roleName : `ROLE_${roleName}`;
    return user.role === normalized;
  }, [user]);

  const value = useMemo(
    () => ({
      user,
      isLoggedIn: !!user?.token,
      isAdmin: user?.role === 'ROLE_ADMIN',
      login,
      logout,
      getToken,
      hasRole,
    }),
    [user, login, logout, getToken, hasRole]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
