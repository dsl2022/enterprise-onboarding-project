import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Team, TeamMember } from '../../core/api/models';
import { TeamsService } from '../../core/services/teams.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { AddMemberDialogComponent } from './add-member-dialog.component';

/**
 * Team detail + roster. There's no single-team GET in the contract, so the team
 * summary is derived from the (role-scoped) list. Member management is owner-only
 * server-side; we show the controls for `team.manage` holders and surface a 403
 * cleanly if a non-creator tries. `TeamMember.name` is null in v1 → show the id.
 */
@Component({
  selector: 'app-team-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    PageHeaderComponent,
    EmptyStateComponent,
  ],
  templateUrl: './team-detail.component.html',
  styleUrl: './team-detail.component.scss',
})
export class TeamDetailComponent implements OnInit {
  readonly id = input.required<string>();

  private readonly api = inject(TeamsService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);

  protected readonly team = signal<Team | null>(null);
  protected readonly members = signal<TeamMember[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly acting = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  /** Resolve a member for display. v1 has no directory lookup, so we only know the
   *  oid — except the current user, whom we can name from /me. */
  protected isYou(member: TeamMember): boolean {
    return this.auth.me()?.id === member.userId;
  }

  protected displayName(member: TeamMember): string {
    if (this.isYou(member)) {
      return 'You';
    }
    return member.name ?? member.userId;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [teams, members] = await Promise.all([this.api.list(), this.api.members(this.id())]);
      this.team.set(teams.find((t) => t.id === this.id()) ?? null);
      this.members.set(members);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load the team.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async addMember(): Promise<void> {
    const added = await firstValueFrom(
      this.dialog.open(AddMemberDialogComponent, { data: this.id(), width: '440px' }).afterClosed(),
    );
    if (added) {
      this.snack.open('Member added.', undefined, { duration: 2500, panelClass: 'snack-success' });
      await this.load();
    }
  }

  protected async removeMember(member: TeamMember): Promise<void> {
    if (this.acting()) return;
    this.acting.set(member.userId);
    try {
      await this.api.removeMember(this.id(), member.userId);
      this.snack.open('Member removed.', undefined, { duration: 2500, panelClass: 'snack-success' });
      await this.load();
    } catch (err) {
      const msg =
        err instanceof ProblemError
          ? err.isForbidden
            ? 'Only the team creator (or an admin) can remove members.'
            : err.message
          : 'Could not remove the member.';
      this.snack.open(msg, undefined, { duration: 5000, panelClass: 'snack-error' });
    } finally {
      this.acting.set(null);
    }
  }
}
