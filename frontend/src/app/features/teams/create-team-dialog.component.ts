import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Team, TeamCreate } from '../../core/api/models';
import { TeamsService } from '../../core/services/teams.service';
import { ProblemError } from '../../core/http/problem';

/** Create a team. Tenant-unique name (duplicate → 409, surfaced inline). */
@Component({
  selector: 'app-create-team-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>New team</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full">
        <mat-label>Name</mat-label>
        <input matInput [(ngModel)]="name" required />
      </mat-form-field>
      <mat-form-field appearance="outline" class="full">
        <mat-label>Description</mat-label>
        <textarea matInput [(ngModel)]="description" rows="2"></textarea>
      </mat-form-field>
      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close [disabled]="submitting()">Cancel</button>
      <button mat-flat-button color="primary" [disabled]="submitting() || !name.trim()" (click)="submit()">
        {{ submitting() ? 'Creating…' : 'Create' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`.full { width: 100%; min-width: 360px; } .error { color: var(--status-danger-fg); margin: 0; font-size: 13px; }`],
})
export class CreateTeamDialogComponent {
  private readonly ref = inject(MatDialogRef<CreateTeamDialogComponent, Team>);
  private readonly api = inject(TeamsService);

  protected name = '';
  protected description = '';
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    if (!this.name.trim() || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    const payload: TeamCreate = { name: this.name.trim(), description: this.description.trim() || undefined };
    try {
      this.ref.close(await this.api.create(payload));
    } catch (err) {
      this.error.set(
        err instanceof ProblemError
          ? err.isConflict
            ? 'A team with that name already exists.'
            : err.message
          : 'Could not create the team.',
      );
      this.submitting.set(false);
    }
  }
}
