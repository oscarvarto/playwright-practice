# Test Statistics Dashboard

Streamlit dashboard for visualizing JUnit 5 test execution data stored in a local [Turso](https://turso.tech/) database.

The JVM test suite writes one row per test attempt into `src/test/resources/test-statistics.db`. This Python application
reads that database (via [pyturso](https://pypi.org/project/pyturso/)) and presents interactive charts and tables
through [Streamlit](https://streamlit.io/).

## Prerequisites

| Tool                                       | Version | Purpose                       |
| ------------------------------------------ | ------- | ----------------------------- |
| [pixi](https://pixi.sh/)                   | >= 0.40 | Python environment manager    |
| JUnit 5 test suite (parent Gradle project) | —       | Produces `test-statistics.db` |

> **Tip:** The database is created automatically the first time you run the
> JUnit tests in the parent project. If it does not exist yet, run
> `./gradlew test` from the repository root.

## Quick start

```zsh
cd python-analysis

# Install the environment (first time only — cached afterwards)
pixi install

# Launch the dashboard
pixi run app
```

The app opens at <http://localhost:8501> by default.

### Database locking

The Turso file-based database supports only one writer at a time. If another process holds the lock (e.g. `tursodb
--mcp` or a running Gradle test), the dashboard will fail to connect. Two workarounds:

1. **Stop the other process** before launching the dashboard.
2. **Use a snapshot copy** — copy the database to a temporary location and point the dashboard at it:

   ```zsh
   cp ../src/test/resources/test-statistics.db /tmp/test-statistics-snapshot.db
   ```

   Then create `.streamlit/secrets.toml`:

   ```toml
   [db]
   path = "/tmp/test-statistics-snapshot.db"
   ```

## Dashboard pages

| Page                     | Data source                                                | What it shows                                                               |
| ------------------------ | ---------------------------------------------------------- | --------------------------------------------------------------------------- |
| **Run Summary**          | `v_run_summary`                                            | KPI cards for the latest run, full run table, stacked bar chart of outcomes |
| **Test Case Quality**    | `v_test_case_quality`                                      | Per-test pass/fail rates, duration stats, last failure timestamp            |
| **Flaky Candidates**     | `test_execution` + `test_case` (raw, aggregated in Polars) | Tests with both passes and failures, ranked by status-flip count            |
| **Failure Fingerprints** | `test_execution` (raw, aggregated in Polars)               | Recurring failures grouped by fingerprint, with drill-down to executions    |
| **Duration Analysis**    | `test_execution` (raw)                                     | Mean, std, min, max, and coefficient of variation per test                  |
| **Daily Trend**          | `v_daily_status_trend`                                     | Daily pass/fail rate line chart, optionally split by trigger source         |
| **Failure Details**      | `test_execution` + payload/artifact tables                 | Drill into payloads and artifacts for individual failed executions          |

## Project layout

```text
python-analysis/
├── .gitignore
├── .streamlit/
│   └── config.toml          # Streamlit theme and server settings
├── app.py                   # Entry point — sidebar navigation and page routing
├── db.py                    # Database helper (query_frame → Polars DataFrame)
├── pages_impl/              # One module per dashboard page
│   ├── __init__.py
│   ├── run_summary.py
│   ├── test_case_quality.py
│   ├── flaky_candidates.py
│   ├── failure_fingerprints.py
│   ├── duration_analysis.py
│   ├── daily_trend.py
│   └── failure_details.py
├── pixi.toml                # Pixi environment definition
├── requirements.txt         # For Streamlit Community Cloud deployment
└── README.md
```

## Configuration

The database path is resolved in this order:

1. **Streamlit secrets** — `.streamlit/secrets.toml` with a `[db]` section containing `path = "..."`.

2. **Default** — `../src/test/resources/test-statistics.db` relative to the `python-analysis/` directory.

### System properties (JVM side)

These properties control the *write* side and are documented in
[`docs/turso-test-statistics.md`](../docs/turso-test-statistics.md):

| Property                         | Default                                 |
| -------------------------------- | --------------------------------------- |
| `test.statistics.enabled`        | `true`                                  |
| `test.statistics.db.path`        | `src/test/resources/test-statistics.db` |
| `test.statistics.run.label`      | unset                                   |
| `test.statistics.artifacts.root` | unset                                   |

## Dependencies

Managed by pixi (conda-forge + PyPI):

| Package     | Role                                       |
| ----------- | ------------------------------------------ |
| `pyturso`   | Turso/libSQL database driver               |
| `polars`    | DataFrame analytics (query results)        |
| `pandas`    | Required by Plotly for chart data          |
| `streamlit` | Web dashboard framework                    |
| `plotly`    | Interactive charts (bar, line, scatter)    |
| `watchdog`  | Faster file-change detection for Streamlit |

## pyturso window function limitation

The `pyturso` embedded engine does **not** support SQL window functions (`LAG`, `ROW_NUMBER`, `RANK`, etc.). The
database views `v_flaky_candidates` and `v_failure_fingerprints` use these functions and therefore cannot be queried
directly via `pyturso`.

The corresponding dashboard pages (`flaky_candidates.py` and `failure_fingerprints.py`) work around this by querying the
raw tables and replicating the view logic in Polars:

- `LAG … PARTITION BY` is replaced by `pl.Expr.shift().over()`.
- `ROW_NUMBER … WHERE row_num = 1` is replaced by `sort().group_by().first()`.

Other views (`v_run_summary`, `v_test_case_quality`, `v_daily_status_trend`) use only standard aggregation and work fine
with `pyturso`.

## Deploying to Streamlit Community Cloud

The repository includes a `requirements.txt` so that Streamlit Community Cloud can install dependencies automatically.
Deployment steps:

1. Push the `python-analysis/` directory to a GitHub repository.
2. Go to [share.streamlit.io](https://share.streamlit.io/) and click **New app**.
3. Point it at the repository, branch, and set the main file path to `python-analysis/app.py`.
4. Under **Advanced settings → Secrets**, provide the database path or connection details.

> **Current limitation:** Streamlit Community Cloud runs on Linux and expects
> network-accessible storage. The current `pyturso` file-based driver requires
> the database file to be present on the server filesystem. For true cloud
> deployment, the database would need to be hosted as a Turso cloud database
> and the connection logic in `db.py` updated to use an HTTP URL and auth
> token.

## Further reading

- [`docs/turso-test-statistics.md`](../docs/turso-test-statistics.md) — full
  schema documentation, design rationale, and additional query recipes.
