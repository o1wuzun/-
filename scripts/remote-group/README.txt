Remote group deployment (Windows)

1. Install Java 21 or newer and ensure java is on PATH.
2. Extract the entire ZIP to a permanent folder. Do not run inside the ZIP.
3. Double-click start.cmd. No pwsh installation or router port forwarding needed.
4. Return to Messages and requests in the community site and refresh connection status.
5. Stop with powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\stop.ps1

The public host must keep its relay, gateway and tunnel running and allow public access.
The remote PC uses outbound HTTPS. Services listen only on loopback ports 8180/8181.
This prototype uses a separate persistent H2 database in data/group. Back up data while stopped.
It does not use the public host's MySQL credentials. Do not share group-config.json or the ZIP:
they contain this group's relay credential and private join key. Share only the join key for a PRIVATE group.
PUBLIC groups require a community account but no join key.
If the temporary public URL changes, download a fresh bundle and replace group-config.json
while stopped; retain data/group* to preserve discussions and membership.
Only run one copy of this deployment at a time. This is not a replicated database deployment.
