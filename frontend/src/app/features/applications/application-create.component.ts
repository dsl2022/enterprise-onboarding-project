import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApplicationCreate, GRANT_TYPES } from '../../core/api/models';
import { ApplicationsService } from '../../core/services/applications.service';
import { ProblemError } from '../../core/http/problem';
import { newIdempotencyKey } from '../../core/http/request-options';
import { PageHeaderComponent } from '../../shared/components/page-header.component';

/**
 * Register a new internal app for SSO. Creates a DRAFT (optionally submitting it
 * for review in the same action). `name`/`env` are immutable after create, so
 * they're captured here; everything else can still be edited on the draft.
 */
@Component({
  selector: 'app-application-create',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    PageHeaderComponent,
  ],
  templateUrl: './application-create.component.html',
  styleUrl: './application-create.component.scss',
})
export class ApplicationCreateComponent {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApplicationsService);
  private readonly router = inject(Router);

  protected readonly grantTypes = GRANT_TYPES;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', Validators.required],
    env: ['dev', Validators.required],
    description: [''],
    grants: [['authorization_code'] as string[]],
    scopesText: [''],
    urisText: [''],
    group: [''],
    teamText: [''],
  });

  protected async save(submitAfter: boolean): Promise<void> {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    const payload: ApplicationCreate = {
      name: v.name.trim(),
      env: v.env.trim(),
      description: v.description.trim() || undefined,
      grants: v.grants as ApplicationCreate['grants'],
      scopes: splitList(v.scopesText),
      uris: splitList(v.urisText),
      group: v.group.trim() || undefined,
      team: splitList(v.teamText),
    };

    try {
      const created = await this.api.create(payload, newIdempotencyKey());
      if (submitAfter && created.etag) {
        await this.api.submit(created.value.id, created.etag);
      }
      await this.router.navigate(['/applications', created.value.id]);
    } catch (err) {
      this.error.set(err instanceof ProblemError ? err.message : 'Could not create the application.');
    } finally {
      this.submitting.set(false);
    }
  }

  protected cancel(): void {
    void this.router.navigate(['/applications']);
  }
}

/** Split a textarea (newline- or comma-separated) into a trimmed, non-empty list. */
function splitList(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean);
}
