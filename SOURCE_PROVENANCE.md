# Source Provenance

> Status: provisional authoritative archive baseline
> Recorded: 2026-09-08
> Owner: project technical owner (confirmation pending)

## Unified repository

| Field | Value |
|---|---|
| Remote | `https://github.com/phlong026/zhongshu-home-design-miniprogram.git` |
| Default branch | `main` |
| Archive commit | `dc0f41145752fce1bba24cfdfe3648a2a2f91c92` |
| Archive root tree | `819c3f876255bce6ea7420239e7f5f938cd425af` |
| Local baseline tag | `baseline/archive-2026-09-08` |
| Archive date | 2026-09-08 |

The archive commit is a root commit with no parent. It preserves the file snapshot but does not contain the three source repositories' histories.

## Archived source trees

| Area | Recorded source branch | Recorded source commit | Archive subtree | Files | Source remote status |
|---|---|---|---|---:|---|
| Mini Program (`前端程序/`) | `fix/activation-feature-gate` | `be57fed0cac613e7272cdb7dba24c557304f3361` | `e1ef957196c274a566732effe8770cba5e1ca18f` | 214 | Not recorded; commit is not present in this repository |
| Admin (`管理后台/`) | `main` | `a6969959bc41a8bb087bd95c4aa58fadd518ad11` | `53a0efe26ebcfc39690bed1bec515a5f9e6be716` | 578 | Not recorded; commit is not present in this repository |
| Backend (`后端程序/`) | `feat/zhongshu-design-v1` | `2bf21e528e8a73481c98d35a061eae60ad897f02` | `df7961fba1914f2c6fa0201c2515abf2e5a3c154` | 8,338 | Modified-source remote not recorded; upstream base is recorded below |
| Project docs (`项目文档/`) | archive snapshot | not separately versioned | `aabd6a7b21d20079569e92af31fa35fa6aeeadec` | 14 | Included directly in unified archive |

All three recorded source commits were checked with `git cat-file -e <sha>^{commit}` and are unavailable in the unified repository object database.

## Known upstream and reference repositories

| Relationship | Repository | Recorded baseline | Authority |
|---|---|---|---|
| Backend upstream framework | `https://github.com/YunaiV/ruoyi-vue-pro.git` | `master-jdk17` / `8e43004cf68a405cd3485f98f8a539b97ca6544a` | Upstream framework only; not evidence of the modified Zhongshu history |
| Related reference project | `https://github.com/hlong026/jdlg.git` | `codex/feat-zhongshu-v11-pages` (commit recorded in the controlled input document) | Reference input only; not adopted as the source of any archived subtree |

## Hash and verification rules

1. The Git tree object IDs above are the canonical integrity identifiers for the archived snapshot. They are independent of checkout line-ending conversion.
2. The four controlled backend documents under `后端程序/docs/zhongshu-design/` retain the normalized SHA-256 values registered in `T00-G0A门禁与实现基线登记.md`.
3. Do not present an unavailable historical SHA as a commit that can be reviewed or checked out from this repository.
4. Every future release must identify a reachable commit and annotated or protected release tag in this unified repository.

## Provisional disposition

Until the source histories or source remotes are supplied, the project adopts the following temporary rule:

- `dc0f411` and `baseline/archive-2026-09-08` are the authoritative code snapshot for new work.
- Historical completion claims remain informational unless reproduced by the unified repository CI.
- New changes must be made on task branches and merged through review; direct development on `main` is not allowed.
- If original histories are later recovered, import them on separate archival refs first. Do not rewrite `main` without an approved migration and rollback plan.

## Open confirmations

- [ ] Confirm the original remote URL for the Mini Program repository.
- [ ] Confirm the original remote URL for the Admin repository.
- [ ] Confirm the modified Backend repository remote, if it differs from the upstream framework URL.
- [ ] Confirm the archive owner and approve this provisional disposition.
- [ ] Decide whether to import the original histories or retain them as read-only archival refs.
