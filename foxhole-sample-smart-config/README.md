# Foxhole smart config sample

This folder contains an anonymized sample Foxhole smart config payload.

What it demonstrates:

- The header format is `# === protocol / route-group ===`.
- Entries with the same `route-group` become one profile.
- Different protocols inside that group become protocol options for that profile.
- `Start` uses the selected protocol.
- `Smart start` checks the available protocols live, remembers the best working one for the current network, and tries it first later.
