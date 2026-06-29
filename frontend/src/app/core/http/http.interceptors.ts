import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { catchError, throwError } from 'rxjs';
import { LOGIN_URL } from '../api/api.config';
import { ProblemError, toProblem } from './problem';

/**
 * Send the BFF session cookie with every request. We're same-origin behind the
 * BFF in prod and proxy to it in dev, so this is the only auth the browser does.
 */
export const credentialsInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ withCredentials: true }));

/**
 * Normalize every failure to a ProblemError (RFC-7807). On a 401 we assume the
 * session lapsed and bounce to the BFF login — EXCEPT for the `/me` probe, which
 * AuthService fires deliberately to discover logged-out state (a 401 there is the
 * expected "not signed in" answer, not a redirect trigger).
 */
export const problemInterceptor: HttpInterceptorFn = (req, next) => {
  const doc = inject(DOCUMENT);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !req.url.endsWith('/me')) {
        const here = doc.defaultView?.location;
        if (here) {
          const returnTo = encodeURIComponent(here.pathname + here.search);
          here.href = `${LOGIN_URL}?returnTo=${returnTo}`;
        }
      }
      return throwError(() => new ProblemError(err.status, toProblem(err), err));
    }),
  );
};
