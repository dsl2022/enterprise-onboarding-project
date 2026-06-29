import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NotificationsService } from './notifications.service';
import { NotificationFeed } from '../api/models';

/**
 * Exercises the live-HTTP path. The test build uses environment.ts (useMockMe=false),
 * so the service hits the BFF rather than the dev seed.
 */
describe('NotificationsService (live feed)', () => {
  let service: NotificationsService;
  let http: HttpTestingController;

  const feed: NotificationFeed = {
    items: [
      { id: 'a', type: 'request.granted', title: 'Access granted', body: '…', resourceRef: 'r1', read: false, createdAt: '2026-06-28T14:02:00Z' },
      { id: 'b', type: 'team.member.added', title: 'Added to a team', body: '…', resourceRef: 't1', read: false, createdAt: '2026-06-28T13:40:00Z' },
    ],
    unreadCount: 2,
    nextCursor: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NotificationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('load() GETs /notifications and exposes items + unreadCount', async () => {
    const done = service.load();
    http.expectOne('/api/v1/notifications').flush(feed);
    await done;
    expect(service.items().length).toBe(2);
    expect(service.unreadCount()).toBe(2);
  });

  it('load() falls back to an empty feed if the request fails', async () => {
    const done = service.load();
    http.expectOne('/api/v1/notifications').error(new ProgressEvent('500'));
    await done;
    expect(service.items()).toEqual([]);
    expect(service.unreadCount()).toBe(0);
  });

  it('markRead() optimistically clears one item and POSTs /{id}/read', async () => {
    const loaded = service.load();
    http.expectOne('/api/v1/notifications').flush(feed);
    await loaded;

    const done = service.markRead('a');
    // Optimistic: signal already reflects the read before the POST resolves.
    expect(service.unreadCount()).toBe(1);
    expect(service.items().find((n) => n.id === 'a')?.read).toBeTrue();
    http.expectOne({ method: 'POST', url: '/api/v1/notifications/a/read' }).flush(null, { status: 204, statusText: 'No Content' });
    await done;
  });

  it('markAllRead() clears the badge and POSTs /read-all', async () => {
    const loaded = service.load();
    http.expectOne('/api/v1/notifications').flush(feed);
    await loaded;

    const done = service.markAllRead();
    expect(service.unreadCount()).toBe(0);
    expect(service.items().every((n) => n.read)).toBeTrue();
    http.expectOne({ method: 'POST', url: '/api/v1/notifications/read-all' }).flush(null, { status: 204, statusText: 'No Content' });
    await done;
  });
});
