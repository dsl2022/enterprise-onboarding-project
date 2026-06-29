import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import { API_BASE } from '../api/api.config';
import {
  AccessRequest,
  AccessRequestCreate,
  AccessRequestKind,
  AccessRequestPage,
  AccessStatus,
  CatalogPage,
  CatalogResource,
  DecisionBody,
  MyAccessItem,
  ResourceType,
  Risk,
} from '../api/models';
import { newIdempotencyKey, writeHeaders } from '../http/request-options';
import { Versioned } from './applications.service';

export interface CatalogQuery {
  type?: ResourceType;
  risk?: Risk;
  cursor?: string;
  limit?: number;
}

export interface AccessRequestQuery {
  status?: AccessStatus;
  type?: AccessRequestKind;
  cursor?: string;
  limit?: number;
}

/**
 * Client for the live access-governance endpoints: the requestable `/catalog`,
 * `/access-requests` (the second request type — same approve→provision→audit
 * workflow as onboarding), and `/my-access` (the SOURCE OF TRUTH for what a user
 * currently holds — never inferred from a request's status; a removal request
 * ending GRANTED means "removal completed", not "has access").
 */
@Injectable({ providedIn: 'root' })
export class AccessService {
  private readonly http = inject(HttpClient);

  // ---- Catalog -------------------------------------------------------------
  listCatalog(query: CatalogQuery = {}): Promise<CatalogPage> {
    const params: Record<string, string> = {};
    if (query.type) params['type'] = query.type;
    if (query.risk) params['risk'] = query.risk;
    if (query.cursor) params['cursor'] = query.cursor;
    if (query.limit) params['limit'] = String(query.limit);
    return firstValueFrom(this.http.get<CatalogPage>(`${API_BASE}/catalog`, { params }));
  }

  getCatalogItem(id: string): Promise<CatalogResource> {
    return firstValueFrom(this.http.get<CatalogResource>(`${API_BASE}/catalog/${id}`));
  }

  // ---- Access requests -----------------------------------------------------
  listAccessRequests(query: AccessRequestQuery = {}): Promise<AccessRequestPage> {
    const params: Record<string, string> = {};
    if (query.status) params['status'] = query.status;
    if (query.type) params['type'] = query.type;
    if (query.cursor) params['cursor'] = query.cursor;
    if (query.limit) params['limit'] = String(query.limit);
    return firstValueFrom(this.http.get<AccessRequestPage>(`${API_BASE}/access-requests`, { params }));
  }

  getAccessRequest(id: string): Promise<Versioned<AccessRequest>> {
    return firstValueFrom(
      this.http
        .get<AccessRequest>(`${API_BASE}/access-requests/${id}`, { observe: 'response' })
        .pipe(map((res) => this.versioned(res))),
    );
  }

  createAccessRequest(
    payload: AccessRequestCreate,
    idempotencyKey = newIdempotencyKey(),
  ): Promise<AccessRequest> {
    return firstValueFrom(
      this.http.post<AccessRequest>(`${API_BASE}/access-requests`, payload, {
        headers: writeHeaders({ idempotencyKey }),
      }),
    );
  }

  decideAccessRequest(
    id: string,
    body: DecisionBody,
    etag: string,
    idempotencyKey = newIdempotencyKey(),
  ): Promise<AccessRequest> {
    return firstValueFrom(
      this.http.post<AccessRequest>(`${API_BASE}/access-requests/${id}/decision`, body, {
        headers: writeHeaders({ idempotencyKey, etag }),
      }),
    );
  }

  // ---- My access (held resources) ------------------------------------------
  listMyAccess(): Promise<MyAccessItem[]> {
    return firstValueFrom(this.http.get<MyAccessItem[]>(`${API_BASE}/my-access`));
  }

  /** Removal is itself a request (kind=removal) that goes through approval. */
  requestRemoval(resourceId: string, idempotencyKey = newIdempotencyKey()): Promise<AccessRequest> {
    return firstValueFrom(
      this.http.post<AccessRequest>(`${API_BASE}/my-access/${resourceId}/removal`, null, {
        headers: writeHeaders({ idempotencyKey }),
      }),
    );
  }

  private versioned(res: HttpResponse<AccessRequest>): Versioned<AccessRequest> {
    return { value: res.body as AccessRequest, etag: res.headers.get('ETag') };
  }
}
