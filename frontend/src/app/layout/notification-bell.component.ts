import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { NotificationsService } from '../core/services/notifications.service';

/**
 * Top-bar notification center: a bell with an unread-count badge that opens a
 * panel of notifications (both request types), newest first, each click-through
 * to its resource. Mark-as-read (single) + mark-all-read. Backed by a mock feed
 * until Phase 6 (see NotificationsService).
 */
@Component({
  selector: 'app-notification-bell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatBadgeModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDividerModule,
  ],
  template: `
    <button
      mat-icon-button
      [matMenuTriggerFor]="menu"
      [attr.aria-label]="
        notifications.unreadCount() > 0
          ? notifications.unreadCount() + ' unread notifications'
          : 'Notifications'
      "
    >
      <mat-icon
        [matBadge]="notifications.unreadCount()"
        [matBadgeHidden]="notifications.unreadCount() === 0"
        matBadgeColor="warn"
        matBadgeSize="small"
        >notifications</mat-icon
      >
    </button>

    <mat-menu #menu="matMenu" class="bell-menu" xPosition="before">
      <div class="bell-head">
        <span class="title">Notifications</span>
        <button
          mat-button
          (click)="notifications.markAllRead()"
          [disabled]="notifications.unreadCount() === 0"
        >
          Mark all read
        </button>
      </div>
      <mat-divider />

      @if (notifications.items().length === 0) {
        <p class="bell-empty text-muted">You're all caught up.</p>
      } @else {
        @for (n of notifications.items(); track n.id) {
          <button mat-menu-item class="bell-item" (click)="notifications.markRead(n.id)">
            <span class="unread-dot" [class.is-read]="n.read" aria-hidden="true"></span>
            <span class="bell-text">
              <span class="bell-title" [class.is-read]="n.read">{{ n.title }}</span>
              <span class="bell-body text-muted">{{ n.body }}</span>
              <span class="bell-time text-subtle">{{ n.createdAt | date: 'short' }}</span>
            </span>
          </button>
        }
      }
    </mat-menu>
  `,
  styles: [
    `
      .bell-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-2) var(--space-2) var(--space-2) var(--space-4);
      }
      .bell-head .title {
        font-weight: 600;
      }
      .bell-empty {
        padding: var(--space-5) var(--space-4);
        text-align: center;
        margin: 0;
      }
      .bell-item {
        height: auto;
        line-height: normal;
        padding-top: var(--space-3);
        padding-bottom: var(--space-3);
      }
      .bell-item {
        display: flex;
        align-items: flex-start;
        gap: var(--space-2);
      }
      .unread-dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--primary);
        margin-top: 6px;
        flex: 0 0 auto;
      }
      .unread-dot.is-read {
        background: transparent;
      }
      .bell-text {
        display: flex;
        flex-direction: column;
        gap: 2px;
        white-space: normal;
        max-width: 300px;
      }
      .bell-title {
        font-weight: 600;
        font-size: 13px;
      }
      .bell-title.is-read {
        font-weight: 400;
      }
      .bell-body {
        font-size: 12px;
        line-height: 1.35;
      }
      .bell-time {
        font-size: 11px;
      }
    `,
  ],
})
export class NotificationBellComponent implements OnInit {
  protected readonly notifications = inject(NotificationsService);

  ngOnInit(): void {
    this.notifications.load();
  }
}
