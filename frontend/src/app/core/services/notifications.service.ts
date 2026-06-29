import { computed, Injectable, signal } from '@angular/core';
import { Notification, NotificationFeed } from '../api/models';
import { environment } from '../../../environments/environment';

/**
 * Notifications feed.
 *
 * `/notifications*` is Phase 6 (not built yet — see docs/integration/STATUS.md).
 * In the REAL app (deployed/prod) the bell therefore shows the true state: empty.
 * Sample data is seeded ONLY in local dev (`useMockMe`) so the bell/panel can be
 * worked on without a backend — it never reaches the deployed demo. When Phase 6
 * lands, swap these bodies to hit `GET /notifications`, `/{id}/read`, `/read-all`;
 * the public signal surface stays identical, so the bell component won't change.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly _feed = signal<NotificationFeed>(EMPTY_FEED);
  readonly feed = this._feed.asReadonly();
  readonly unreadCount = computed(() => this._feed().unreadCount);
  readonly items = computed(() => this._feed().items);

  load(): void {
    // No real endpoint yet → empty in prod; seeded only for local dev UI work.
    this._feed.set(environment.useMockMe ? seedFeed() : EMPTY_FEED);
  }

  markRead(id: string): void {
    this._feed.update((feed) => {
      const items = feed.items.map((n) => (n.id === id ? { ...n, read: true } : n));
      return { ...feed, items, unreadCount: items.filter((n) => !n.read).length };
    });
  }

  markAllRead(): void {
    this._feed.update((feed) => ({
      ...feed,
      items: feed.items.map((n) => ({ ...n, read: true })),
      unreadCount: 0,
    }));
  }
}

const EMPTY_FEED: NotificationFeed = { items: [], unreadCount: 0, nextCursor: null };

function seedFeed(): NotificationFeed {
  const items: Notification[] = [
    {
      id: 'n-1',
      type: 'access.approved',
      title: 'Access approved',
      body: 'Your request for AWS — Production was approved.',
      resourceRef: 'access:req-1024',
      read: false,
      createdAt: '2026-06-28T14:02:00Z',
    },
    {
      id: 'n-2',
      type: 'review.needed',
      title: 'A request needs your review',
      body: 'Workday access requested by jordan@corp.example.',
      resourceRef: 'access:req-1031',
      read: false,
      createdAt: '2026-06-28T13:40:00Z',
    },
    {
      id: 'n-3',
      type: 'onboarding.active',
      title: 'Application is active',
      body: 'billing-portal finished provisioning and is now active.',
      resourceRef: 'onboarding:app-77',
      read: true,
      createdAt: '2026-06-27T17:15:00Z',
    },
  ];
  return { items, unreadCount: items.filter((n) => !n.read).length, nextCursor: null };
}
