import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { useAuthStore } from '../../app/stores/auth';
import { ctxKey, useContextStore } from '../../app/stores/context';

export type OrderView = components['schemas']['OrderView'];
export type AdminOrderView = components['schemas']['AdminOrderView'];
export type PaymentAccountView = components['schemas']['PaymentAccountView'];

/** One of the person's own orders. While it waits for payment it is re-read every 15 seconds: the administrator may confirm at any time. */
export function useOrder(code: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'billing', 'order', code),
    retry: false,
    refetchInterval: (q) => (q.state.data?.status === 'PENDING_PAYMENT' ? 15_000 : false),
    queryFn: async () => (await api.GET('/api/orders/{code}', { params: { path: { code } } })).data as OrderView,
  });
}

/**
 * The QR picture of an order. It is fetched with the person's token (an image element cannot send one) and shown from a local object URL,
 * which is released when the picture changes or the page closes.
 */
export function useQrImage(url: string | null | undefined) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!url) return;
    let revoked = false;
    let objectUrl: string | null = null;
    const token = useAuthStore.getState().accessToken;
    void fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then(async (res) => {
        if (!res.ok) throw new Error(String(res.status));
        const blob = await res.blob();
        if (revoked) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
        setFailed(false);
      })
      .catch(() => {
        if (!revoked) setFailed(true);
      });
    return () => {
      revoked = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url]);
  return { src: url ? src : null, failed: url ? failed : false };
}

export function useAdminOrders(status: string, q: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'billing', 'admin-orders', status, q),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/orders', { params: { query: { status: status || undefined, q: q.trim() || undefined } } })).data as AdminOrderView[],
  });
}

export function useDecideOrder() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const done = () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'billing', 'admin-orders') });
  const confirm = useMutation({
    mutationFn: async (v: { code: string; bankRef: string }) =>
      (await api.POST('/api/admin/orders/{code}/confirm', { params: { path: { code: v.code } }, body: { bankRef: v.bankRef.trim() || undefined } })).data,
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: async (v: { code: string; reason: string }) =>
      (await api.POST('/api/admin/orders/{code}/reject', { params: { path: { code: v.code } }, body: { reason: v.reason } })).data,
    onSuccess: done,
  });
  return { confirm, reject };
}

export function usePaymentAccount() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'billing', 'payment-account'),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/payment-account')).data as PaymentAccountView,
  });
}

export function useSavePaymentAccount() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (v: { bankBin: string; accountNo: string; accountName: string }) => (await api.PUT('/api/admin/payment-account', { body: v })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'billing', 'payment-account') }),
  });
}
