# Contributing to MARZ

Thank you for your interest in contributing to MARZ! This guide will help you get started.

## Development Setup

1. Fork and clone the repository
2. Ensure you have Java 17+ and Maven 3.9+ installed
3. Run `mvn clean install` to build and test
4. Create a feature branch from `main`

## Pull Request Process

1. Create a feature branch: `git checkout -b feature/your-feature`
2. Write your code with tests (maintain >80% coverage)
3. Run `mvn clean verify` to ensure all tests pass
4. Submit a PR against `main` with a clear description
5. Ensure CI passes before requesting review

## Code Standards

- Follow existing code style (enforced via Checkstyle)
- All public APIs must have Javadoc
- New features require unit tests
- Integration tests for cross-component behavior

## Reporting Issues

- Use the GitHub issue templates (bug report or feature request)
- Include reproduction steps for bugs
- Include your Java version, Spring Boot version, and OS

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
