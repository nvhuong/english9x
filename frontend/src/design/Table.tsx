import type { HTMLAttributes, ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export interface TableProps extends HTMLAttributes<HTMLTableElement> {
  /** Required: names the table for assistive technology (visible unless `captionHidden`). */
  caption: string;
  captionHidden?: boolean;
  /** Tighter rows for CMS / admin. */
  dense?: boolean;
  children: ReactNode;
}

/** Semantic table inside a keyboard-focusable scroll region (so wide tables stay reachable without a mouse). */
export function Table({ caption, captionHidden, dense, className, children, ...rest }: TableProps) {
  return (
    // The scroll region is focusable on purpose (axe: scrollable-region-focusable).
    <div role="region" aria-label={caption} tabIndex={0} className="overflow-x-auto rounded-md border border-line bg-surface">
      <table data-dense={dense || undefined} className={cn('w-full border-collapse text-left text-ink', dense ? 'text-sm' : 'text-base', className)} {...rest}>
        <caption className={captionHidden ? 'sr-only' : 'px-4 py-3 text-left font-serif text-lg font-semibold'}>{caption}</caption>
        {children}
      </table>
    </div>
  );
}

export const TableHead = ({ className, ...p }: HTMLAttributes<HTMLTableSectionElement>) => <thead className={cn('bg-sunken', className)} {...p} />;
export const TableBody = (p: HTMLAttributes<HTMLTableSectionElement>) => <tbody {...p} />;
export const TableRow = ({ className, ...p }: HTMLAttributes<HTMLTableRowElement>) => <tr className={cn('border-t border-line', className)} {...p} />;
export const TableCell = ({ className, ...p }: TdHTMLAttributes<HTMLTableCellElement>) => (
  <td className={cn('px-4 py-2 align-top [table[data-dense]_&]:py-1', className)} {...p} />
);

export type SortDirection = 'ascending' | 'descending';
export interface TableHeaderCellProps extends Omit<ThHTMLAttributes<HTMLTableCellElement>, 'onClick'> {
  /** Current sort of this column; undefined = not sorted by it. Provide `onSort` to make it sortable. */
  sort?: SortDirection;
  onSort?: () => void;
}

export function TableHeaderCell({ sort, onSort, children, className, ...p }: TableHeaderCellProps) {
  const { t } = useTranslation();
  return (
    <th scope="col" aria-sort={onSort ? (sort ?? 'none') : undefined} className={cn('px-4 py-2 font-semibold', className)} {...p}>
      {onSort ? (
        <button type="button" onClick={onSort} className="inline-flex min-h-8 items-center gap-1 font-semibold" title={t('table.sortBy', { column: typeof children === 'string' ? children : '' })}>
          {children}
          <span aria-hidden="true">{sort === 'ascending' ? '▲' : sort === 'descending' ? '▼' : '↕'}</span>
        </button>
      ) : (
        children
      )}
    </th>
  );
}
