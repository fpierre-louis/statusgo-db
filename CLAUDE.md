# SitPrep back end (sitprepapi 2)

Spring Boot API for SitPrep (Status Now, LLC). Read the workspace map first:
`/Users/dione/Desktop/MS Bootcamp/CLAUDE.md`. The state-of-the-app reference is
`../SitPrep FE/CLAUDE.md`.

- **This repo holds back-end code only.** Docs (epics, EXEC ledgers, audits, the traps
  doc) live in `/Users/dione/Desktop/MS Bootcamp/SitPrep Docs`, under the same `docs/...`
  paths that comments here cite. The main checkout's `docs` is a symlink there; worktrees
  have none, so write docs by absolute path and commit in SitPrep Docs with a pathspec.
- **Traps:** `docs/architecture/SYSTEM_TRAPS_AND_PATTERNS.md` is one shared file. A
  back-end comment citing T-1…T-5 for a Flyway, `task_assignee`, `PostKind` or HQL-heap
  trap means the "BE T-n" section; other numbers mean the shared entries.
- **Never edit an applied migration** (`src/main/resources/db/migration/V*.sql`), not
  even a comment: Flyway checksums the whole file and the dyno fails to boot.
- **Before every push:** run `./mvnw package` on the exact commit (Heroku compiles the
  tests) and push only if it passes. Pushes to `main` auto-deploy to Heroku (prod). Locally
  set `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` to any value; a
  worktree also needs the gitignored Firebase admin JSON copied into `src/main/resources/`.
