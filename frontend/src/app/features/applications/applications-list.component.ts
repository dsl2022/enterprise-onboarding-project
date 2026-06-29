import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Application } from '../../core/api/models';
import { ApplicationsService } from '../../core/services/applications.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { StatusChipComponent } from '../../shared/components/status-chip.component';

/**
 * Applications list — onboarding requests, role-scoped server-side (owners see
 * their own). Cursor-paginated. The "New application" action shows only with
 * `app.create`; everyone with `app.read` can open a row.
 */
@Component({
  selector: 'app-applications-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    PageHeaderComponent,
    EmptyStateComponent,
    StatusChipComponent,
  ],
  templateUrl: './applications-list.component.html',
  styleUrl: './applications-list.component.scss',
})
export class ApplicationsListComponent implements OnInit {
  private readonly api = inject(ApplicationsService);
  protected readonly auth = inject(AuthService);

  protected readonly apps = signal<Application[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly nextCursor = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.list({ limit: 20 });
      this.apps.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load applications.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    try {
      const page = await this.api.list({ cursor, limit: 20 });
      this.apps.update((cur) => [...cur, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load more.');
    } finally {
      this.loadingMore.set(false);
    }
  }
}
