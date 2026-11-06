import { useEffect } from 'react';
import { bootstrapSession, syncLogoutAcrossTabs } from './session';

/** Restores a stored session once when the application starts, and follows sign-outs made in other tabs. */
export function AuthBootstrap() {
  useEffect(() => {
    void bootstrapSession();
    return syncLogoutAcrossTabs();
  }, []);
  return null;
}
