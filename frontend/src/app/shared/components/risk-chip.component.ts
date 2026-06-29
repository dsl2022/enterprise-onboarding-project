import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Risk } from '../../core/api/models';
import { riskMeta } from '../status';
import { ToneChipComponent } from './tone-chip.component';

/** Catalog risk-level chip (low = success, medium = warning, high = danger). */
@Component({
  selector: 'app-risk-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ToneChipComponent],
  template: `<app-tone-chip [label]="meta().label" [tone]="meta().tone" />`,
})
export class RiskChipComponent {
  readonly risk = input.required<Risk>();
  protected readonly meta = computed(() => riskMeta(this.risk()));
}
