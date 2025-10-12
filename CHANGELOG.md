# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [unreleased]

### Bug Fixes

- Adapter tests ([38ea17f](https://github.com/hugoduncan/mcp-clj/commit/38ea17f24421a21e6a1b79a10e255f571e934586))

- New http-client component broke json-rpc (#11) ([9e243b8](https://github.com/hugoduncan/mcp-clj/commit/9e243b80b7ca9b821699537ff22a87d5ca22ae89))


### Build System

- Add GitHub Actions workflow for testing (#10) ([2d18ded](https://github.com/hugoduncan/mcp-clj/commit/2d18deda32c579579638e2c8073b7d21c7850931))


### Chores

- Change json-rpc top level namespace to mcp-clj ([9269fee](https://github.com/hugoduncan/mcp-clj/commit/9269fee29a8512dc97284dc4c9cc62d235393803))

- Add namespace documentation ([9500d6c](https://github.com/hugoduncan/mcp-clj/commit/9500d6c73b10c2dab45b2b035b6531cfcaec5846))

- Improve implementation encapsulation ([102a63e](https://github.com/hugoduncan/mcp-clj/commit/102a63ec07c9d61da28e93231dcb3d726a77eb6f))

- Rename to http-server.adapter ([0fc6bb0](https://github.com/hugoduncan/mcp-clj/commit/0fc6bb09c2ab1a22d9c845cb94c94d6a3125cf55))

- Remove ring dependencies ([3878617](https://github.com/hugoduncan/mcp-clj/commit/3878617c55480d1dde1274e6644632a076609c4e))

- Improve HTTP server adapter test coverage ([4b0b0c5](https://github.com/hugoduncan/mcp-clj/commit/4b0b0c5e4eaea0b047e9f10ca4a11afa17575672))

- Improve JSON-RPC server adapter test coverage ([414de3e](https://github.com/hugoduncan/mcp-clj/commit/414de3e5df3164e31fd873cb49f78e087de1f417))

- Improve server tests ([a4d94af](https://github.com/hugoduncan/mcp-clj/commit/a4d94afa27409133455d89b486d5567da35f4584))

- Add README.md ([e92ba82](https://github.com/hugoduncan/mcp-clj/commit/e92ba825010fb809f43ed7bc8dccc2ac398f00b9))

- Add server project ([edd3c34](https://github.com/hugoduncan/mcp-clj/commit/edd3c34f14add286fee8f716f339da21c2d0ce80))

- Add server project ([4967c42](https://github.com/hugoduncan/mcp-clj/commit/4967c42601b572a68f72e43ed12911b221df70d8))

- Remove extraneous files (#6) ([2e9c213](https://github.com/hugoduncan/mcp-clj/commit/2e9c213ee5fb323aefe427f911a8caea2df2e18d))

- Update cljstyle to format edn (#7) ([7a16f77](https://github.com/hugoduncan/mcp-clj/commit/7a16f77dc02334d8b5d18db09d05c4734cd6d26b))

- Remove reflection (#13) ([9a20cf3](https://github.com/hugoduncan/mcp-clj/commit/9a20cf38ffa382780a791eddd8105ce03e542aa8))

- Update CLAUDE.md (#15) ([72983c7](https://github.com/hugoduncan/mcp-clj/commit/72983c739be4456b7efc69b04c801f822435a7ec))

- Add MCP protocol specification as git submodule (#19) ([9ddd8aa](https://github.com/hugoduncan/mcp-clj/commit/9ddd8aaca96bef73032e09e3832438f4a02a91df))


### Features

- Add server implementation ([c696c8b](https://github.com/hugoduncan/mcp-clj/commit/c696c8b818b6c3422391694b0ff32c5abbeae82b))

- Add mcp-server ([303a7a1](https://github.com/hugoduncan/mcp-clj/commit/303a7a1c9fe487924a9c7ae3ef068adeb65dd72c))

- Add Java HTTP ring adapter ([6f3038d](https://github.com/hugoduncan/mcp-clj/commit/6f3038d6a4dede783284238ce41f279c502324ff))

- Make json-rpc server use SSE ([3b09462](https://github.com/hugoduncan/mcp-clj/commit/3b09462cab4280c0512d2d6732cdb3fbfb1a7258))

- Working with initialisation over /messages ([3369f2b](https://github.com/hugoduncan/mcp-clj/commit/3369f2b82647bc29d687f30b5b606f714534c39a))

- Basic tools support ([640e657](https://github.com/hugoduncan/mcp-clj/commit/640e6570e7be2148809f3c972e57189e207497f9))

- Add basic resources and prompts support ([0233627](https://github.com/hugoduncan/mcp-clj/commit/0233627c51f0361445f36b84f750fdde56cbf35d))

- Add dynamic tool management ([563f8dd](https://github.com/hugoduncan/mcp-clj/commit/563f8dd0df174bdf9480f6abb0732198f5505776))

- Add tool change notifications ([435e1f4](https://github.com/hugoduncan/mcp-clj/commit/435e1f4ebfb1107ba4090bfe39bfab8fbd3058db))

- Improve clj-eval tool to show output and stack traces ([03e1f47](https://github.com/hugoduncan/mcp-clj/commit/03e1f4769b3e0491b84de5eecc70d392e7536dc0))

- Improve clj-eval tool to show output and stack traces ([9c847c7](https://github.com/hugoduncan/mcp-clj/commit/9c847c7d0b099094d23e65bd9738ecad544949fd))

- Add prompts support to MCP server ([dd868dc](https://github.com/hugoduncan/mcp-clj/commit/dd868dcfc6ac9ee1c915665a454dc74fec4fd4bf))

- Implement resource support ([239e7da](https://github.com/hugoduncan/mcp-clj/commit/239e7da40e8d7eca2053da6748080e0fb7674640))

- Add client-side prompts support (#16) ([caaa58e](https://github.com/hugoduncan/mcp-clj/commit/caaa58e59df27992d87b487559ced8dbaa1155d3))

- Add mcp-client support for resources (#17) ([1b2588b](https://github.com/hugoduncan/mcp-clj/commit/1b2588b2cc6fb14ff7fa408d43cdd100c8d0e38e))

- Add MCP capability negotiation compliance (#18) ([77ca1a2](https://github.com/hugoduncan/mcp-clj/commit/77ca1a2df2c9d261a127272417e92e0728b528fd))

- Add subscription support and compliance tests (#20) ([8dd2533](https://github.com/hugoduncan/mcp-clj/commit/8dd2533fd5fc5e4ed4dd0169fbc06fadb3c38f60))

- Add MCP logging utility support ([c60d2a1](https://github.com/hugoduncan/mcp-clj/commit/c60d2a14586bb4a4d71d4a90e37a06e273b1093f))

- Add CI reflection warning check ([84914ab](https://github.com/hugoduncan/mcp-clj/commit/84914abc4233db18d2002e7d1909b1a9a061a4a1))

- Add build infrastructure for JAR generation ([57408e8](https://github.com/hugoduncan/mcp-clj/commit/57408e8d13c78f7f696becfc59d84073227105f5))


### Refactoring

- Extract versions into shared component (#12) ([e22160a](https://github.com/hugoduncan/mcp-clj/commit/e22160a39e13b328268b105fa89c49e6c1df61b7))

- Merge json-rpc protocol namespaces (#14) ([5173393](https://github.com/hugoduncan/mcp-clj/commit/5173393fe292c028ab33085ecaebd0440be91c31))


## [0.1.0] - 2025-01-05

### Features

- Add initial json-rpc server ([39f31f8](https://github.com/hugoduncan/mcp-clj/commit/39f31f8ae815f4ca0f293218732e5d80e037ac88))


<!-- generated by git-cliff -->
