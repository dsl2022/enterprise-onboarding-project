import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AccessRequest, AccessRequestCreate, CatalogResource } from '../../core/api/models';
import { AccessService } from '../../core/services/access.service';
import { ProblemError } from '../../core/http/problem';
import { RiskChipComponent } from '../../shared/components/risk-chip.component';

/** Duration is informational in v1 (no auto-expiry) — offered as a hint only. */
const DURATIONS: { label: string; value: string | null }[] = [
  { label: 'Permanent', value: null },
  { label: '30 days', value: 'P30D' },
  { label: '90 days', value: 'P90D' },
];

/**
 * Focused request-access flow (lighter than the onboarding wizard): the selected
 * resource is read-only, business justification is required, duration is optional.
 * On submit it enters the shared lifecycle and appears in the approver queue.
 */
@Component({
  selector: 'app-request-access-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    RiskChipComponent,
  ],
  template: `
    <h2 mat-dialog-title>Request access</h2>
    <mat-dialog-content>
      <div class="resource">
        <div class="resource-head">
          <span class="resource-name">{{ data.name }}</span>
          <app-risk-chip [risk]="data.risk" />
        </div>
        <p class="text-muted resource-desc">{{ data.description }}</p>
      </div>

      <mat-form-field appearance="outline" class="full">
        <mat-label>Business justification</mat-label>
        <textarea matInput [(ngModel)]="justification" rows="3" required></textarea>
      </mat-form-field>

      <mat-form-field appearance="outline" class="full">
        <mat-label>Duration</mat-label>
        <mat-select [(ngModel)]="duration">
          @for (d of durations; track d.label) {
            <mat-option [value]="d.value">{{ d.label }}</mat-option>
          }
        </mat-select>
        <mat-hint>Informational in v1 — access isn't auto-expired.</mat-hint>
      </mat-form-field>

      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close [disabled]="submitting()">Cancel</button>
      <button
        mat-flat-button
        color="primary"
        [disabled]="submitting() || !justification.trim()"
        (click)="submit()"
      >
        {{ submitting() ? 'Submitting…' : 'Submit request' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .resource {
        background: var(--surface-sunken);
        border-radius: var(--radius-md);
        padding: var(--space-3) var(--space-4);
        margin-bottom: var(--space-4);
      }
      .resource-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-2);
      }
      .resource-name {
        font-weight: 600;
      }
      .resource-desc {
        margin: var(--space-1) 0 0;
        font-size: 13px;
      }
      .full {
        width: 100%;
        min-width: 380px;
      }
      .error {
        color: var(--status-danger-fg);
        font-size: 13px;
        margin: 0;
      }
    `,
  ],
})
export class RequestAccessDialogComponent {
  protected readonly data = inject<CatalogResource>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<RequestAccessDialogComponent, AccessRequest>);
  private readonly api = inject(AccessService);

  protected readonly durations = DURATIONS;
  protected justification = '';
  protected duration: string | null = null;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    if (!this.justification.trim() || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    const payload: AccessRequestCreate = {
      resourceId: this.data.id,
      justification: this.justification.trim(),
      duration: this.duration,
    };
    try {
      const created = await this.api.createAccessRequest(payload);
      this.ref.close(created);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Could not submit the request.');
      this.submitting.set(false);
    }
  }
}
