# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| `main` (latest) | Yes |
| Older releases | Best effort; prefer upgrading to the latest `main` |

## Reporting a Vulnerability

If you discover a security vulnerability, please **do not** open a public GitHub issue.

Instead, report it privately by emailing:

**yhed10@gmail.com**

Include as much detail as possible:

- Description of the vulnerability and potential impact
- Steps to reproduce
- Affected version or commit
- Any suggested fix (optional)

We aim to acknowledge reports within **5 business days** and will coordinate disclosure timing with you before any public announcement.

## What to Report

Examples of in-scope reports:

- Authentication or authorization bypass
- Remote code execution or SQL injection in the application layer
- Sensitive data exposure (credentials, tokens, connection strings)
- Insecure defaults that pose risk in production deployments

## Out of Scope

- Issues in third-party dependencies without a demonstrable exploit path in Inquery
- Missing security headers or best-practice hardening with no direct vulnerability
- Social engineering or physical attacks
- Vulnerabilities in user-configured external services (Slack, Jira, MCP servers, etc.) unless caused by Inquery misconfiguration by default

## Production Deployment Reminders

When self-hosting Inquery:

- Change default administrator credentials immediately after first login
- Set strong values for `JWT_SECRET`, `DB_PASSWORD`, and other secrets in `.env`
- Restrict `CORS_ALLOWED_ORIGINS` to trusted frontend origins
- Do not expose the backend directly to the public internet without a reverse proxy and TLS

Thank you for helping keep Inquery and its users safe.
