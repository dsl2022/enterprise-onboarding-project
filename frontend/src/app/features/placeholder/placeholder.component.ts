import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ToneChipComponent } from '../../shared/components/tone-chip.component';
import { Tone } from '../../shared/status';

/**
 * Foundation placeholder for routes whose feature screens land next. Title/subtitle
 * and a backend-status badge come from the route's `data` (bound via the router's
 * component input binding). Keeps navigation real and honestly labels what's wired
 * to a live endpoint vs. mocked vs. not built (per docs/integration/STATUS.md).
 */
@Component({
  selector: 'app-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeaderComponent, EmptyStateComponent, ToneChipComponent],
  template: `
    <div class="page">
      <app-page-header [title]="title()" [subtitle]="subtitle()">
        <app-tone-chip actions [label]="badgeLabel()" [tone]="badgeTone()" [dot]="true" />
      </app-page-header>

      <div class="card-surface">
        <app-empty-state icon="construction" heading="Screen coming next" [message]="note()">
        </app-empty-state>
      </div>
    </div>
  `,
})
export class PlaceholderComponent {
  readonly title = input('Section');
  readonly subtitle = input('');
  readonly note = input('This screen is part of the planned build and will arrive shortly.');
  /** 'live' = backend ready · 'mock' = backend not built · 'stub' = 501. */
  readonly status = input<'live' | 'mock' | 'stub'>('live');

  protected badgeLabel(): string {
    return { live: 'Backend live', mock: 'Mocked', stub: 'Stubbed (501)' }[this.status()];
  }
  protected badgeTone(): Tone {
    return ({ live: 'success', mock: 'warn', stub: 'neutral' } as const)[this.status()];
  }
}
