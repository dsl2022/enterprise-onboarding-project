import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TeamMember } from '../../core/api/models';
import { TeamsService } from '../../core/services/teams.service';
import { ProblemError } from '../../core/http/problem';

/** Add a member by Entra user id (oid). Re-adding a removed member reactivates them. */
@Component({
  selector: 'app-add-member-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Add member</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full">
        <mat-label>User ID (Entra object id)</mat-label>
        <input matInput [(ngModel)]="userId" required />
        <mat-hint>The member's directory object id (oid).</mat-hint>
      </mat-form-field>
      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close [disabled]="submitting()">Cancel</button>
      <button mat-flat-button color="primary" [disabled]="submitting() || !userId.trim()" (click)="submit()">
        {{ submitting() ? 'Adding…' : 'Add' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`.full { width: 100%; min-width: 380px; } .error { color: var(--status-danger-fg); margin: 0; font-size: 13px; }`],
})
export class AddMemberDialogComponent {
  protected readonly teamId = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<AddMemberDialogComponent, TeamMember>);
  private readonly api = inject(TeamsService);

  protected userId = '';
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    if (!this.userId.trim() || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    try {
      this.ref.close(await this.api.addMember(this.teamId, { userId: this.userId.trim() }));
    } catch (err) {
      this.error.set(
        err instanceof ProblemError
          ? err.isForbidden
            ? 'Only the team creator (or an admin) can add members.'
            : err.message
          : 'Could not add the member.',
      );
      this.submitting.set(false);
    }
  }
}
