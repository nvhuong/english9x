import * as RadixToast from '@radix-ui/react-toast';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export type ToastTone = 'info' | 'success' | 'warning' | 'danger';
export interface ToastOptions {
  title: string;
  description?: string;
  tone?: ToastTone;
}
interface ToastItem extends ToastOptions {
  id: number;
}

const ToastContext = createContext<{ toast: (o: ToastOptions) => void } | null>(null);

const tones: Record<ToastTone, string> = {
  info: 'bg-info-bg text-info border-info',
  success: 'bg-success-bg text-success border-success',
  warning: 'bg-warning-bg text-warning border-warning',
  danger: 'bg-danger-bg text-danger border-danger',
};

/** Mount once near the app root. Errors stay until dismissed (WCAG 2.2.1); others close after 6 s. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [items, setItems] = useState<ToastItem[]>([]);
  const toast = useCallback((o: ToastOptions) => setItems((cur) => [...cur, { ...o, id: Date.now() + Math.random() }]), []);
  const value = useMemo(() => ({ toast }), [toast]);
  return (
    <ToastContext.Provider value={value}>
      <RadixToast.Provider label={t('common.notifications')} swipeDirection="right">
        {children}
        {items.map((it) => (
          <RadixToast.Root
            key={it.id}
            duration={it.tone === 'danger' ? Infinity : 6000}
            onOpenChange={(open) => !open && setItems((cur) => cur.filter((x) => x.id !== it.id))}
            className={cn('flex items-start gap-3 rounded-md border p-4 shadow-card', tones[it.tone ?? 'info'])}
          >
            <div className="flex-1">
              <RadixToast.Title className="font-semibold">{it.title}</RadixToast.Title>
              {it.description && <RadixToast.Description className="mt-1 text-sm">{it.description}</RadixToast.Description>}
            </div>
            <RadixToast.Close aria-label={t('common.dismiss')} className="min-h-8 min-w-8 rounded-sm font-semibold">
              <span aria-hidden="true">✕</span>
            </RadixToast.Close>
          </RadixToast.Root>
        ))}
        <RadixToast.Viewport label={`${t('common.notifications')} ({hotkey})`} className="fixed bottom-4 right-4 z-[60] flex w-[min(92vw,24rem)] flex-col gap-2 outline-none" />
      </RadixToast.Provider>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>');
  return ctx;
}
