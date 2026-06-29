import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { statusMeta } from '../status';
import { ToneChipComponent } from './tone-chip.component';

/**
 * Status chip for BOTH request types — pass any OnboardingStatus or AccessStatus
 * string and it renders with the shared semantic tone + sentence-case label.
 */
@Component({
  selector: 'app-status-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ToneChipComponent],
  template: `<app-tone-chip [label]="meta().label" [tone]="meta().tone" />`,
})
export class StatusChipComponent {
  readonly status = input.required<string>();
  protected readonly meta = computed(() => statusMeta(this.status()));
}
