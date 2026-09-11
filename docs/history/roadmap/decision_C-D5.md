# C-D5 — May the console run on the server before OIDC/RBAC exists?

## Options

- No - loopback operator workstation only
- Yes, behind the reverse proxy

## Recommendation

No. Loopback only until the full DEPLOY.1 gate set passes; docker-compose.yml gains no console port mapping in this track.
