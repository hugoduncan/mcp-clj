# Release Process

This document describes the release process for mcp-clj projects.

## Overview

The release process is automated via GitHub Actions and includes:

1. **Building** all project JARs
2. **Testing** with full unit and integration test suites
3. **Smoke testing** built artifacts
4. **Changelog generation** from git commits
5. **Git tagging** with semantic versioning
6. **Deployment** to Clojars
7. **GitHub Release** creation with artifacts

## Version Numbering

Version numbers follow the format: `Major.minor.commit-count`

- **Major.minor**: Manually set in `dev/build.clj` (currently `0.1`)
- **commit-count**: Automatically calculated from git history

Example: `0.1.49` means version 0.1 with 49 commits

## Prerequisites

### First Time Setup

1. **Clojars Account**
   - Create account at https://clojars.org
   - Generate deployment token in account settings

2. **GitHub Secrets**
   Add these secrets to your GitHub repository (Settings → Secrets → Actions):
   - `CLOJARS_USERNAME`: Your Clojars username
   - `CLOJARS_PASSWORD`: Your Clojars deployment token

## Performing a Release

### Step 1: Dry Run (Recommended)

Always test the release process first with a dry run:

1. Go to **Actions** tab in GitHub
2. Select **Release** workflow
3. Click **Run workflow**
4. Leave **dry_run** set to `true`
5. Click **Run workflow**

This will:
- Build all JARs
- Run all tests
- Generate changelog
- Show what would be released
- **NOT** create tags or deploy anything

### Step 2: Review Dry Run

Check the workflow output:
- Verify all tests pass
- Review generated changelog
- Confirm version number is correct
- Check JAR file sizes and contents

### Step 3: Actual Release

Once dry run succeeds:

1. Go to **Actions** tab in GitHub
2. Select **Release** workflow
3. Click **Run workflow**
4. Set **dry_run** to `false`
5. Click **Run workflow**

This will:
- Build and test everything again
- Create git tag (e.g., `v0.1.49`)
- Deploy JARs to Clojars
- Create GitHub Release with changelog and artifacts

## What Gets Released

The following projects are released to Clojars:

- `io.github.hugoduncan/mcp-clj-server`
- `io.github.hugoduncan/mcp-clj-client`
- `io.github.hugoduncan/mcp-clj-in-memory-transport`

Test-only projects (`test-dep`, `java-sdk-wrapper`) are not released.

## Local Testing

### Build JARs Locally

```bash
# Build a specific project
cd projects/server
clojure -T:build jar

# Build all projects
for project in server client in-memory-transport; do
  cd "projects/$project"
  clojure -T:build jar
  cd ../..
done
```

### Run Smoke Tests Locally

```bash
# First build the JARs, then:
bash scripts/smoke-test.sh
```

### Test Deploy Locally (Dry Run)

You cannot do a true dry-run deploy, but you can verify the JARs are built correctly:

```bash
cd projects/server
clojure -T:build jar

# Inspect the JAR
jar tf target/mcp-clj-server-*.jar | less
```

## Troubleshooting

### Release Fails at Testing

- Fix the failing tests
- Commit and push fixes
- Start a new release

### Release Fails at Deployment

- Check Clojars credentials in GitHub Secrets
- Verify network connectivity
- Check Clojars status page

### Tag Already Exists

If the workflow detects an existing tag:
- The workflow will skip tagging and deployment
- This prevents accidental re-releases
- To create a new release, you need new commits

### Want to Re-release Same Version

This is not supported by design. Instead:
1. Make a commit (even a doc change)
2. This increments the version number
3. Release the new version

## Changelog

Changelogs are automatically generated using [git-cliff](https://git-cliff.org/) from conventional commit messages.

### Commit Message Format

Use semantic commit prefixes:

- `feat:` - New features
- `fix:` - Bug fixes
- `perf:` - Performance improvements
- `refactor:` - Code refactoring
- `docs:` - Documentation changes
- `test:` - Test changes
- `build:` - Build system changes
- `ci:` - CI/CD changes
- `chore:` - Other changes

Example: `feat: add subscription support for resources`

### Generate Changelog Locally

```bash
# View changelog for unreleased commits
git cliff --unreleased

# Generate full changelog
git cliff --output CHANGELOG.md
```

## Manual Deployment (Emergency)

If GitHub Actions is unavailable, you can deploy manually:

```bash
# 1. Build the JAR
cd projects/server
clojure -T:build jar

# 2. Configure Clojars credentials in ~/.m2/settings.xml
cat > ~/.m2/settings.xml <<EOF
<settings>
  <servers>
    <server>
      <id>clojars</id>
      <username>YOUR_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
EOF

# 3. Deploy
clojure -X:deploy

# 4. Repeat for other projects (client, in-memory-transport)
```

## Post-Release

After a successful release:

1. Verify artifacts appear on Clojars
2. Test installation in a fresh project
3. Update any external documentation
4. Announce the release if appropriate

## Release Checklist

- [ ] All tests passing on main branch
- [ ] Commits follow conventional commit format
- [ ] Breaking changes documented
- [ ] Dry run completed successfully
- [ ] Clojars credentials configured in GitHub
- [ ] Actual release completed
- [ ] Artifacts verified on Clojars
- [ ] GitHub Release looks correct
