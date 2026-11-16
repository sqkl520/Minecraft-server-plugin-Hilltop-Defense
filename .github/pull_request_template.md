## What this changes

<!-- One or two sentences. What behaviour is different after this patch? -->

## Why

<!-- The problem this solves. Link the issue if there is one: "Closes #123". -->

## How it was verified

<!--
  Be concrete. "Ran the tests" is not enough on its own for a behaviour change —
  say what you actually observed.
-->

- [ ] `./build.sh -o clean test` passes locally
- [ ] Manual check in game: <!-- describe what you did and what you saw -->

## Checklist

- [ ] One logical change — unrelated refactoring is in a separate pull request
- [ ] New or changed behaviour is covered by a test, or the reason it cannot be is explained above
- [ ] Player-visible text goes through `messages.yml` (both `en:` and `zh:` blocks)
- [ ] Log output is English
- [ ] Documentation updated in this same pull request if behaviour, config keys or commands changed
- [ ] No JARs, build output, local paths or personal data included in the diff
