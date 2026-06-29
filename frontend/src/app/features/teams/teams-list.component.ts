import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog } from '@angular/material/dialog';
import { Team } from '../../core/api/models';
import { TeamsService } from '../../core/services/teams.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { CreateTeamDialogComponent } from './create-team-dialog.component';

/**
 * Teams list — role-scoped (teams you created or are a member of). "New team"
 * needs `team.manage`. Portal-local in v1; no team deletion.
 */
@Component({
  selector: 'app-teams-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    PageHeaderComponent,
    EmptyStateComponent,
  ],
  templateUrl: './teams-list.component.html',
  styleUrl: './teams-list.component.scss',
})
export class TeamsListComponent implements OnInit {
  private readonly api = inject(TeamsService);
  private readonly dialog = inject(MatDialog);
  protected readonly auth = inject(AuthService);

  protected readonly teams = signal<Team[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.teams.set(await this.api.list());
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load teams.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async newTeam(): Promise<void> {
    const created = await firstValueFrom(
      this.dialog.open(CreateTeamDialogComponent, { width: '440px' }).afterClosed(),
    );
    if (created) {
      await this.load();
    }
  }
}
