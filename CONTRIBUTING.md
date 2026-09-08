# Contributing

## Authoritative project inputs

- Source provenance: `SOURCE_PROVENANCE.md`
- Current audit: `项目文档/T00-仓库基线审计-2026-09-08.md`
- Current task plan: `项目文档/T01-正式任务拆分与执行顺序-V1.0.md`
- Live status: `项目文档/项目状态看板.md`
- Controlled backend contracts: `后端程序/docs/zhongshu-design/`

Historical reports are evidence of earlier work, not the current source of truth. Current completion claims must link to a reachable commit and a reproducible test run.

## Branches

- `main` is the integration and release-candidate branch. Do not develop directly on it.
- Normal work: `task/Txx-yy-short-name`.
- Urgent production correction: `hotfix/Txx-yy-short-name`.
- Archival imports: `archive/<source>-<date>`; archival imports must not silently rewrite `main`.

One branch should implement one task ID. If a change cannot be reviewed independently, split the task before coding.

## Commits

Use a concise Chinese subject and include the task ID. Recommended form:

```text
T05-01 修复无流水用户详情查询

Tested:
- <exact command and result>

Not-tested:
- <reason or none>

Risk:
- <risk and mitigation>

Rollback:
- <how to revert safely>
```

Do not state that a test passed unless it was run against the current commit or CI run.

## Pull requests

Every pull request must include:

- one primary task ID and links to any dependencies;
- scope and explicitly excluded work;
- exact verification commands and evidence;
- API, environment, migration and compatibility impact;
- security and data-handling impact;
- rollback instructions;
- updated documentation when behavior or configuration changes.

P0 changes require review by the technical owner and the relevant QA/security role. The implementer must not be the only approver.

## Test expectations

- Mini Program: Node tests plus relevant WeChat Developer Tools or device evidence.
- Admin: frozen dependency install, type-check, lint, build and relevant component/API tests.
- Backend: JDK 17 build, targeted tests, Flyway/Testcontainers contract tests when schema or persistence changes.
- Cross-end changes: repeatable E2E coverage with fixtures and cleanup.

The root verification command and CI jobs will become mandatory after T02 is complete.

## Secrets and environments

- Never commit real AppSecret, payment keys, certificates, API keys, database passwords or access tokens.
- Commit only documented examples with unmistakably non-production values.
- Production profiles must fail closed when required credentials are missing.
- Logs and test artifacts must not contain authorization headers, payment payload secrets or personal data.

## Database and external contracts

- Every schema change uses an additive Flyway migration and includes forward verification and a rollback/recovery note.
- Do not edit an already-released migration in place.
- Payment, points, authorization, asset access and internal API changes require duplicate, failure, replay and unauthorized-path tests.
- Stub success does not satisfy a production integration task.

## Generated and binary artifacts

- Do not commit dependencies, build output, local runtime state or temporary screenshots.
- Evidence that must be retained belongs in the controlled project documentation area or a documented artifact store.
- Large Mini Program business images must follow the approved package-size and object-storage policy.
