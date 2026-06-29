import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Standard page heading: title + optional subtitle on the left, projected actions
 * on the right (`<button actions>…</button>`). Keeps every feature page aligned.
 */
@Component({
  selector: 'app-page-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div class="titles">
        <h1>{{ title() }}</h1>
        @if (subtitle()) {
          <p class="text-muted subtitle">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions">
        <ng-content select="[actions]" />
      </div>
    </header>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .page-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--space-4);
        margin-bottom: var(--space-5);
        flex-wrap: wrap;
      }
      h1 {
        margin: 0;
        font-size: 22px;
        font-weight: 600;
        letter-spacing: -0.01em;
      }
      .subtitle {
        margin: var(--space-1) 0 0;
        font-size: 14px;
        max-width: 60ch;
      }
      .actions {
        display: flex;
        gap: var(--space-2);
        align-items: center;
      }
    `,
  ],
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly subtitle = input<string>('');
}
