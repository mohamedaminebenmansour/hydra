# Document Upload & List — Analysis

> Team reference: why `POST .../documents/upload` needed an auditor fix, why
> `GET /api/projects/{projectId}/documents` returned 500, and what was changed.
> **Do not regress the upload fix.**

## 1. What happened on upload (ALREADY FIXED — do not regress)

### Symptom
`POST /api/projects/{projectId}/folders/{folderId}/documents/upload` crashed with
`StackOverflowError` during `em.flush()`.

### Root cause — infinite recursion through auditing
1. `DocumentService.upload()` persists a `Document` together with its first
   `DocumentRevision` (`em.persist(doc); em.flush()`).
2. During the flush, Hibernate populates `@CreatedBy` via `AuditingEntityListener` →
   `AuditorAwareImpl.getCurrentAuditor()`.
3. The original `AuditorAwareImpl` resolved the user with a **JPA query**
   (`users.findByEmail()`).
4. Issuing a JPA query from inside the flush makes Hibernate **auto-flush pending
   entities first** → cascade `Document.revisions` → `AuditingEntityListener` again →
   `getCurrentAuditor()` again → … → `StackOverflowError`.

### Fix applied (already in production — keep it)
`AuditorAwareImpl` now resolves the auditor with **`JdbcTemplate`** (plain SQL against
`app_user`). JDBC bypasses the Hibernate session entirely, so no auto-flush can be
triggered from within auditing, and the recursion loop is broken.

### Rules for the team
- **Never** call a JPA repository or `EntityManager` query inside `AuditorAwareImpl`.
- **Never** reintroduce `users.findByEmail()` inside `getCurrentAuditor()`.
- `DocumentService.upload(...)`, `LocalFileStorage` / `S3FileStorage` and the `Document`
  `revisions` cascade (`CascadeType.ALL`) are **out of scope** for list/read fixes.

## 2. What was wrong on GET /documents (this task)

### Configuration
`spring.jpa.open-in-view=false` — there is **no** JPA session kept open past the service
transaction boundary (no `OpenEntityManagerInViewFilter`).

### Bug
1. `DocumentRepository.searchAll / searchInFolders / registerAll / registerInFolders`
   loaded `Document` entities **without** initializing `folder`, `folder.project`,
   `tags` and `revisions` (all mapped `LAZY`).
2. `DocumentMapper.toResponse()` reads `folder.id`, `folder.project.id`, `tags` and
   `revisions.size()`. When the page had rows, those lazy associations were touched
   without a reliably initialized session → `LazyInitializationException` → HTTP 500
   (`GlobalExceptionHandler` maps it to code `LAZY_INIT`).
3. Expected user-visible behaviour before the fix: **upload succeeded, then the document
   list failed (500)** — the two bugs are independent, so one being fixed says nothing
   about the other.

### Diagnosis logging (kept + extended)
- `[DOC-SEARCH] FAILED ...` / `[DOC-REGISTER] FAILED ...` — already existed, kept.
- `[DOC-GET] FAILED ...` / `[DOC-BYREF] FAILED ...` — added by this fix.
All four log `e.getClass().getSimpleName()` so a `LazyInitializationException` is
obvious in the logs (STEP 1 of the fix: prove the failure).

## 3. How we solved GET (this task)

Upload code stays untouched; read paths now return DTOs without any lazy access.

### Repository (`DocumentRepository`)
All six read queries use the same eager-loading graph:

```jpql
select distinct d from Document d
join fetch d.folder f
join fetch f.project
left join fetch d.tags
left join fetch d.revisions
where ...
```

- `join fetch f.project` → `projectId` in the DTO without lazy loads.
- `left join fetch d.tags` / `d.revisions` → `tags` + `revisionCount` without N+1.
- `select distinct` → collection fetches multiply SQL rows; Hibernate applies the
  DISTINCT to the **entity results in memory**, so each `Document` appears once
  (confirmed for Hibernate 6/7 semantics; see Vlad Mihalcea, "The best way to use the
  JPQL DISTINCT keyword with JPA and Hibernate").
- The four `Page` queries declare an explicit **`countQuery` without fetch joins** —
  no row multiplication there, so `count(d)` is exact and Spring Data never has to
  derive a count over fetch joins.
- `findByIdAndOrgId` / `findByOrgIdAndReferenceIgnoreCase` were converted from derived
  queries to explicit `@Query` with the same eager graph (used by `get()` /
  `byReference()`; signatures unchanged, all other callers keep working).

### Service (`DocumentService` — search/register/get/byReference only)
- DTO mapping already happened inside the `@Transactional(readOnly = true)` boundary;
  that stays.
- `get()` and `byReference()` now wrap fetch + mapping in try/catch with
  `[DOC-GET] FAILED` / `[DOC-BYREF] FAILED` logs (exception class name included).
- `upload()`, `create()`, `update()`, `linkTicket()`, `softDelete()`, storage and
  auditor code: **unchanged**.

### Mapper / entity
- `DocumentMapper`: javadoc updated only (read paths are eager at repository level;
  ticket id is proxy-safe via `getId()`).
- `Document`: **no changes** — upload cascade untouched.

## 4. Files changed / not changed

| File | Change |
|------|--------|
| `document/repository/DocumentRepository.java` | JOIN FETCH + DISTINCT + explicit countQuery on all 6 read queries |
| `document/service/DocumentService.java` | `get()` / `byReference()` FAILED-logging wrappers only |
| `document/dto/DocumentMapper.java` | javadoc only |
| `docs/DOCUMENT_LIST_AND_UPLOAD_ANALYSIS.md` | this file (created) |

**Must NOT change:** `DocumentService.upload(...)` body and storage flow,
`LocalFileStorage`, `S3FileStorage`, `AuditorAwareImpl`, `Document` cascade mappings,
frontend.

## 5. Résumé en français

- **Upload (corrigé — ne pas régresser)** : `AuditorAwareImpl` déclenchait une requête
  JPA (`findByEmail`) pendant le flush de Hibernate → auto-flush → cascade `revisions`
  → audit encore → `StackOverflowError`. Correctif : `JdbcTemplate` (aucune requête JPA
  dans l'auditeur — règle permanente).
- **GET /documents (corrigé ici)** : `open-in-view=false` + requêtes sans `JOIN FETCH` →
  `DocumentMapper` touchait `folder.project`, `tags`, `revisions` non initialisés →
  `LazyInitializationException` → 500 (alors que l'upload réussissait — c'est attendu :
  deux bugs indépendants).
- **Correctif** : `SELECT DISTINCT` + `JOIN FETCH folder→project` + `LEFT JOIN FETCH
  tags, revisions` (avec `countQuery` explicite sans fetch) sur toutes les requêtes de
  lecture ; mapping DTO dans la transaction ; logs `[DOC-GET]` / `[DOC-BYREF] FAILED`
  ajoutés. L'upload, le stockage et l'auditeur n'ont **pas** été touchés.
