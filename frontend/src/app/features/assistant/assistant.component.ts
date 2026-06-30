import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ProposedAction } from '../../core/api/models';
import { AssistantService } from '../../core/services/assistant.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';

type Role = 'user' | 'assistant' | 'system';

interface ChatMessage {
  role: Role;
  text: string;
  actions?: ProposedAction[];
}

/** Suggestion chips mapped 1:1 to the v1 advisory tools (all read-only, no writes). */
const SUGGESTIONS: readonly string[] = [
  'Draft a description for my app',
  'Check my redirect URIs',
  'Recommend least-privilege scopes',
  'Can I request this catalog resource?',
];

const TOOL_LABELS: Record<ProposedAction['tool'], string> = {
  draftDescription: 'Draft description',
  validateRedirectUris: 'Validate redirect URIs',
  recommendScopes: 'Recommend scopes',
  checkGroupOwnership: 'Check group ownership',
};

/**
 * Onboarding assistant — a chat surface that helps users draft the wizard forms.
 *
 * It is **advisory, never authoritative** (see the design doc): it can only
 * *suggest* inputs; the governed request engine (RBAC/ABAC/SoD) remains the only
 * thing that changes state, and any write-action would still go through the
 * human-in-the-loop approve flow. The backend is a **501 stub** in v1, so this
 * screen is built to **fail closed** — a 501 flips it into an honest "not enabled
 * yet" preview rather than erroring. When the assistant track ships it answers
 * here automatically, no frontend change needed.
 */
@Component({
  selector: 'app-assistant',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    PageHeaderComponent,
    EmptyStateComponent,
  ],
  templateUrl: './assistant.component.html',
  styleUrl: './assistant.component.scss',
})
export class AssistantComponent {
  private readonly api = inject(AssistantService);

  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly sending = signal(false);
  /** Set once the backend answers 501 — drives the "preview, not enabled yet" banner. */
  protected readonly unavailable = signal(false);
  protected draft = '';

  protected readonly suggestions = SUGGESTIONS;

  protected async send(): Promise<void> {
    const text = this.draft.trim();
    if (!text || this.sending()) return;
    this.draft = '';
    this.push({ role: 'user', text });
    this.sending.set(true);
    try {
      const res = await this.api.chat(text);
      this.push({ role: 'assistant', text: res.reply, actions: res.proposedActions });
    } catch (err) {
      if (err instanceof ProblemError && err.status === 501) {
        this.unavailable.set(true);
        this.push({
          role: 'system',
          text: "The assistant isn't enabled yet — this is a preview. It'll start answering here automatically once the assistant track ships. For now, fill in the form manually.",
        });
      } else {
        this.push({
          role: 'system',
          text: err instanceof ProblemError ? err.message : 'Something went wrong reaching the assistant.',
        });
      }
    } finally {
      this.sending.set(false);
    }
  }

  protected useSuggestion(s: string): void {
    this.draft = s;
  }

  protected avatar(role: Role): string {
    return role === 'user' ? 'person' : role === 'assistant' ? 'smart_toy' : 'info';
  }

  protected toolLabel(tool: ProposedAction['tool']): string {
    return TOOL_LABELS[tool] ?? tool;
  }

  private push(message: ChatMessage): void {
    this.messages.update((cur) => [...cur, message]);
  }
}
