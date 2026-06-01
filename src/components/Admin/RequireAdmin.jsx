import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';

/**
 * Route guard — requires ADMIN role (from JWT) to access child routes.
 * Redirects to / if user is not an admin.
 */
export default function RequireAdmin({ children }) {
  const { isLoggedIn, isAdmin } = useAuth();

  // Not logged in at all → go to login
  if (!isLoggedIn) return <Navigate to="/login" replace />;

  // Logged in but not admin → go to home
  if (!isAdmin) return <Navigate to="/HomePage" replace />;

  return children;
}
