import { HttpErrorResponse } from '@angular/common/http';
import { Problem } from '../api/models';

/**
 * A normalized error for the whole app. Every failed HTTP call is mapped to one
 * of these by `problemInterceptor`, so features can `catch (e: ProblemError)` and
 * render `e.message` (the RFC-7807 `detail`) without re-parsing responses.
 */
export class ProblemError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
    readonly original?: HttpErrorResponse,
  ) {
    super(problem.detail || problem.title || `Request failed (${status})`);
    this.name = 'ProblemError';
  }

  get title(): string {
    return this.problem.title ?? 'Something went wrong';
  }

  get correlationId(): string | undefined {
    return this.problem.correlationId;
  }

  /** No session — the interceptor sends the user to login for non-/me calls. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }
  /** Permission / ownership / separation-of-duties denial. */
  get isForbidden(): boolean {
    return this.status === 403;
  }
  get isNotFound(): boolean {
    return this.status === 404;
  }
  /** State conflict / illegal transition / business conflict (e.g. dup name). */
  get isConflict(): boolean {
    return this.status === 409;
  }
  /** Stale ETag — re-fetch and retry. */
  get isPreconditionFailed(): boolean {
    return this.status === 412;
  }
  /** Validation, or idempotency-key reuse with a different body. */
  get isUnprocessable(): boolean {
    return this.status === 422;
  }
}

/** Best-effort map of any HttpErrorResponse onto the RFC-7807 shape. */
export function toProblem(err: HttpErrorResponse): Problem {
  const body = err.error;
  if (body && typeof body === 'object' && ('title' in body || 'detail' in body || 'status' in body)) {
    return body as Problem;
  }
  return {
    status: err.status,
    title: err.statusText || 'Request failed',
    detail: typeof body === 'string' && body ? body : err.message,
  };
}
