<!--
Sync Impact Report:
- Version change: N/A → 1.0.0
- List of modified principles:
  - PRINCIPLE_1: [PRINCIPLE_1_NAME] → I. Pragmatic Simplicity (Grug Brained)
  - PRINCIPLE_2: [PRINCIPLE_2_NAME] → II. Layered Unidirectional Architecture
  - PRINCIPLE_3: [PRINCIPLE_3_NAME] → III. Type-Safe Error Handling
  - PRINCIPLE_4: [PRINCIPLE_4_NAME] → IV. Main-Safe Operations
  - PRINCIPLE_5: [PRINCIPLE_5_NAME] → V. Testing & Quality Assurance
- Added sections:
  - Tech Stack & Constraints
  - Development Workflow
- Removed sections: None
- Templates requiring updates:
  - .specify/templates/plan-template.md (✅ updated - already aligned)
  - .specify/templates/spec-template.md (✅ updated - already aligned)
  - .specify/templates/tasks-template.md (✅ updated - already aligned)
- Follow-up TODOs: None
-->

# Ivy Wallet Constitution

## Core Principles

### I. Pragmatic Simplicity (Grug Brained)
Prioritize the simplest possible solution that works. Avoid over-engineering and strictly follow the 80/20 principle. "Don't walk away from complexity, run!" All changes must be practical, easy to understand, and maintainable.

### II. Layered Unidirectional Architecture
Maintain a strict separation of concerns: Data Layer -> Domain Layer (optional) -> UI Layer. Use explicit data mapping (Raw -> Domain -> ViewState). UI components (Composables) must be "dumb" and only display formatted ViewState or emit events.

### III. Type-Safe Error Handling
Use functional programming patterns (ArrowKt's `Either`) to handle operations that can fail. Data sources must be total functions, wrapping IO operations in try-catch and returning error-safe results. Avoid throwing exceptions for expected failures.

### IV. Main-Safe Operations
All repository functions and heavy computation/IO must be main-safe. Use Kotlin Coroutines (`withContext(Dispatchers.IO)`) to ensure that the main UI thread is never blocked, maintaining a smooth user experience.

### V. Testing & Quality Assurance
Every contributor is their own QA. Verify all changes on a real device. Use JUnit/Kotest for business logic and Paparazzi for screenshot testing. Ensure new code doesn't break existing features and adheres to the project's styling and complexity rules.

## Tech Stack & Constraints
Ivy Wallet is 100% Kotlin and 100% Jetpack Compose. Core libraries include Hilt (DI), ArrowKt (FP), Room/DataStore (Persistence), Ktor (Networking), and Material3 (UI). Adherence to these technologies is mandatory to maintain project consistency.

## Development Workflow
Contributions follow a Fork-and-Pull-Request model. Issues must be assigned before starting work (`I'm on it`). Feature branches should follow the naming convention `fix-issue-{ID}`. PRs must target the `main` branch and include verification that the implementation works across all cases.

## Governance
This constitution supersedes all other documentation. Amendments require alignment with the core values of simplicity and stability. All code reviews must verify compliance with these principles. Use `docs/Guidelines.md` for runtime development guidance.

**Version**: 1.0.0 | **Ratified**: 2026-04-23 | **Last Amended**: 2026-04-23
