import { DOCUMENT } from '@angular/common';
import { effect, inject, Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'eop.theme';

/**
 * Light/dark theme. Persists the user's choice; falls back to the OS preference
 * on first run. Applying = setting `data-theme` on <html>, which flips the CSS
 * custom properties in _tokens.scss (and Material's color set in styles.scss).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  readonly mode = signal<ThemeMode>(this.initial());

  constructor() {
    effect(() => {
      const mode = this.mode();
      this.doc.documentElement.setAttribute('data-theme', mode);
      this.doc.defaultView?.localStorage?.setItem(STORAGE_KEY, mode);
    });
  }

  toggle(): void {
    this.mode.update((m) => (m === 'dark' ? 'light' : 'dark'));
  }

  private initial(): ThemeMode {
    const stored = this.doc.defaultView?.localStorage?.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
    const prefersDark = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)').matches;
    return prefersDark ? 'dark' : 'light';
  }
}
