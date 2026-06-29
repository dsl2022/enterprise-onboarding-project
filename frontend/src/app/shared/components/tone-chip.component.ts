import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Tone } from '../status';

/** Presentational chip rendered in one of the five semantic tones. */
@Component({
  selector: 'app-tone-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="chip" [class]="toneClass()">
      @if (dot()) {
        <span class="dot" aria-hidden="true"></span>
      }
      {{ label() }}
    </span>
  `,
  styleUrl: './tone-chip.component.scss',
})
export class ToneChipComponent {
  readonly label = input.required<string>();
  readonly tone = input<Tone>('neutral');
  readonly dot = input(true);

  protected readonly toneClass = computed(() => `tone-${this.tone()}`);
}
