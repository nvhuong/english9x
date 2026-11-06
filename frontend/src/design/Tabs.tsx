import * as RadixTabs from '@radix-ui/react-tabs';
import type { ReactNode } from 'react';
import { cn } from './cn';

export interface TabItem {
  value: string;
  label: ReactNode;
  content: ReactNode;
  disabled?: boolean;
}

export interface TabsProps {
  items: readonly TabItem[];
  /** Accessible name of the tab list. */
  label: string;
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  className?: string;
}

/** Tabs with roving focus: arrows move, Home/End jump; the panel is tabbable. */
export function Tabs({ items, label, value, defaultValue, onValueChange, className }: TabsProps) {
  return (
    <RadixTabs.Root value={value} defaultValue={defaultValue ?? items[0]?.value} onValueChange={onValueChange} className={className}>
      <RadixTabs.List aria-label={label} className="flex gap-1 border-b border-line">
        {items.map((it) => (
          <RadixTabs.Trigger
            key={it.value}
            value={it.value}
            disabled={it.disabled}
            className={cn(
              '-mb-px min-h-10 border-b-2 border-transparent px-4 font-semibold text-muted',
              'data-[state=active]:border-accent data-[state=active]:text-ink disabled:opacity-50',
            )}
          >
            {it.label}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {items.map((it) => (
        <RadixTabs.Content key={it.value} value={it.value} className="pt-4">
          {it.content}
        </RadixTabs.Content>
      ))}
    </RadixTabs.Root>
  );
}
