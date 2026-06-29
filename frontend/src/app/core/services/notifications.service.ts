import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { Notification, NotificationFeed } from '../api/models';
import { environment } from '../../../environments/environment';

/**
 * Notifications feed (Phase 6b — live).
 *
 * Prod hits the real BFF: `GET /notifications` (newest-first, causal order),
 * `POST /notifications/{id}/read` and `/read-all` (both 204). The feed is
 * best-effort chrome — a failure (e.g. the image not yet rolled) must never break
 * the shell, so loads fall back to an empty feed and read-toggles are optimistic.
 *
 * Dev (`environment.useMockMe`) keeps a small in-memory seed so the bell + panel
 * are browsable without a BFF — the same convention as the `/me` mock. The seed
 * uses the REAL event `type` values the backend emits (`request.*`, `team.member.*`)
 * so dev mirrors prod. The public signal surface is identical in both modes.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/notifications`;

  private readonly _feed = signal<NotificationFeed>(EMPTY_FEED);
  readonly feed = this._feed.asReadonly();
  readonly unreadCount = computed(() => this._feed().unreadCount);
  readonly items = computed(() => this._feed().items);

  async load(): Promise<void> {
    if (environment.useMockMe) {
      this._feed.set(seedFeed());
      return;
    }
    try {
      this._feed.set(await firstValueFrom(this.http.get<NotificationFeed>(this.base)));
    } catch {
      this._feed.set(EMPTY_FEED);
    }
  }

  async markRead(id: string): Promise<void> {
    this.applyRead(id); // optimistic — the panel reacts immediately
    if (environment.useMockMe) return;
    try {
      await firstValueFrom(this.http.post<void>(`${this.base}/${id}/read`, null));
    } catch {
      // best-effort; the next load() re-syncs from the server
    }
  }

  async markAllRead(): Promise<void> {
    this.applyAllRead(); // optimistic
    if (environment.useMockMe) return;
    try {
      await firstValueFrom(this.http.post<void>(`${this.base}/read-all`, null));
    } catch {
      // best-effort
    }
  }

  private applyRead(id: string): void {
    this._feed.update((feed) => {
      const items = feed.items.map((n) => (n.id === id ? { ...n, read: true } : n));
      return { ...feed, items, unreadCount: items.filter((n) => !n.read).length };
    });
  }

  private applyAllRead(): void {
    this._feed.update((feed) => ({
      ...feed,
      items: feed.items.map((n) => ({ ...n, read: true })),
      unreadCount: 0,
    }));
  }
}

const EMPTY_FEED: NotificationFeed = { items: [], unreadCount: 0, nextCursor: null };

/** Dev-only sample feed. Types match what {@code NotifyProjector} actually emits. */
function seedFeed(): NotificationFeed {
  const items: Notification[] = [
    {
      id: 'n-1',
      type: 'request.granted',
      title: 'Access granted',
      body: 'Your access request was granted.',
      resourceRef: 'req-1024',
      read: false,
      createdAt: '2026-06-28T14:02:00Z',
    },
    {
      id: 'n-2',
      type: 'request.approved',
      title: 'Request approved',
      body: 'Your request was approved.',
      resourceRef: 'req-1031',
      read: false,
      createdAt: '2026-06-28T13:40:00Z',
    },
    {
      id: 'n-3',
      type: 'team.member.added',
      title: 'Added to a team',
      body: 'You were added to a team.',
      resourceRef: 'team-77',
      read: true,
      createdAt: '2026-06-27T17:15:00Z',
    },
  ];
  return { items, unreadCount: items.filter((n) => !n.read).length, nextCursor: null };
}
