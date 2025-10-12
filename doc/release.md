# Release Process

This document describes the manual release process for mcp-clj projects.

## Overview

mcp-clj uses:
- **Semantic versioning**: `0.1.<commit-count>` format
- **Automatic version calculation**: Based on total git commit count via `b/git-count-revs`
- **Conventional commits**: For structured changelog generation
- **git-cliff**: For automated changelog generation from commit history
- **Git tags**: Required for changelog version segmentation

## Version Numbering Strategy

Version format: `0.1.<commit-count>`

- **Major.Minor**: Currently fixed at `0.1`
- **Patch**: Automatically increments with each commit (calculated by `b/git-count-revs`)
- Example: If there are 49 commits total, version is `0.1.49`

This ensures:
- Deterministic versioning (same commit count = same version)
- No manual version tracking required
- Monotonically increasing versions

## Prerequisites

### Required Tools

1. **git-cliff** (for changelog generation)
   ```bash
   # macOS
   brew install git-cliff

   # Or download from https://github.com/orhun/git-cliff/releases
   ```

2. **Clojure CLI** (already required for development)

### Commit Message Requirements

All commits MUST follow [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>: <description>

[optional body]

[optional footer]
```

**Supported types:**
- `feat:` - New features (appears in Features section)
- `fix:` - Bug fixes (appears in Bug Fixes section)
- `perf:` - Performance improvements
- `refactor:` - Code refactoring
- `docs:` - Documentation changes
- `test:` - Test additions/changes
- `build:` - Build system changes
- `ci:` - CI/CD changes
- `chore:` - Maintenance tasks (chores section)
- `style:` - Code style changes

**Breaking changes:** Add `!` after type or `BREAKING CHANGE:` in footer:
```
feat!: redesign API interface

BREAKING CHANGE: The old API methods have been removed
```

## Release Workflow

### 1. Verify Current State

```bash
# Ensure working directory is clean
git status

# Check current version
clj -T:build version
# Example output: Version: 0.1.49
```

### 2. Generate Changelog

**Preview unreleased changes:**
```bash
git cliff --unreleased
```

Review the output to ensure:
- All commits since last tag are included
- Commits are properly categorized by type
- No unexpected commits appear

**Update CHANGELOG.md:**
```bash
# This appends the unreleased section to CHANGELOG.md
git cliff -o CHANGELOG.md
```

**Review and edit if needed:**
```bash
# Optional: manually review/edit CHANGELOG.md
# - Fix any formatting issues
# - Add release notes if needed
# - Ensure accuracy
```

### 3. Create Git Tag

**CRITICAL REQUIREMENT:** Git tags are required for git-cliff to segment the changelog by version.

```bash
# Get version from build script
VERSION=$(clj -T:build version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')

# Create annotated tag
git tag -a "v${VERSION}" -m "Release v${VERSION}"

# Verify tag was created
git tag -l "v${VERSION}"
```

**Tag format:** `v<major>.<minor>.<patch>` (e.g., `v0.1.49`)

**Why tags are required:**
- git-cliff uses tags to identify release boundaries
- Commits between tags are grouped into version sections
- Without tags, all commits appear as "unreleased"
- Tags enable version-specific changelog generation

### 4. Build JAR Artifacts

Build JARs for each project:

```bash
# Server project
cd projects/server
clj -T:build jar
# Output: projects/server/target/mcp-clj-server-0.1.49.jar

# Client project
cd ../client
clj -T:build jar
# Output: projects/client/target/mcp-clj-client-0.1.49.jar

# In-memory transport project
cd ../in-memory-transport
clj -T:build jar
# Output: projects/in-memory-transport/target/mcp-clj-in-memory-transport-0.1.49.jar

# Return to root
cd ../..
```

### 5. Commit Changelog Updates

```bash
# Stage the updated changelog
git add CHANGELOG.md

# Commit with semantic message
git commit -m "chore(release): update changelog for v${VERSION}"
```

**Note:** This commit will be filtered out of future changelogs due to `chore(release)` prefix in cliff.toml.

### 6. Push Tag to Remote

```bash
# Push the tag to GitHub
git push origin "v${VERSION}"

# Verify tag is on remote
git ls-remote --tags origin
```

### 7. Create GitHub Release

**Option A: Manual (GitHub UI)**

1. Go to https://github.com/hugoduncan/mcp-clj/releases/new
2. Select the tag: `v0.1.49`
3. Release title: `v0.1.49`
4. Description: Copy the relevant section from CHANGELOG.md
5. Attach JAR files from `projects/*/target/`
6. Click "Publish release"

**Option B: Using GitHub CLI**

```bash
# Extract changelog section for this version
CHANGELOG_SECTION=$(git cliff --latest --strip all)

# Create release with JARs
gh release create "v${VERSION}" \
  --title "v${VERSION}" \
  --notes "${CHANGELOG_SECTION}" \
  projects/server/target/mcp-clj-server-${VERSION}.jar \
  projects/client/target/mcp-clj-client-${VERSION}.jar \
  projects/in-memory-transport/target/mcp-clj-in-memory-transport-${VERSION}.jar
```

### 8. Verify Release

- [ ] GitHub Release is published
- [ ] JAR artifacts are attached
- [ ] CHANGELOG.md is updated
- [ ] Git tag exists locally and on remote
- [ ] Version number matches expected value

## Common Scenarios

### First Release After git-cliff Setup

If this is the first release after adding git-cliff:

1. A baseline tag `v0.1.0` has already been created (historical commit)
2. Current release will be first "official" tagged release
3. CHANGELOG.md will show all commits since `v0.1.0`

### Hotfix Release

For urgent fixes:

1. Create fix commit with `fix:` prefix
2. Follow standard release workflow
3. Version automatically increments due to new commit

### Release Candidates

Currently not supported. Future enhancement could add:
- RC tags: `v0.1.49-rc.1`
- Pre-release flags in GitHub Releases

## Troubleshooting

### "No commits found" when running git-cliff

**Problem:** git-cliff shows no commits or empty changelog

**Solution:**
- Ensure git tags exist (`git tag -l`)
- Check that commits follow conventional commit format
- Verify cliff.toml configuration
- Run `git cliff --unreleased --verbose` for debug info

### Version number doesn't increment

**Problem:** `clj -T:build version` shows same version after new commits

**Solution:**
- `b/git-count-revs` counts ALL commits in repository
- Verify new commits are actually committed (`git log --oneline -5`)
- Check you're in the correct git repository

### git-cliff includes unwanted commits

**Problem:** Changelog shows commits that shouldn't appear

**Solution:**
- Check commit_parsers in cliff.toml
- Add skip rules for commit patterns
- Use `{ body = "pattern", skip = true }` to filter by commit message content

### Tag already exists

**Problem:** `git tag -a v0.1.49` fails with "already exists"

**Solution:**
```bash
# Delete local tag
git tag -d v0.1.49

# Delete remote tag (if pushed)
git push origin :refs/tags/v0.1.49

# Recreate tag
git tag -a v0.1.49 -m "Release v0.1.49"
```

## Future Enhancements

Planned improvements for the release process:

- [ ] Automated changelog generation in GitHub Actions
- [ ] Automatic GitHub Release creation in CI
- [ ] Clojars deployment automation
- [ ] Release candidate support
- [ ] Multi-project coordinated releases

## References

- [git-cliff documentation](https://git-cliff.org/docs/)
- [Conventional Commits specification](https://www.conventionalcommits.org/)
- [Semantic Versioning](https://semver.org/)
- [Keep a Changelog](https://keepachangelog.com/)
