# OpenTowns Run Guide

From the repository root in PowerShell:

```powershell
.\gradlew run
```

This launches the normal windowed game. Gradle sets the working directory to `src/` and auto-provisions the required Java 25 toolchain.

Optional commands:

```powershell
.\gradlew runHeadless -Pseed=42 -Pticks=3000
```

This runs the deterministic headless simulation with a fixed seed.

```powershell
.\gradlew test
```

This runs the JUnit test suite.

Notes:

- On first launch, the game may ask for a Towns install so it can copy `data/graphics`, `data/audio`, and `data/fonts`.
- Run commands from the repo root so relative paths resolve correctly.
