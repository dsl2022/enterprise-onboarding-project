import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { AssistantChatRequest, AssistantChatResponse, ProposedAction } from '../api/models';
import { ProblemError } from '../http/problem';
import { newIdempotencyKey, writeHeaders } from '../http/request-options';

export type ChatRole = 'user' | 'assistant' | 'system';

export interface ChatMessage {
  role: ChatRole;
  text: string;
  actions?: ProposedAction[];
}

/**
 * Client + shared conversation state for the AI assistant (`POST /assistant/chat`).
 *
 * The assistant is **advisory, never authoritative** (see
 * `docs/assistant-feature-design-and-guardrails.md`): it drafts and suggests, but
 * the governed request engine remains the only thing that can change state. In v1
 * the backend is a **501 stub** behind the `assistant.use` gate — so this is built
 * to **fail closed and degrade gracefully** (a `ProblemError` 501 means "not
 * enabled yet", not an app error). It lights up automatically when the assistant
 * track ships, with no frontend change.
 *
 * Conversation state lives here (not in a component) so the full-page view and the
 * floating chat widget share ONE thread.
 */
@Injectable({ providedIn: 'root' })
export class AssistantService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/assistant`;

  private readonly _messages = signal<ChatMessage[]>([]);
  readonly messages = this._messages.asReadonly();
  private readonly _sending = signal(false);
  readonly sending = this._sending.asReadonly();
  /** Set once the backend answers 501 — drives the "preview, not enabled yet" banner. */
  private readonly _unavailable = signal(false);
  readonly unavailable = this._unavailable.asReadonly();

  /** Low-level call: POST one message with a per-send Idempotency-Key. */
  chat(message: string, context?: Record<string, unknown>): Promise<AssistantChatResponse> {
    const body: AssistantChatRequest = context ? { message, context } : { message };
    return firstValueFrom(
      this.http.post<AssistantChatResponse>(`${this.base}/chat`, body, {
        headers: writeHeaders({ idempotencyKey: newIdempotencyKey() }),
      }),
    );
  }

  /** Stateful send used by the chat UI: appends the turn and handles the 501 preview. */
  async send(text: string): Promise<void> {
    const message = text.trim();
    if (!message || this._sending()) return;
    this.push({ role: 'user', text: message });
    this._sending.set(true);
    try {
      const res = await this.chat(message);
      this.push({ role: 'assistant', text: res.reply, actions: res.proposedActions });
    } catch (err) {
      if (err instanceof ProblemError && err.status === 501) {
        this._unavailable.set(true);
        this.push({
          role: 'system',
          text: "The AI assistant isn't enabled yet — this is a preview. It'll start answering here automatically once the assistant track ships. For now, fill in the form manually.",
        });
      } else {
        this.push({
          role: 'system',
          text: err instanceof ProblemError ? err.message : 'Something went wrong reaching the assistant.',
        });
      }
    } finally {
      this._sending.set(false);
    }
  }

  private push(message: ChatMessage): void {
    this._messages.update((cur) => [...cur, message]);
  }
}
