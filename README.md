# Playwright Testing - Java API

This repository is a Java-based Playwright testing workspace with support for browser tests, API tests, opt-in external
integrations, and a Turso-backed JUnit 5 statistics pipeline.

The top-level README is intentionally short. Detailed design notes and operational guides live in focused documents
linked below.

## Documentation

| Topic                                                                            | Document                                                       |
| -------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| API testing architecture, `@UsePlaywright`, GitHub integration, and secrets flow | [docs/api-testing.md](docs/api-testing.md)                     |
| Turso test statistics schema, lifecycle, SQL views, and dashboard contract       | [docs/turso-test-statistics.md](docs/turso-test-statistics.md) |
| AWS / LocalStack testing setup and mode selection                                | [AWS_TESTING_GUIDE.md](AWS_TESTING_GUIDE.md)                   |
| Streamlit dashboard for test statistics (Python / pixi)                          | [python-analysis/README.md](python-analysis/README.md)         |

## Quick Start

**NOTE**: Follow <https://github.com/tursodatabase/turso/blob/main/bindings/java/README.md> to deploy the Turso JDBC
driver to your local maven repo first.

Default build:

```zsh
./gradlew build
```

GitHub API integration tests are opt-in:

```zsh
./gradlew test \
  -Dplaywright.github.integration.enabled=true \
  --tests "*.TestGitHubAPI"
```

AWS / LocalStack-backed tests are also opt-in:

```zsh
./gradlew test \
  -Dplaywright.aws.testing.enabled=true \
  --tests "*AwsTestingTest"
```

The Turso statistics pipeline is enabled by default for JUnit 5 test runs and stores data in
`src/test/resources/test-statistics.db`. For schema and analytics details, use
[docs/turso-test-statistics.md](docs/turso-test-statistics.md).

## Why Playwright + Java?

Most Playwright adoption defaults to Node.js with TypeScript. That works, but TypeScript's type system is structural and
opt-out: `any`, type assertions, and unchecked index access are one keystroke away, and the compiler cannot prevent them
at the project level. In practice, large test suites accumulate these escape hatches quietly.

Java's type system is nominal and closed by default. There is no `any`. Casts are explicit and checked at runtime.
Generics are erased but enforced at compile time. The result is that an entire class of bugs — wrong argument order,
mismatched return types, null where non-null was expected — is caught before the test ever runs.

Beyond the type system:

- **JUnit 5 extensions** give you lifecycle hooks (before/after all, before/after each, parameter resolution,
  conditional execution) as composable, reusable classes — not ad-hoc `beforeAll` closures scattered across spec files.
- **Gradle and Maven** provide reproducible, cacheable builds with dependency locking. No `node_modules` drift, no
  phantom peer-dependency warnings.
- **One toolchain for teams already on the JVM.** If the application under test is Java, Kotlin, or Scala, the test
  suite shares the same language, IDE, debugger, and dependency graph. Context switching between "app code in Java" and
  "test code in TypeScript" disappears.
- **Playwright's Java API is a first-class binding**, not a community wrapper. It is maintained by Microsoft, ships with
  the same browser versions, and supports the same features (auto-waits, trace viewer, codegen) as the Node.js API.

## Why Turso / SQLite for test statistics?

The test statistics pipeline stores execution data in a local Turso database file (`test-statistics.db`). A natural
question is whether a "real" database like PostgreSQL would be a better fit. For this use case, it would be overkill.

**The data volume is inherently bounded.** Test statistics grow as *(number of test cases) x (number of runs)*. Even a
large suite running daily in CI produces tens of thousands of rows over months — trivial for SQLite.

**Zero infrastructure is the killer feature.** The current pipeline is:

1. Run `./gradlew test` — data appears in a file.
2. Run `pixi run app` — the Streamlit dashboard reads that file.

No server process, no Docker container, no credentials, no network configuration. PostgreSQL would replace that with:
install and run Postgres, create a database, manage credentials, configure JDBC connection strings on the Java side and
connection parameters on the Python side, provision an instance in CI, and handle migrations through a server-aware
tool. Every developer and every CI runner would need a running PostgreSQL instance. That is a lot of operational
friction for a testing support tool.

**If a shared, always-on dashboard becomes necessary** — for example, a team-wide view aggregating CI runs from all
branches — Turso offers a cloud-hosted mode with HTTP access that bridges that gap without switching database engines.
PostgreSQL remains an option at that point, but the migration cost only makes sense once the single-file model is
genuinely outgrown.
