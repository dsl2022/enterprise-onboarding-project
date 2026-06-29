import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AccessRequest, MyAccessItem } from '../../core/api/models';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { StatusChipComponent } from '../../shared/components/status-chip.component';
import { ToneChipComponent } from '../../shared/components/tone-chip.component';

/**
 * My access. "Currently held" comes from `/my-access` — the SOURCE OF TRUTH (a
 * removal request reaching GRANTED means "removal completed", so we never infer
 * holdings from request status). "Your requests" tracks in-flight access-requests;
 * `CHANGES_REQUESTED` dead-ends in v1 (no resubmit), so we guide to a new request
 * rather than offer a resubmit that doesn't exist.
 */
@Component({
  selector: 'app-my-access',
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
    ToneChipComponent,
  ],
  templateUrl: './my-access.component.html',
  styleUrl: './my-access.component.scss',
})
export class MyAccessComponent implements OnInit {
  private readonly api = inject(AccessService);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);

  protected readonly held = signal<MyAccessItem[]>([]);
  protected readonly requests = signal<AccessRequest[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly removing = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [held, page] = await Promise.all([
        this.api.listMyAccess(),
        this.api.listAccessRequests({ limit: 50 }),
      ]);
      this.held.set(held);
      this.requests.set(page.items);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load your access.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async requestRemoval(item: MyAccessItem): Promise<void> {
    if (this.removing()) return;
    this.removing.set(item.resourceId);
    try {
      await this.api.requestRemoval(item.resourceId);
      this.snack.open(`Removal requested for ${item.resourceName}.`, undefined, {
        duration: 3500,
        panelClass: 'snack-success',
      });
      await this.load();
    } catch (err) {
      const msg = err instanceof ProblemError ? err.message : 'Could not request removal.';
      this.snack.open(msg, undefined, { duration: 5000, panelClass: 'snack-error' });
    } finally {
      this.removing.set(null);
    }
  }
}
