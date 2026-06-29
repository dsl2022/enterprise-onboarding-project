import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { routes } from './app.routes';
import { credentialsInterceptor, problemInterceptor } from './core/http/http.interceptors';
import { mockMeInterceptor } from './core/http/mock-me.interceptor';
import { environment } from '../environments/environment';

// Mock runs first so it can short-circuit identity calls in dev; it's excluded
// (and tree-shaken) when environment.useMockMe is false, i.e. in production.
const interceptors = [
  ...(environment.useMockMe ? [mockMeInterceptor] : []),
  credentialsInterceptor,
  problemInterceptor,
];

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideAnimationsAsync(),
    provideHttpClient(withInterceptors(interceptors)),
  ],
};
