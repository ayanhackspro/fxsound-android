# Antigravity Workflow

## Before each phase
1. Read relevant specifications.
2. Inspect current repository state; never assume it is empty.
3. State the phase goal, planned file changes, risks and validation commands.
4. Ask for approval at any blocking decision defined by the master prompt.

## During implementation
- Make small changes.
- Build after each coherent slice.
- Keep source changes scoped.
- Add tests before or alongside behavior.
- Do not fake unavailable Android APIs.
- Do not hardcode success states.
- Do not swallow errors without diagnostics.
- Avoid large refactors unrelated to the current phase.

## After each phase
Update `docs/IMPLEMENTATION_LOG.md` with:
- Date/time.
- Git commit/hash if available.
- Summary.
- Files changed.
- Commands actually run.
- Exact pass/fail outcomes.
- Device and OS tested.
- Blockers.
- Next action.

## Required progress report
### Completed
- ...

### Files changed
- ...

### Validation
- `command`: observed result

### Device verification
- Device/OS:
- Scenarios:
- Results:

### Known limitations
- ...

### Next phase
- ...

## Stop conditions
Stop and ask for a decision if:
- Universal rootless system-wide audio cannot be achieved with supported APIs.
- The actual FxSound DSP cannot be ported without major rewriting.
- Licence terms or dependency incompatibilities are unclear.
- A requested behavior requires private APIs, root, or bypassing capture restrictions.
- Tests reveal audible dropouts, unstable DSP or unsafe output.
