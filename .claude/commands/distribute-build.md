# Distribute Build

Create a release build and generate a QR code for easy installation on Android devices.

## Automatic Distribution

When changes are merged to `main`, the `distribute-apk.yml` workflow automatically:
1. Builds the debug APK
2. Creates a GitHub Release with the APK attached
3. Generates a QR code for direct download

## Manual Distribution

```bash
# Trigger distribution workflow manually
gh workflow run distribute-apk.yml

# Or with custom release notes
gh workflow run distribute-apk.yml -f release_notes="Fixed recording issue"
```

## Get the QR Code

```bash
# List recent distribution runs
gh run list --workflow=distribute-apk.yml --limit 3

# Download the QR code
gh run download <run-id> -n install-qr-code
```

## Find the Release

```bash
# List recent releases
gh release list --limit 5

# Get download URL for latest release
gh release view --json assets -q '.assets[0].url'
```

## Installation Instructions for Testers

1. Scan the QR code with your Android device
2. Download the APK file
3. Open the downloaded file
4. If prompted, enable "Install from unknown sources"
5. Complete the installation

## Troubleshooting

- **Can't install**: Enable "Install from unknown sources" in Android settings
- **QR code not working**: Use the direct GitHub release URL instead
- **Old version installed**: Uninstall the existing app first
