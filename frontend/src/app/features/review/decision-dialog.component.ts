import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Decision, DecisionBody } from '../../core/api/models';

export interface DecisionDialogData {
  decision: Decision;
  resourceName: string;
}

const TITLES: Record<Decision, string> = {
  APPROVE: 'Approve request',
  REJECT: 'Reject request',
  REQUEST_CHANGES: 'Request changes',
};

const CONFIRM_LABELS: Record<Decision, string> = {
  APPROVE: 'Approve',
  REJECT: 'Reject',
  REQUEST_CHANGES: 'Request changes',
};

/**
 * Shared approve / reject / request-changes confirmation. Returns a DecisionBody
 * (or undefined on cancel) so both the application detail and the review queue
 * drive the identical decision interaction. A reason is required for reject and
 * request-changes (the requester needs to know why), optional for approve.
 */
@Component({
  selector: 'app-decision-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ title }}</h2>
    <mat-dialog-content>
      <p class="text-muted">
        {{ data.resourceName }}
      </p>
      <mat-form-field appearance="outline" class="reason">
        <mat-label>{{ reasonRequired ? 'Reason' : 'Reason (optional)' }}</mat-label>
        <textarea matInput [(ngModel)]="reason" rows="3"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        [color]="data.decision === 'REJECT' ? 'warn' : 'primary'"
        [disabled]="reasonRequired && !reason.trim()"
        (click)="confirm()"
      >
        {{ confirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`.reason { width: 100%; min-width: 360px; } p { margin-top: 0; }`],
})
export class DecisionDialogComponent {
  protected readonly data = inject<DecisionDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DecisionDialogComponent, DecisionBody>);

  protected reason = '';
  protected readonly title = TITLES[this.data.decision];
  protected readonly confirmLabel = CONFIRM_LABELS[this.data.decision];
  protected readonly reasonRequired = this.data.decision !== 'APPROVE';

  protected confirm(): void {
    this.ref.close({ decision: this.data.decision, reason: this.reason.trim() || undefined });
  }
}
