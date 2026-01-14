# Verify Build

Check the status of CI builds and tests for the current PR or branch.

## Steps

1. **Check PR status** (if on a PR branch):
```bash
gh pr checks
```

2. **If checks are still running**, wait for completion:
```bash
gh pr checks --watch
```

3. **View detailed logs** for a failed workflow:
```bash
# List recent runs to find the run ID
gh run list --limit 5

# View logs for a specific run
gh run view <run-id> --log
```

4. **Download build artifacts**:
```bash
# Download the debug APK
gh run download <run-id> -n app-debug

# Download lint results
gh run download <run-id> -n lint-results
```

## Common Issues

- **Build failed**: Check lint errors in `lint-results` artifact
- **Tests failed**: Download test results and check for assertion failures
- **Emulator tests timeout**: May need to simplify tests or increase timeout
