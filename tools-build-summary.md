# tools.build Technical Summary

## Overview

tools.build is Clojure's official library for building project artifacts, designed around the philosophy that "builds are programs." It provides a programmatic approach to creating JAR files, uberjars, and managing deployment workflows using Clojure code rather than declarative configurations.

## Core Philosophy

- **Builds as Programs**: Build processes should be written in Clojure code for maximum flexibility and extensibility
- **Programmatic Control**: Enables custom, adaptable build workflows beyond what declarative systems offer
- **Integration with Clojure CLI**: Designed to work seamlessly with `clj -T` (tools) functionality

## Key Functions

### Essential Build Functions
- `create-basis` - Generate project dependency context from deps.edn
- `copy-dir` - Copy source files and resources to build directory
- `write-pom` - Generate Maven POM XML file for deployment
- `jar` - Create library JAR archives
- `compile-clj` - Compile Clojure namespaces to bytecode
- `uber` - Create standalone executable uberjar with all dependencies
- `javac` - Compile Java source files

### Additional Utilities
- File and directory manipulation functions
- Command execution with output capture
- Git integration helpers
- Build artifact management

## Project Structure

### deps.edn Configuration
```clojure
{:aliases
 {:build {:deps {io.github.clojure/tools.build {:git/tag "v0.10.5"}}
          :ns-default build}}}
```

### build.clj File Structure
```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'myorg/myproject)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
```

## Common Build Scenarios

### Library JAR Creation
1. Create basis from deps.edn
2. Copy source and resource files
3. Generate POM file with metadata
4. Package into JAR file

### Uberjar Application Build
1. Create basis with all dependencies
2. Compile Clojure namespaces
3. Copy all source files and dependencies
4. Package into standalone executable JAR

### Mixed Java/Clojure Projects
1. Compile Java sources with `javac`
2. Compile Clojure namespaces
3. Package both compiled outputs

## Clojars Deployment Integration

### Required Dependencies
tools.build does not provide direct deployment functionality. For Clojars deployment, use:
- `slipset/deps-deploy` - Standard deployment library
- `org.corfield/build-clj` - Convenience wrapper with common tasks

### Authentication Setup
Clojars requires deploy tokens (not passwords):
1. Create deploy token at https://clojars.org/tokens
2. Set environment variables:
   - `CLOJARS_USERNAME` - Your Clojars username
   - `CLOJARS_PASSWORD` - Your deploy token (not actual password)

### Example Deployment Function
```clojure
(defn deploy [_]
  (jar nil)
  (deploy/deploy {:installer :remote
                  :artifact jar-file
                  :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
```

### Deployment Command
```bash
env CLOJARS_USERNAME=myusername CLOJARS_PASSWORD=clojars_token clj -T:build deploy
```

## Best Practices

### Project Organization
- Keep build.clj in project root
- Use descriptive configuration maps
- Separate configuration from task functions
- Include flexible functions that accept command-line options

### Build Tasks
- Implement `clean`, `jar`, `uberjar`, `install`, and `deploy` functions
- Use consistent naming conventions: `group/artifact-version.jar`
- Include configuration display functions for debugging
- Handle both library and application artifact types

### Version Management
- Use git-based versioning strategies
- Include version in both POM and JAR filename
- Consider semantic versioning for libraries

### Error Handling
- Validate configuration before build tasks
- Provide clear error messages
- Check for required environment variables in deployment

## Integration with Existing Tools

### Polylith Architecture
tools.build works well with polylith-style projects:
- Build individual component JARs
- Aggregate components into deployable projects
- Handle complex dependency graphs

### CI/CD Integration
- Environment variable based configuration
- Command-line parameter support
- Exit codes for build success/failure
- Artifact path predictability

## Migration Considerations

### From Leiningen
- Replace `project.clj` with `deps.edn` + `build.clj`
- Convert profiles to aliases
- Adapt plugin functionality to tools.build functions

### From Boot
- Replace boot tasks with tools.build functions
- Convert middleware to function composition
- Adapt asset pipeline to copy-dir operations

## Performance Characteristics

- Incremental builds supported through selective copying
- Compilation caching via basis management
- Minimal startup overhead compared to plugin-based systems
- Efficient dependency resolution through tools.deps integration

## Limitations and Considerations

- No built-in deployment functionality (requires additional libraries)
- Learning curve for teams used to declarative build systems
- Manual configuration of complex build pipelines
- Limited plugin ecosystem compared to Maven/Gradle

This technical summary provides the foundation needed to implement tools.build in the mcp-clj project for building and deploying to Clojars.