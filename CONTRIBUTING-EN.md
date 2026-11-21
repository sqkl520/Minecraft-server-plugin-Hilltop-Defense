[简体中文](CONTRIBUTING.md) | English

# Contributing to Hilltop Village

Thanks for taking the time to help. This file describes the process that actually
works in this repository right now — not a generic template.

## Before you start

- **Java 21** and **Maven 3.9.9** are required. The build enforces JDK 21 and will
  stop with a readable error on anything older.
- Target platform is **Paper 1.21.4**. Features that only work on other forks or
  other Minecraft versions are out of scope.
- The project has **no runtime dependencies** beyond the Paper API. Please do not
  introduce a shading/packet library without discussing it in an issue first.

## Running the build

The simplest path is the wrapper script in the repo's `tools/` directory, which
finds a JDK 21 on your machine and passes every argument through to Maven:

```bash
./tools/build.sh                       # equivalent to: mvn clean package
./tools/build.sh test                  # tests only
./tools/build.sh -DskipTests clean package
```

`tools/build.sh` needs Git Bash / MSYS on Windows. Override with `HV_JDK21_HOME`,
`HV_JDK21_GLOB` or `HV_MAVEN` if your layout differs.

If you prefer calling Maven directly, export JDK 21 first — the default shell
`JAVA_HOME` on many machines points at JDK 17 and the build will refuse to run:

```bash
export JAVA_HOME="/path/to/jdk-21"
mvn clean package
```

## Tests

The suite currently has **991 tests** (`0 failures`, `1 skipped`). The skipped one
is a Windows-only read-only-directory case and is skipped by design.

> Builds in this repository are not safe to run concurrently with another Maven
> build in the same working tree — `clean` will wipe `target/` under the other
> build and produce confusing "cannot find symbol" errors in test compilation.
> If you see that, re-run once before investigating.

```bash
./tools/build.sh -o test                          # whole suite
./tools/build.sh -o test -Dtest=ClassName         # one class
```

**Every change that touches production code needs either a new test or an
explanation in the pull request of why none is possible.** Behaviour fixes that
regression-proof themselves are the ones that get merged quickly.

Some tests are pure unit tests (no server needed). Tests that need a Bukkit
runtime use MockBukkit; `src/test/java/com/hilltopvillage/testsupport/` holds the
shared harness.

## Submitting a pull request

1. **Open an issue first** for anything larger than a typo fix. It saves you from
   writing a patch that cannot be merged.
2. Branch off `main` — do not commit directly to it.
3. Keep the change focused. One logical change per pull request; unrelated
   refactoring makes review harder and is usually asked to be split.
4. Run `./tools/build.sh -o clean test` before pushing. A red build will not be reviewed.
5. Commit messages use `type(scope): description`, and the body explains **why**
   the change is needed, not just what changed.

   ```
   fix(scoreboard): recompute line scores when the line count changes

   setScore was only called when a team was first created, so lines kept
   stale sort keys after a countdown line appeared and the sidebar order
   jumped. Scores are now recomputed whenever the desired value changes.
   ```

6. Update the documentation in the same commit when you change behaviour,
   configuration keys or commands. `README.md` and `README-EN.md` are kept in
   sync; the per-topic guides live in `docs/guides/` (the English version of each is the sibling `docs/guides/X-EN.md`).

## Code conventions

- **All log output must be English.** Logs are read by server owners and
  operators, not players, and are not localised.
- **All player-facing text lives in `messages.yml`**, under both the `en:` and
  `zh:` blocks with the same key set. Never hardcode a player-visible string in
  Java.
- Javadoc and comments in this codebase are written in Chinese and explain *why*
  a thing is done, not what the line does. Plain restatement comments are not
  wanted.
- Prefer small, named methods over deeply nested conditionals. Several modules
  carry long comments recording a past defect — please keep them; they document
  decisions that are otherwise invisible.

## Reporting a bug

Use the bug report template. The three things that make a report actionable and
without which it is hard to help:

- the plugin version and Paper version,
- the exact command or in-game action that triggered it,
- what you expected versus what happened.

Console output from `logs/latest.log` around the failure is worth more than a
paragraph of description.

## Security issues

Please do not open a public issue for a security problem — see
[SECURITY.md](SECURITY.md) for the private channel.
