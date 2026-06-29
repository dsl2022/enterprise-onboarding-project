import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Decision, RequestKind, ReviewItem } from '../../core/api/models';
import { ReviewService } from '../../core/services/review.service';
import { ApplicationsService } from '../../core/services/applications.service';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ToneChipComponent } from '../../shared/components/tone-chip.component';
import { DecisionDialogComponent, DecisionDialogData } from './decision-dialog.component';

/**
 * Unified review queue — items awaiting a decision across BOTH request types.
 * The same approve/request-changes/reject interaction (the shared decision dialog)
 * drives onboarding and access alike; deciding fetches the resource's ETag first.
 * Separation of duties is mirrored (you can't decide your own request; the server
 * enforces on the real principal).
 */
@Component({
  selector: 'app-review-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatButtonToggleModule,
    MatTooltipModule,
    PageHeaderComponent,
    EmptyStateComponent,
    ToneChipComponent,
  ],
  templateUrl: './review-queue.component.html',
  styleUrl: './review-queue.component.scss',
})
export class ReviewQueueComponent implements OnInit {
  private readonly review = inject(ReviewService);
  private readonly apps = inject(ApplicationsService);
  private readonly access = inject(AccessService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);

  protected readonly items = signal<ReviewItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly acting = signal<string | null>(null);
  protected typeFilter: RequestKind | '' = '';

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.review.list({ type: this.typeFilter || undefined, limit: 50 });
      this.items.set(page.items);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load the review queue.');
    } finally {
      this.loading.set(false);
    }
  }

  protected canDecide(item: ReviewItem): boolean {
    return item.kind === 'onboarding' ? this.auth.can('app.decide') : this.auth.can('access.decide');
  }

  /** SoD mirror: the requester can't approve their own request. */
  protected isOwnRequest(item: ReviewItem): boolean {
    const me = this.auth.me();
    return (
      !!me &&
      (item.requester === me.id || item.requester === me.email || item.requester === me.name)
    );
  }

  protected typeLabel(kind: RequestKind): string {
    return kind === 'onboarding' ? 'Onboarding' : 'Access';
  }

  protected async decide(item: ReviewItem, decision: Decision): Promise<void> {
    if (this.acting() || this.isOwnRequest(item) || !this.canDecide(item)) return;
    const data: DecisionDialogData = { decision, resourceName: item.title };
    const body = await firstValueFrom(
      this.dialog.open(DecisionDialogComponent, { data, width: '440px' }).afterClosed(),
    );
    if (!body) return;

    this.acting.set(item.id);
    try {
      // Fetch the live resource for its ETag, then decide — identical shape per type.
      if (item.kind === 'onboarding') {
        const current = await this.apps.get(item.id);
        await this.apps.decide(item.id, body, current.etag ?? '');
      } else {
        const current = await this.access.getAccessRequest(item.id);
        await this.access.decideAccessRequest(item.id, body, current.etag ?? '');
      }
      this.snack.open('Decision recorded.', undefined, { duration: 3000, panelClass: 'snack-success' });
      await this.load();
    } catch (err) {
      if (err instanceof ProblemError && err.isPreconditionFailed) {
        this.snack.open('This changed since you loaded it — refreshing the queue.', undefined, {
          duration: 4000,
        });
        await this.load();
      } else {
        const msg = err instanceof ProblemError ? err.message : 'Could not record the decision.';
        this.snack.open(msg, undefined, { duration: 5000, panelClass: 'snack-error' });
      }
    } finally {
      this.acting.set(null);
    }
  }
}
