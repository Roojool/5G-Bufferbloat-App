## Summary

<!-- What changed, why, and what is intentionally not included? -->

## Validation

- [ ] `:app:assembleDebug` passed locally.
- [ ] Relevant unit/instrumentation tests were added or updated.
- [ ] `:app:testDebugUnitTest` and `:app:lintDebug` passed locally.
- [ ] For network changes: redacted real-device evidence is included or linked.
- [ ] I distinguish verified behavior from code that only compiles.

## Privacy and safety

- [ ] This change adds no remote relay, analytics/telemetry backend, TLS interception, payload decryption, or traffic-data resale.
- [ ] I did not add secrets, signing material, packet captures, personal identifiers, or private traffic to the repository.
- [ ] Failure paths safely stop/bypass the local interception path rather than silently black-holing traffic.

## Documentation

- [ ] I updated documentation, compatibility notes, and limitations where behavior or user expectations changed.
