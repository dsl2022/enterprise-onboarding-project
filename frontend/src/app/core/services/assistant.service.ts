import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { AssistantChatRequest, AssistantChatResponse } from '../api/models';
import { newIdempotencyKey, writeHeaders } from '../http/request-options';

/**
 * Client for the onboarding assistant (`POST /assistant/chat`).
 *
 * The assistant is **advisory, never authoritative** (see
 * `docs/assistant-feature-design-and-guardrails.md`): it drafts and suggests, but
 * the governed request engine remains the only thing that can change state. In v1
 * the backend is a **501 stub** behind the `assistant.use` gate — so the UI is
 * built to **fail closed and degrade gracefully** (a `ProblemError` with status
 * 501 means "not enabled yet", not an app error). It lights up automatically when
 * the assistant track ships, with no frontend change.
 *
 * The chat carries an Idempotency-Key per send (the contract reserves it for the
 * real implementation; the stub ignores it).
 */
@Injectable({ providedIn: 'root' })
export class AssistantService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/assistant`;

  chat(message: string, context?: Record<string, unknown>): Promise<AssistantChatResponse> {
    const body: AssistantChatRequest = context ? { message, context } : { message };
    return firstValueFrom(
      this.http.post<AssistantChatResponse>(`${this.base}/chat`, body, {
        headers: writeHeaders({ idempotencyKey: newIdempotencyKey() }),
      }),
    );
  }
}
