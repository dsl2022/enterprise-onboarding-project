import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import { API_BASE } from '../api/api.config';
import {
  Application,
  ApplicationCreate,
  ApplicationPage,
  ApplicationPatch,
  DecisionBody,
  OnboardingStatus,
  TimelineEntry,
} from '../api/models';
import { newIdempotencyKey, writeHeaders } from '../http/request-options';

/** A resource plus the ETag to send back on its next mutation (If-Match). */
export interface Versioned<T> {
  value: T;
  etag: string | null;
}

export interface ApplicationListQuery {
  cursor?: string;
  limit?: number;
  status?: OnboardingStatus;
  owner?: string;
}

/**
 * Client for the live `/applications` onboarding endpoints. Owns the contract's
 * write-time mechanics so feature components don't have to: an Idempotency-Key per
 * create/transition (reused on retry by the caller passing the same key) and
 * ETag/If-Match optimistic concurrency (read the ETag from each single-resource
 * response, echo it on the next submit/decision/patch; a 412 = re-fetch & retry).
 */
@Injectable({ providedIn: 'root' })
export class ApplicationsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/applications`;

  list(query: ApplicationListQuery = {}): Promise<ApplicationPage> {
    const params: Record<string, string> = {};
    if (query.cursor) params['cursor'] = query.cursor;
    if (query.limit) params['limit'] = String(query.limit);
    if (query.status) params['status'] = query.status;
    if (query.owner) params['owner'] = query.owner;
    return firstValueFrom(this.http.get<ApplicationPage>(this.base, { params }));
  }

  get(id: string): Promise<Versioned<Application>> {
    return firstValueFrom(
      this.http
        .get<Application>(`${this.base}/${id}`, { observe: 'response' })
        .pipe(map((res) => this.versioned(res))),
    );
  }

  timeline(id: string): Promise<TimelineEntry[]> {
    return firstValueFrom(this.http.get<TimelineEntry[]>(`${this.base}/${id}/timeline`));
  }

  create(payload: ApplicationCreate, idempotencyKey = newIdempotencyKey()): Promise<Versioned<Application>> {
    return firstValueFrom(
      this.http
        .post<Application>(this.base, payload, {
          observe: 'response',
          headers: writeHeaders({ idempotencyKey }),
        })
        .pipe(map((res) => this.versioned(res))),
    );
  }

  patch(id: string, payload: ApplicationPatch, etag: string): Promise<Versioned<Application>> {
    return firstValueFrom(
      this.http
        .patch<Application>(`${this.base}/${id}`, payload, {
          observe: 'response',
          headers: writeHeaders({ etag }),
        })
        .pipe(map((res) => this.versioned(res))),
    );
  }

  submit(id: string, etag: string, idempotencyKey = newIdempotencyKey()): Promise<Application> {
    return firstValueFrom(
      this.http.post<Application>(`${this.base}/${id}/submit`, null, {
        headers: writeHeaders({ idempotencyKey, etag }),
      }),
    );
  }

  decide(
    id: string,
    body: DecisionBody,
    etag: string,
    idempotencyKey = newIdempotencyKey(),
  ): Promise<Application> {
    return firstValueFrom(
      this.http.post<Application>(`${this.base}/${id}/decision`, body, {
        headers: writeHeaders({ idempotencyKey, etag }),
      }),
    );
  }

  private versioned(res: HttpResponse<Application>): Versioned<Application> {
    return { value: res.body as Application, etag: res.headers.get('ETag') };
  }
}
