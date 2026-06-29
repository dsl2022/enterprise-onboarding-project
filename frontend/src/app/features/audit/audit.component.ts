import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuditEvent, AuditVerifyResult, Role } from '../../core/api/models';
import { AuditService } from '../../core/services/audit.service';
import { ProblemError } from '../../core/http/problem';
import { roleLabel } from '../../shared/role-label';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';

/**
 * Audit log viewer. Shows the hash-chained, tamper-evident trail plus a chain
 * integrity badge from /audit/verify. Notes baked in: `actor` is the real
 * principal (Super Admin even while impersonating; `effectiveRole` is the
 * impersonated role), and `seq` is an ordering key with possible gaps — never a
 * count. The log is eventually-consistent, so a just-taken action may take a
 * moment to appear.
 */
@Component({
  selector: 'app-audit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    PageHeaderComponent,
    EmptyStateComponent,
  ],
  templateUrl: './audit.component.html',
  styleUrl: './audit.component.scss',
})
export class AuditComponent implements OnInit {
  private readonly api = inject(AuditService);

  protected readonly events = signal<AuditEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly nextCursor = signal<string | null>(null);

  protected readonly verifyResult = signal<AuditVerifyResult | null>(null);
  protected readonly verifying = signal(false);

  protected readonly expanded = signal<ReadonlySet<string>>(new Set());

  protected actor = '';
  protected type = '';
  protected resource = '';

  ngOnInit(): void {
    void this.load();
    void this.runVerify();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.list({
        actor: this.actor.trim() || undefined,
        type: this.type.trim() || undefined,
        resource: this.resource.trim() || undefined,
        limit: 25,
      });
      this.events.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load the audit log.');
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    try {
      const page = await this.api.list({
        actor: this.actor.trim() || undefined,
        type: this.type.trim() || undefined,
        resource: this.resource.trim() || undefined,
        cursor,
        limit: 25,
      });
      this.events.update((cur) => [...cur, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Failed to load more.');
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async runVerify(): Promise<void> {
    this.verifying.set(true);
    try {
      this.verifyResult.set(await this.api.verify());
    } catch {
      this.verifyResult.set(null);
    } finally {
      this.verifying.set(false);
    }
  }

  protected toggle(id: string): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
  }

  protected roleText(role: Role): string {
    return roleLabel(role);
  }

  protected detailEntries(event: AuditEvent): { key: string; value: string }[] {
    const detail = event.detail ?? {};
    return Object.entries(detail).map(([key, value]) => ({ key, value: String(value) }));
  }

  protected short(hash: string | undefined): string {
    return hash ? hash.slice(0, 12) + '…' : '—';
  }
}
