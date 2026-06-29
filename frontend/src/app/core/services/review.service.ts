import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { RequestKind, ReviewQueuePage } from '../api/models';

export interface ReviewQueueQuery {
  type?: RequestKind;
  cursor?: string;
  limit?: number;
}

/**
 * The unified review queue — items UNDER_REVIEW across BOTH request types
 * (onboarding + access). Items are lightweight ({id, kind, title, requester,
 * submittedAt}); deciding fetches the full resource (for its ETag) via the
 * applications/access services, so the approve interaction is identical for both.
 */
@Injectable({ providedIn: 'root' })
export class ReviewService {
  private readonly http = inject(HttpClient);

  list(query: ReviewQueueQuery = {}): Promise<ReviewQueuePage> {
    const params: Record<string, string> = {};
    if (query.type) params['type'] = query.type;
    if (query.cursor) params['cursor'] = query.cursor;
    if (query.limit) params['limit'] = String(query.limit);
    return firstValueFrom(this.http.get<ReviewQueuePage>(`${API_BASE}/review-queue`, { params }));
  }
}
