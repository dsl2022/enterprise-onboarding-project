import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Application, Decision, TimelineEntry } from '../../core/api/models';
import { ApplicationsService } from '../../core/services/applications.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { StatusChipComponent } from '../../shared/components/status-chip.component';
import { LifecycleTimelineComponent } from '../../shared/components/lifecycle-timeline.component';
import { DecisionDialogComponent, DecisionDialogData } from '../review/decision-dialog.component';

const DRAFT_STATES = new Set(['DRAFT', 'CHANGES_REQUESTED']);

/**
 * Application detail: the full record, its lifecycle timeline, and the actions
 * available for its current state + the principal's capabilities. Owners submit
 * drafts; reviewers approve/reject/request-changes — but never their own request
 * (separation of duties, mirrored here, enforced by the server).
 */
@Component({
  selector: 'app-application-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDialogModule,
    PageHeaderComponent,
    StatusChipComponent,
    LifecycleTimelineComponent,
  ],
  templateUrl: './application-detail.component.html',
  styleUrl: './application-detail.component.scss',
})
export class ApplicationDetailComponent implements OnInit {
  /** Bound from the route param via withComponentInputBinding. */
  readonly id = input.required<string>();

  private readonly api = inject(ApplicationsService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);

  protected readonly app = signal<Application | null>(null);
  protected readonly timeline = signal<TimelineEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly acting = signal(false);
  private etag: string | null = null;

  protected readonly isProvisioning = computed(() => this.app()?.status === 'PROVISIONING');
  protected readonly canSubmit = computed(
    () => !!this.app() && DRAFT_STATES.has(this.app()!.status) && this.auth.can('app.submit'),
  );
  protected readonly canDecide = computed(
    () => this.app()?.status === 'UNDER_REVIEW' && this.auth.can('app.decide'),
  );
  /** Separation of duties: the requester (owner) can't approve their own request. */
  protected readonly isOwnRequest = computed(() => {
    const me = this.auth.me();
    const owner = this.app()?.owner;
    return !!me && !!owner && (owner === me.id || owner === me.email || owner === me.name);
  });

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [res, timeline] = await Promise.all([this.api.get(this.id()), this.api.timeline(this.id())]);
      this.app.set(res.value);
      this.etag = res.etag;
      this.timeline.set(timeline);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load the application.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async submit(): Promise<void> {
    if (!this.etag || this.acting()) return;
    this.acting.set(true);
    try {
      await this.api.submit(this.id(), this.etag);
      this.snack.open('Submitted for review.', undefined, { duration: 3000, panelClass: 'snack-success' });
      await this.load();
    } catch (err) {
      this.handleActionError(err, 'submit');
    } finally {
      this.acting.set(false);
    }
  }

  protected async decide(decision: Decision): Promise<void> {
    const app = this.app();
    if (!app || !this.etag || this.acting()) return;
    const data: DecisionDialogData = { decision, resourceName: `${app.name} · ${app.env}` };
    const body = await firstValueFrom(
      this.dialog.open(DecisionDialogComponent, { data, width: '440px' }).afterClosed(),
    );
    if (!body) return;

    this.acting.set(true);
    try {
      await this.api.decide(this.id(), body, this.etag);
      this.snack.open('Decision recorded.', undefined, { duration: 3000, panelClass: 'snack-success' });
      await this.load();
    } catch (err) {
      this.handleActionError(err, 'decision');
    } finally {
      this.acting.set(false);
    }
  }

  private handleActionError(err: unknown, what: string): void {
    if (err instanceof ProblemError && err.isPreconditionFailed) {
      this.snack.open('This changed since you loaded it — refreshed, please retry.', undefined, {
        duration: 4000,
      });
      void this.load();
      return;
    }
    const msg = err instanceof ProblemError ? err.message : `Could not record the ${what}.`;
    this.snack.open(msg, undefined, { duration: 5000, panelClass: 'snack-error' });
  }
}
