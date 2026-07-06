import { ChangeDetectionStrategy, Component, HostBinding, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ProposedAction } from '../../core/api/models';
import { AssistantService, ChatRole } from '../../core/services/assistant.service';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';

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
 * The reusable AI-assistant chat surface (thread + composer + suggestions + the
 * "not enabled yet" preview notice). Rendered both full-page
 * ({@link AssistantComponent}) and inside the floating widget — both share ONE
 * conversation via {@link AssistantService}. Set `compact` for the widget.
 */
@Component({
  selector: 'app-assistant-chat',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    EmptyStateComponent,
  ],
  templateUrl: './assistant-chat.component.html',
  styleUrl: './assistant-chat.component.scss',
})
export class AssistantChatComponent {
  protected readonly svc = inject(AssistantService);

  /** Tighter layout + scrolling thread for the floating widget. */
  readonly compact = input(false);
  @HostBinding('class.compact') get isCompact(): boolean {
    return this.compact();
  }

  protected draft = '';
  protected readonly suggestions = SUGGESTIONS;

  protected send(): void {
    const text = this.draft.trim();
    if (!text) return;
    this.draft = '';
    void this.svc.send(text);
  }

  protected useSuggestion(s: string): void {
    this.draft = s;
  }

  protected avatar(role: ChatRole): string {
    return role === 'user' ? 'person' : role === 'assistant' ? 'smart_toy' : 'info';
  }

  protected toolLabel(tool: ProposedAction['tool']): string {
    return TOOL_LABELS[tool] ?? tool;
  }

  /** The drafted free-text for draftDescription / draftJustification (keyed by arg, not tool name, so it
   *  renders even for tools not yet in the generated contract enum). */
  protected actionText(a: ProposedAction): string | null {
    const v = a.args['description'] ?? a.args['justification'];
    return typeof v === 'string' && v.trim() ? v : null;
  }

  /** The suggested scope tokens for recommendScopes (else null). */
  protected actionScopes(a: ProposedAction): string[] | null {
    if (a.tool !== 'recommendScopes' || !Array.isArray(a.args['scopes'])) return null;
    return (a.args['scopes'] as unknown[]).filter((s): s is string => typeof s === 'string');
  }

  /** The per-URI verdicts for validateRedirectUris (else null). */
  protected actionUriResults(a: ProposedAction): UriVerdict[] | null {
    if (a.tool !== 'validateRedirectUris' || !Array.isArray(a.args['results'])) return null;
    return (a.args['results'] as UriVerdict[]).filter((r) => r && typeof r.uri === 'string');
  }

  /** Copy a suggestion to the clipboard so the user can paste it into the wizard field. */
  protected copy(text: string): void {
    void navigator.clipboard?.writeText(text);
  }
}

interface UriVerdict {
  uri: string;
  valid: boolean;
  reason: string;
}
