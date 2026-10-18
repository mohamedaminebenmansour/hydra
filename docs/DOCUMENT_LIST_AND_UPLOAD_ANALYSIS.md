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
1. `DocumentRepository` paged JPQL (`searchAll` / `JOIN FETCH` of `tags` + `revisions`)
   plus `open-in-view=false` caused list 500s (lazy init first; then Hibernate 7
   rejecting pagination over collection fetches).
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

Upload code stays untouched.

**Do not JOIN FETCH `tags` and `revisions` on paged list queries.** Hibernate 6.2+/7
(`fail_on_pagination_over_collection_fetch`) throws when `GET .../documents?page=&size=`
uses collection fetches — that is HTTP 500 while upload still works. List/register use
`DocumentSpecifications` (to-one fetch of folder→project only; optional filters omitted
when null). Collections are initialized in the service transaction (`@BatchSize`).

### Repository (`DocumentRepository`)
Single-document reads fetch folder→project only:

```jpql
select d from Document d
join fetch d.folder f
join fetch f.project
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
- `search()` / `register()` use `DocumentSpecifications` + `findAll(spec, pageable)`.
- DTO mapping happens inside the transaction after `Hibernate.initialize` of
  tags/revisions (batched).
- `get()` / `byReference()` keep FAILED-logging wrappers.
- Upload persist/storage/auditor: **unchanged**.

### Mapper / entity
- `DocumentMapper`: javadoc only.
- `Document`: `@BatchSize` on `tags` / `revisions` only — cascade for upload untouched.

## 4. Files changed / not changed

| File | Change |
|------|--------|
| `document/repository/DocumentSpecifications.java` | Criteria list/register (no collection fetch, no null binds) |
| `document/repository/DocumentRepository.java` | `JpaSpecificationExecutor`; single-doc JOIN FETCH folder→project only |
| `document/service/DocumentService.java` | search/register via specifications; in-tx collection init |
| `document/domain/Document.java` | `@BatchSize` on tags/revisions |
| `docs/DOCUMENT_LIST_AND_UPLOAD_ANALYSIS.md` | this file |

**Must NOT change:** `DocumentService.upload(...)` persist/storage flow,
`LocalFileStorage`, `S3FileStorage`, `AuditorAwareImpl`, `Document` cascade mappings,
frontend.

**Must NOT regress:** never `JOIN FETCH` `tags` and `revisions` on a `Page` query.

## 5. Résumé en français

- **Upload (corrigé — ne pas régresser)** : `AuditorAwareImpl` déclenchait une requête
  JPA (`findByEmail`) pendant le flush de Hibernate → auto-flush → cascade `revisions`
  → audit encore → `StackOverflowError`. Correctif : `JdbcTemplate` (aucune requête JPA
  dans l'auditeur — règle permanente).
- **GET /documents (500 alors que l'upload marche)** : Hibernate 7 refuse la pagination
  combinée à un `JOIN FETCH` de collections (`tags`, `revisions`). Les filtres JPQL
  `:param is null or ...` cassent aussi PostgreSQL (bind `NULL` sans type).
- **Correctif** : Criteria (`DocumentSpecifications`) — fetch folder→project seulement,
  filtres optionnels omis si null, collections initialisées dans la transaction
  (`@BatchSize`). Ne jamais réintroduire un fetch de collections sur une requête paginée.
