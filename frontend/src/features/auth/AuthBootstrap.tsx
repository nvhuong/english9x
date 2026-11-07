import { useEffect } from 'react';
import { bootstrapSession, syncSessionAcrossTabs } from './session';

/** Restores a stored session once when the application starts, and follows sign-ins and sign-outs made in other tabs. */
export function AuthBootstrap() {
  useEffect(() => {
    void bootstrapSession();
    return syncSessionAcrossTabs();
  }, []);
  return null;
}
