# LS Tool Specification

## Overview

The `ls` tool provides recursive file listing functionality with security restrictions and configurable limits.

## Arguments

### Required Arguments
- `path` (string) - Absolute or relative path to list files from

### Optional Arguments
- `max-depth` (integer, default: 10) - Maximum recursive depth to traverse
- `max-files` (integer, default: 100) - Maximum number of files to return

## Security

### Allowed Directory Roots
The tool restricts access to prevent filesystem traversal attacks:
- Current working directory (`.`)
- User directory (`System/getProperty "user.dir"`)

Any path that would escape these allowed roots will be rejected with an error.

## Behavior

### File Inclusion/Exclusion
- **Include**: Hidden files (starting with `.`)
- **Exclude**: 
  - `.DS_Store` files
  - Files and directories listed in `.gitignore` files
  - Directories are traversed but not counted toward the file limit

### Traversal
- Follow symbolic links
- Respect `max-depth` limit (directories at max depth are not traversed)
- Stop when `max-files` limit is reached

### Output
- Flat list of full file paths
- No particular ordering guaranteed

## Response Format

```json
{
  "content": [{
    "type": "text",
    "text": "JSON response with files and metadata"
  }]
}
```

The JSON response contains:
- `files` (array of strings) - Full file paths
- `truncated` (boolean) - True if either max-depth or max-files limit was hit
- `total-files` (integer) - Total number of files found (may be > files.length if truncated)
- `max-depth-reached` (boolean) - True if max-depth limit was hit
- `max-files-reached` (boolean) - True if max-files limit was hit

## Error Handling

The tool handles various error conditions:
- Non-existent paths
- Permission denied errors
- Invalid path arguments
- Path traversal attempts (outside allowed roots)

Errors are returned in the standard MCP error format with `isError: true`.

## Examples

### Basic usage
```json
{
  "path": "./src"
}
```

### With limits
```json
{
  "path": "/absolute/path/to/dir",
  "max-depth": 3,
  "max-files": 50
}
```

### Response example
```json
{
  "files": [
    "/Users/user/project/src/file1.clj",
    "/Users/user/project/src/subdir/file2.clj"
  ],
  "truncated": false,
  "total-files": 2,
  "max-depth-reached": false,
  "max-files-reached": false
}
```