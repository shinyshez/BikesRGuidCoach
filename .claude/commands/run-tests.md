# Run Tests

Trigger and monitor test runs via GitHub Actions.

## Check Existing Test Results

```bash
# View PR check status
gh pr checks

# Find recent test runs
gh run list --workflow=unit-tests.yml --limit 3
gh run list --workflow=instrumented-tests.yml --limit 3
```

## Download Test Results

```bash
# Get the run ID from gh run list, then:

# Download unit test results
gh run download <run-id> -n unit-test-results

# Download instrumented test results
gh run download <run-id> -n instrumented-test-results
```

## Interpret Results

Test results are in JUnit XML format under:
- `app/build/test-results/` - Unit tests
- `app/build/outputs/androidTest-results/` - Instrumented tests

HTML reports are also available for easier reading.

## Trigger Manual Test Run

If you need to re-run tests without pushing new changes:

```bash
# Re-run a failed workflow
gh run rerun <run-id>

# Or trigger specific workflow
gh workflow run unit-tests.yml
```
