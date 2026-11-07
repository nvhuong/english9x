import type { Meta, StoryObj } from '@storybook/react-vite';
import { NotificationListView, type ListItem } from './NotificationListView';
import { PreferencesView } from './PreferencesView';
import { TelegramLinkView } from './TelegramLinkView';

const noop = () => {};
const items: ListItem[] = [
  { id: '1', type: 'REVIEW_REQUESTED', data: { contentType: 'question', examCode: 'IELTS' }, createdAt: '2026-11-07T03:00:00Z', read: false },
  { id: '2', type: 'CONTENT_PUBLISHED', data: { contentType: 'test', examCode: 'TOEIC' }, createdAt: '2026-11-06T09:30:00Z', read: true },
  { id: '3', type: 'ORDER_REJECTED', data: { reason: 'Sai nội dung chuyển khoản' }, createdAt: '2026-11-05T09:30:00Z', read: true },
];

const meta = { title: 'Features/Notifications', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Inbox: S = { render: () => <NotificationListView items={items} unread={1} hasMore onMarkRead={noop} onMarkAll={noop} onMore={noop} /> };
export const InboxEmpty: S = { render: () => <NotificationListView items={[]} unread={0} hasMore={false} onMarkRead={noop} onMarkAll={noop} onMore={noop} /> };

const events = [
  { type: 'REVIEW_REQUESTED', audience: 'REVIEWER', email: true, telegram: false },
  { type: 'CONTENT_PUBLISHED', audience: 'AUTHOR', email: false, telegram: true },
  { type: 'ORDER_CONFIRMED', audience: 'LEARNER', email: true, telegram: true },
  { type: 'AI_GRADING_FAILED', audience: 'SYS_ADMIN', email: true, telegram: true },
];
export const Preferences: S = { render: () => <PreferencesView events={events} telegramLinked onToggle={noop} /> };
export const PreferencesTelegramNotLinked: S = { render: () => <PreferencesView events={events} telegramLinked={false} onToggle={noop} /> };

export const TelegramNotLinked: S = { render: () => <TelegramLinkView enabled linked={false} onCreate={noop} onUnlink={noop} onCheck={noop} /> };
export const TelegramWithLink: S = {
  render: () => (
    <TelegramLinkView enabled linked={false} link={{ deepLink: 'https://t.me/english9x_bot?start=abc', expiresAt: '2026-11-07T04:00:00Z' }} onCreate={noop} onUnlink={noop} onCheck={noop} />
  ),
};
export const TelegramLinked: S = { render: () => <TelegramLinkView enabled linked onCreate={noop} onUnlink={noop} onCheck={noop} /> };
export const TelegramUnavailable: S = { render: () => <TelegramLinkView enabled={false} linked={false} onCreate={noop} onUnlink={noop} onCheck={noop} /> };
