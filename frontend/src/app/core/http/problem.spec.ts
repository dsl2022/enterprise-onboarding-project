import { HttpErrorResponse } from '@angular/common/http';
import { ProblemError, toProblem } from './problem';

describe('toProblem', () => {
  it('passes an RFC-7807 problem+json body through unchanged', () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: { title: 'Conflict', detail: 'duplicate application name', status: 409 },
    });
    expect(toProblem(err)).toEqual(
      jasmine.objectContaining({ title: 'Conflict', detail: 'duplicate application name' }),
    );
  });

  it('synthesizes a problem from a non-7807 (string) error body', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Server Error', error: 'boom' });
    const p = toProblem(err);
    expect(p.status).toBe(500);
    expect(p.detail).toBe('boom');
  });
});

describe('ProblemError', () => {
  it('exposes typed status flags and uses `detail` as the message', () => {
    const e = new ProblemError(412, { title: 'Precondition Failed', detail: 'stale etag' });
    expect(e.isPreconditionFailed).toBeTrue();
    expect(e.isConflict).toBeFalse();
    expect(e.message).toBe('stale etag');
  });

  it('treats 401 as unauthorized and 422 as unprocessable', () => {
    expect(new ProblemError(401, {}).isUnauthorized).toBeTrue();
    expect(new ProblemError(422, {}).isUnprocessable).toBeTrue();
  });
});
