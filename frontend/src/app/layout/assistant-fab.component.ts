import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../core/auth/auth.service';
import { AssistantChatComponent } from '../features/assistant/assistant-chat.component';

/**
 * Floating AI-assistant launcher, bottom-right of every authenticated page. Opens
 * a docked chat panel rendering the shared {@link AssistantChatComponent} — so the
 * assistant is reachable without leaving the current screen, and its conversation
 * is the same one shown on the full `/assistant` page (state lives in the service).
 *
 * Only shown to principals with `assistant.use` (mirrors the nav item + route
 * guard + the backend gate), so it never appears pre-auth or for AUDITOR/READ_ONLY.
 */
@Component({
  selector: 'app-assistant-fab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, AssistantChatComponent],
  template: `
    @if (canUse()) {
      @if (open()) {
        <section class="panel" role="dialog" aria-label="AI assistant">
          <header class="panel-head">
            <mat-icon class="bot">smart_toy</mat-icon>
            <span class="title">AI Assistant</span>
            <span class="preview-pill">Preview</span>
            <span class="spacer"></span>
            <a
              mat-icon-button
              routerLink="/assistant"
              matTooltip="Open full page"
              aria-label="Open full page"
              (click)="open.set(false)"
            >
              <mat-icon>open_in_full</mat-icon>
            </a>
            <button mat-icon-button aria-label="Close assistant" (click)="open.set(false)">
              <mat-icon>close</mat-icon>
            </button>
          </header>
          <app-assistant-chat class="panel-body" [compact]="true" />
        </section>
      }

      <button
        mat-fab
        class="fab"
        [attr.aria-label]="open() ? 'Close AI assistant' : 'Open AI assistant'"
        [matTooltip]="open() ? '' : 'AI Assistant'"
        (click)="open.set(!open())"
      >
        <mat-icon>{{ open() ? 'close' : 'forum' }}</mat-icon>
      </button>
    }
  `,
  styles: [
    `
      :host {
        position: fixed;
        right: 24px;
        bottom: 24px;
        z-index: 1000;
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: var(--space-3);
      }
      .fab {
        align-self: flex-end;
      }
      .panel {
        width: 380px;
        max-width: calc(100vw - 48px);
        height: 540px;
        max-height: calc(100vh - 140px);
        display: flex;
        flex-direction: column;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg, 0 12px 32px rgba(0, 0, 0, 0.24));
        overflow: hidden;
      }
      .panel-head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-2) var(--space-2) var(--space-2) var(--space-4);
        border-bottom: 1px solid var(--border);
      }
      .panel-head .bot {
        color: var(--primary);
      }
      .panel-head .title {
        font-weight: 600;
      }
      .preview-pill {
        font-size: 10px;
        font-weight: 600;
        color: var(--status-info-fg);
        background: var(--status-info-bg);
        padding: 1px 8px;
        border-radius: var(--radius-pill);
      }
      .spacer {
        flex: 1 1 auto;
      }
      .panel-body {
        flex: 1 1 auto;
        min-height: 0;
        display: flex;
        flex-direction: column;
        padding: var(--space-4);
        overflow: hidden;
      }
      @media (max-width: 600px) {
        :host {
          right: 16px;
          bottom: 16px;
        }
        .panel {
          height: calc(100vh - 120px);
        }
      }
    `,
  ],
})
export class AssistantFabComponent {
  private readonly auth = inject(AuthService);
  protected readonly open = signal(false);
  protected readonly canUse = computed(() => this.auth.can('assistant.use'));
}
