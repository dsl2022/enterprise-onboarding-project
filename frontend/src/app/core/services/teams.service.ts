import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { Team, TeamCreate, TeamMember, TeamMemberAdd } from '../api/models';
import { newIdempotencyKey, writeHeaders } from '../http/request-options';

/**
 * Client for the live `/teams` endpoints (Phase 5c — portal-local CRUD, no
 * request engine). Notes from the contract: `GET /teams` is a plain array
 * (role-scoped: teams you created OR are a member of); creating/adding take an
 * Idempotency-Key; member-add is idempotent (re-add reactivates); duplicate team
 * name → 409. There is no team-delete in v1. `TeamMember.name` is null (no
 * directory lookup) — render the userId.
 */
@Injectable({ providedIn: 'root' })
export class TeamsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/teams`;

  list(): Promise<Team[]> {
    return firstValueFrom(this.http.get<Team[]>(this.base));
  }

  create(payload: TeamCreate, idempotencyKey = newIdempotencyKey()): Promise<Team> {
    return firstValueFrom(
      this.http.post<Team>(this.base, payload, { headers: writeHeaders({ idempotencyKey }) }),
    );
  }

  members(teamId: string): Promise<TeamMember[]> {
    return firstValueFrom(this.http.get<TeamMember[]>(`${this.base}/${teamId}/members`));
  }

  addMember(
    teamId: string,
    payload: TeamMemberAdd,
    idempotencyKey = newIdempotencyKey(),
  ): Promise<TeamMember> {
    return firstValueFrom(
      this.http.post<TeamMember>(`${this.base}/${teamId}/members`, payload, {
        headers: writeHeaders({ idempotencyKey }),
      }),
    );
  }

  removeMember(teamId: string, userId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`${this.base}/${teamId}/members/${encodeURIComponent(userId)}`),
    );
  }
}
