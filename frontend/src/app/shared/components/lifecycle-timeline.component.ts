import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TimelineEntry } from '../../core/api/models';
import { StatusChipComponent } from './status-chip.component';

/**
 * Vertical lifecycle timeline — the SAME component drives both the application
 * onboarding history and the access-request history, so the two request types
 * read identically. Newest entry first; each row shows the status it moved to,
 * who did it, an optional reason, and when.
 */
@Component({
  selector: 'app-lifecycle-timeline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, StatusChipComponent],
  template: `
    @if (entries().length === 0) {
      <p class="text-subtle empty">No history yet.</p>
    } @else {
      <ol class="timeline">
        @for (entry of entries(); track entry.id) {
          <li class="entry">
            <span class="rail" aria-hidden="true">
              <span class="node"></span>
            </span>
            <div class="body">
              <div class="head">
                <app-status-chip [status]="entry.status" />
                <time class="when text-subtle" [attr.datetime]="entry.at">
                  {{ entry.at | date: 'medium' }}
                </time>
              </div>
              <div class="meta text-muted">
                by <span class="actor">{{ entry.actor }}</span>
                @if (entry.reason) {
                  <span class="reason">— {{ entry.reason }}</span>
                }
              </div>
            </div>
          </li>
        }
      </ol>
    }
  `,
  styleUrl: './lifecycle-timeline.component.scss',
})
export class LifecycleTimelineComponent {
  readonly entries = input.required<TimelineEntry[]>();
}
