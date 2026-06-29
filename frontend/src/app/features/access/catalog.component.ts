import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CatalogResource, RESOURCE_TYPES, ResourceType, RISKS, Risk } from '../../core/api/models';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProblemError } from '../../core/http/problem';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { RiskChipComponent } from '../../shared/components/risk-chip.component';
import { ToneChipComponent } from '../../shared/components/tone-chip.component';
import { resourceTypeLabel } from './resource-type-label';
import { RequestAccessDialogComponent } from './request-access-dialog.component';

/**
 * Access catalog — browsable requestable resources (apps, groups, roles, teams),
 * filterable by type and risk. "Request access" opens the focused request flow;
 * the resulting request shares the onboarding lifecycle and approver queue.
 */
@Component({
  selector: 'app-catalog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatSelectModule,
    PageHeaderComponent,
    EmptyStateComponent,
    RiskChipComponent,
    ToneChipComponent,
  ],
  templateUrl: './catalog.component.html',
  styleUrl: './catalog.component.scss',
})
export class CatalogComponent implements OnInit {
  private readonly api = inject(AccessService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);

  protected readonly resourceTypes = RESOURCE_TYPES;
  protected readonly risks = RISKS;
  protected readonly typeLabel = resourceTypeLabel;

  protected readonly items = signal<CatalogResource[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected typeFilter: ResourceType | '' = '';
  protected riskFilter: Risk | '' = '';

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.listCatalog({
        type: this.typeFilter || undefined,
        risk: this.riskFilter || undefined,
        limit: 50,
      });
      this.items.set(page.items);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load the catalog.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async request(resource: CatalogResource): Promise<void> {
    const created = await firstValueFrom(
      this.dialog.open(RequestAccessDialogComponent, { data: resource, width: '460px' }).afterClosed(),
    );
    if (created) {
      this.snack.open(`Request submitted for ${resource.name}.`, undefined, {
        duration: 3500,
        panelClass: 'snack-success',
      });
    }
  }
}
