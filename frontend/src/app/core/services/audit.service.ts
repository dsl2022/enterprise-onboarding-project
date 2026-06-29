import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { AuditPage, AuditVerifyResult } from '../api/models';

export interface AuditQuery {
  actor?: string;
  type?: string;
  resource?: string;
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}

/**
 * Client for the hash-chained audit log (Phase 6a). `GET /audit` is a derived,
 * eventually-consistent projection (the relay writes it sub-second after the
 * action — never poll it to confirm a write). `actor` is always the REAL principal
 * (Super Admin even while impersonating); `seq` is a monotonic ordering key that
 * may have GAPS (not a count). `GET /audit/verify` recomputes the chain — a
 * tamper-check, not a freshness check.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/audit`;

  list(query: AuditQuery = {}): Promise<AuditPage> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(query)) {
      if (v) params[k] = String(v);
    }
    return firstValueFrom(this.http.get<AuditPage>(this.base, { params }));
  }

  verify(): Promise<AuditVerifyResult> {
    return firstValueFrom(this.http.get<AuditVerifyResult>(`${this.base}/verify`));
  }
}
