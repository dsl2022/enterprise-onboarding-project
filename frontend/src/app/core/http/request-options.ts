import { HttpHeaders } from '@angular/common/http';

/**
 * Helpers for the contract's two write-time concurrency mechanisms:
 *
 *  - Idempotency-Key (required on creating/transition POSTs): generate ONE key
 *    per user-intent and reuse it on retry of that same intent. Replay → the
 *    original response; same key + a different body → 422.
 *  - If-Match / ETag (optimistic concurrency on submit/decision/PATCH): echo the
 *    ETag from the last GET; a 412 means "someone changed it — re-fetch & retry".
 */

export function newIdempotencyKey(): string {
  // Available in all evergreen browsers + the Angular dev server context.
  return crypto.randomUUID();
}

export interface WriteOptions {
  /** Reuse the SAME key across retries of one intent. */
  idempotencyKey?: string;
  /** ETag from the resource's last GET (for If-Match). */
  etag?: string;
}

/** Build HttpHeaders carrying Idempotency-Key and/or If-Match as provided. */
export function writeHeaders(opts: WriteOptions = {}): HttpHeaders {
  let headers = new HttpHeaders();
  if (opts.idempotencyKey) {
    headers = headers.set('Idempotency-Key', opts.idempotencyKey);
  }
  if (opts.etag) {
    headers = headers.set('If-Match', opts.etag);
  }
  return headers;
}
