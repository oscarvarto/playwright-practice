# Test Statistics Dashboard

Streamlit dashboard for visualizing JUnit 5 test execution statistics.

This branch contains only the Python dashboard code, deployed to
[Streamlit Community Cloud](https://streamlit.io/cloud). The full Java
project (Playwright tests, Gradle build, schema migrations) lives on the
`main` branch.

## Stack

| Component  | Role                                       |
| ---------- | ------------------------------------------ |
| Streamlit  | Web dashboard framework                    |
| Plotly     | Interactive charts (bar, line, scatter, pie)|
| Polars     | DataFrame analytics                        |
| pyturso    | Turso/libSQL database driver               |

## Dashboard pages

| Page                     | What it shows                                                               |
| ------------------------ | --------------------------------------------------------------------------- |
| **Run Summary**          | KPI cards, run table, stacked bar chart of outcomes, pie chart of latest run|
| **Test Case Quality**    | Per-test pass/fail rates, duration stats, last failure timestamp            |
| **Flaky Candidates**     | Tests with both passes and failures, ranked by status-flip count            |
| **Failure Fingerprints** | Recurring failures grouped by fingerprint, with drill-down to executions    |
| **Duration Analysis**    | Mean, std, min, max, and coefficient of variation per test                  |
| **Daily Trend**          | Daily pass/fail rate line chart, optionally split by trigger source         |
| **Failure Details**      | Drill into payloads and artifacts for individual failed executions          |

## Local development

```zsh
pip install -r requirements.txt
streamlit run app.py
```

## Data

The `data/test-statistics.db` file is a snapshot of the Turso database
produced by the JUnit 5 test suite. It is included for demo purposes and
does not contain sensitive information.
