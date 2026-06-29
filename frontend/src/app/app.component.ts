import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from './core/auth/auth.service';
import { ThemeService } from './core/theme/theme.service';
import { ShellComponent } from './layout/shell.component';

@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, ShellComponent, MatProgressSpinnerModule],
  template: `
    @if (!auth.loaded()) {
      <div class="splash">
        <mat-spinner diameter="36" />
        <span class="text-muted">Loading…</span>
      </div>
    } @else if (auth.isAuthenticated()) {
      <app-shell />
    } @else {
      <!-- Signed out: render the public route (login) without the app chrome. -->
      <router-outlet />
    }
  `,
  styles: [
    `
      .splash {
        height: 100vh;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: var(--space-4);
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  protected readonly auth = inject(AuthService);

  // Inject ThemeService eagerly so its effect applies data-theme on startup.
  private readonly theme = inject(ThemeService);

  ngOnInit(): void {
    void this.auth.ensureLoaded();
  }
}
