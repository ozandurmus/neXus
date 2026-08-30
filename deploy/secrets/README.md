# deploy/secrets — DEV.2.2 mounted trust material

This directory is where `docker-compose.prod.yml` expects the real CP
`known_hosts` file and the real PAN/Panorama corporate CA bundle to live on
the host, before they are bind-mounted read-only into the `worker` container.

Nothing here is loaded automatically and nothing here is committed except
these two placeholders — `.gitignore`'s `known_hosts*` / `*.pem` rules keep
the real files out of the repository the same way `.env` is kept out.

## `known_hosts`

Provision the real Check Point MDS host key **before** the first strict-mode
connection (`SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1` fails closed with no
usable host keys — `utils/cp_ssh_trust.py`):

```
cp known_hosts.example known_hosts
ssh-keyscan -H <mds-hostname-or-ip> >> known_hosts
```

Verify the fingerprint against the MDS out-of-band before trusting it —
`ssh-keyscan` itself does not authenticate the host.

## `pan-ca-bundle.pem`

Copy the corporate CA bundle that signs the Panorama / PAN-OS HTTPS
certificate:

```
cp <your corporate CA bundle> pan-ca-bundle.pem
```

## Verifying the contract

After mounting both files and bringing the production overlay up, run the
DEV.2.2 offline check (no network, no credentials, no key material printed):

```
docker compose exec worker python main.py --persistent-secret-material-check
```
