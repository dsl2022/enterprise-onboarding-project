import { ChangeDetectionStrategy, Component } from '@angular/core';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { AssistantChatComponent } from './assistant-chat.component';

/**
 * Full-page AI assistant view — a page header over the shared chat surface. The
 * same chat (and conversation) is also reachable from the floating widget in the
 * shell; both share state via {@link AssistantService}. See
 * {@link AssistantChatComponent} for the advisory-only / graceful-501 behaviour.
 */
@Component({
  selector: 'app-assistant',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeaderComponent, AssistantChatComponent],
  template: `
    <div class="page">
      <app-page-header
        title="AI Assistant"
        subtitle="An advisory onboarding helper — it drafts and suggests; you always review and approve. It can never change anything on its own."
      >
        <span actions class="preview-tag">Preview</span>
      </app-page-header>

      <app-assistant-chat />
    </div>
  `,
  styles: [
    `
      .preview-tag {
        font-size: 11px;
        font-weight: 600;
        color: var(--status-info-fg);
        background: var(--status-info-bg);
        padding: 2px 10px;
        border-radius: var(--radius-pill);
        white-space: nowrap;
      }
    `,
  ],
})
export class AssistantComponent {}
