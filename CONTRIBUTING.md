# Contributing to Inquery

Thank you for your interest in contributing. Inquery is an open-source project, and we welcome bug reports, documentation improvements, and code contributions via pull requests.

## Before You Start

- Read the [README](README.md) for setup instructions (local dev, Docker, desktop).
- Search [existing issues](https://github.com/jeonghyeondang/inquery/issues) to avoid duplicate work.
- For large changes (new features, architecture shifts), open an issue first to discuss scope.

## How to Contribute

1. **Fork** the repository on GitHub.
2. **Clone** your fork and create a branch from `main`:
   ```bash
   git checkout -b feat/my-change
   ```
3. **Make your changes** and verify locally:
   ```bash
   # Backend
   cd inquery-server
   mvn clean package -DskipTests

   # Frontend
   cd inquery-client-svelte
   npm install
   npm run check
   ```
4. **Commit** with a clear message (see below).
5. **Push** to your fork and open a **Pull Request** against `main`.

Only maintainers can merge pull requests into the upstream repository. External contributors cannot push directly to the main repo unless explicitly added as collaborators.

## Pull Request Guidelines

- Keep PRs focused — one logical change per PR when possible.
- Update documentation if behavior or setup steps change.
- Add or update UI strings in all supported locales (`en-us`, `ko-kr`, `ja-jp`, `tr-tr`) when changing user-facing text.
- Ensure CI checks pass (backend build + frontend type check).

## Commit Messages

Follow the existing style:

```
feat: add support for ...
fix: resolve crash when ...
docs: update Docker setup guide
refactor: simplify ...
chore: bump dependency ...
```

Use the imperative mood and explain **why** when it is not obvious.

## Code Guidelines

- **Language:** English for code, comments, commit messages, and default UI strings (translations live under `inquery-client-svelte/src/lib/i18n/`).
- **Backend:** Java 17, Spring Boot, LangChain4j 1.9.0. See `.claude/rules/backend.md` for project conventions.
- **Frontend:** Svelte 5 runes syntax (`$state`, `$derived`, `$effect`). See `.claude/rules/code-style.md`.
- **AI architecture:** Prefer tool descriptions and LLM routing over hard-coded domain keyword branches.
- **Security:** Never commit API keys, tokens, `.env` files, or personal machine paths.

## Reporting Bugs

Open a [GitHub Issue](https://github.com/jeonghyeondang/inquery/issues) with:

- Steps to reproduce
- Expected vs actual behavior
- Environment (OS, Java/Node versions, database type if relevant)
- Relevant logs or screenshots

## Security Vulnerabilities

Do **not** open public issues for security problems. See [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
