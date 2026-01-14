# Get Screenshots

Download and review app screenshots captured by CI for visual verification.

## Find Screenshot Runs

```bash
# List recent screenshot workflow runs
gh run list --workflow=screenshot-tests.yml --limit 5
```

## Download Screenshots

```bash
# Get run ID from the list above, then download:
gh run download <run-id> -n app-screenshots

# Screenshots will be saved to ./app-screenshots/
```

## Trigger New Screenshot Capture

```bash
# Manually trigger screenshot capture
gh workflow run screenshot-tests.yml
```

## What Screenshots Are Captured

The workflow captures:
1. `01_main_screen.png` - Main recording screen
2. `02_gallery_or_main.png` - Gallery view (if navigation successful)
3. `03_settings_or_main.png` - Settings screen (if navigation successful)

## Review Process

1. Download screenshots from the PR's workflow run
2. Compare with previous version to verify visual changes
3. Check for layout issues, missing elements, or UI regressions
