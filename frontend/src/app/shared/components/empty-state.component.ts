import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * Friendly empty state with an icon, message, and an optional projected action
 * (e.g. "Browse the catalog"). Used by lists with nothing to show yet.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <div class="empty-state">
      <mat-icon class="icon" aria-hidden="true">{{ icon() }}</mat-icon>
      <h2>{{ heading() }}</h2>
      @if (message()) {
        <p class="text-muted">{{ message() }}</p>
      }
      <div class="action">
        <ng-content />
      </div>
    </div>
  `,
  styles: [
    `
      .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        padding: var(--space-7) var(--space-4);
        color: var(--text-muted);
      }
      .icon {
        width: 40px;
        height: 40px;
        font-size: 40px;
        color: var(--text-subtle);
        margin-bottom: var(--space-3);
      }
      h2 {
        margin: 0 0 var(--space-2);
        font-size: 16px;
        font-weight: 600;
        color: var(--text);
      }
      p {
        margin: 0;
        max-width: 42ch;
        font-size: 14px;
      }
      .action {
        margin-top: var(--space-4);
      }
    `,
  ],
})
export class EmptyStateComponent {
  readonly icon = input('inbox');
  readonly heading = input.required<string>();
  readonly message = input<string>('');
}
