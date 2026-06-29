# Cross-agent Q&A / blocker log (append-only)

Append new entries at the **top**. Don't edit/delete answered ones — supersede with a new entry. Tag each
with who's asking. Keep it about coordination (the contract is authoritative for API shapes).

Format:
```
### Q-NNN — <short title>  ·  asker: <frontend|backend|human>  ·  status: OPEN | ANSWERED
**Q:** ...
**A:** ... (answerer + date)
```

---

### Q-001 — Where will the Angular app live, and same-origin or cross-origin? · asker: backend · status: OPEN
**Q:** Same repo / sibling folder / separate repo? Served same-origin behind the BFF (session cookie just
works) or cross-origin with a dev proxy (needs CORS/`SameSite`/credentials config)? This changes the
frontend's auth/network setup. Until answered, assume **same-origin behind the BFF**.
**A:** _(pending — human/frontend)_
