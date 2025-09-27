---
description: Update README following modern best practices for developer-focused documentation
---

Rewrite @README.md following README best practices to help users
understand and use mcp-clj within 5 minutes.

## Structure Requirements

**Above the fold** (first screen):
- Clear project title and one-line description
- Installation command (copy-pasteable)
- Minimal working example
- Link to key sections

**Essential sections in order**:
1. **Quick Start** - Get running in 30 seconds with realistic example
2. **What & Why** - What mcp-clj does, key benefits, when to use it
3. **Installation** - Multiple methods (git deps, future releases)
4. **Core Usage Patterns** - Server, client, programmatic (with working examples)
5. **Configuration** - Claude Code/Desktop setup, transport options
6. **Development** - Setup, testing, contribution basics
7. **Architecture** - Brief polylith overview for contributors
8. **License**

## Content Guidelines

**Front-load critical info**:
- Lead with installation + working example
- Show realistic use cases, not toy examples
- Include expected output where helpful

**Make it scannable**:
- Use clear headers and bullet points
- Keep paragraphs short (2-3 sentences max)
- Add table of contents if >10 sections

**Code examples must**:
- Be copy-pasteable and tested
- Show complete working examples
- Include both server and client usage
- Demonstrate real MCP tool interactions

**Comparisons**:
- Keep the clojure-mcp comparison but make it concise
- Focus on practical differences that affect usage decisions

## Remove/Minimize

- Detailed API documentation (link to separate docs)
- Lengthy architectural explanations (brief overview only)
- Every configuration option (show common cases)
- Alpha warnings (acknowledge but don't emphasize)

Verify all code examples work with current codebase. Test installation
instructions. Ensure the README helps users succeed quickly rather than
explaining everything comprehensively.
