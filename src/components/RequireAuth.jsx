import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

/**
 * Route guard — requires a valid JWT token to access child routes.
 * Redirects to /login if not authenticated.
 */
export default function RequireAuth({ children }) {
  const { isLoggedIn } = useAuth();
  
  if (!isLoggedIn) {
    return <Navigate to="/login" replace />;
  }
  
  return children ? children : <Outlet />;
}
