# UI2 OpenShift deployment shape

This directory contains the smallest OpenShift-compatible deployment shape
for the frozen B1-1 contract. It uses ordinary Kubernetes resources so the
same files can be applied with `oc apply -k`.

Create the four Secret objects out of band; never commit their values:

```text
oc create secret generic ui2-service-secrets --from-file=db-dsn=./service-db-dsn
oc create secret generic ui2-worker-secrets --from-file=db-dsn=./worker-db-dsn
oc create secret generic ui2-scheduler-secrets --from-file=db-dsn=./scheduler-db-dsn
oc create secret generic ui2-migrate-secrets --from-file=db-dsn=./migrate-db-dsn
oc apply -k ui2/deploy/openshift
```

The migration Job is separate from the long-running roles and receives only
the `ui2_migrate` DSN. Service, worker, and scheduler receive only their own
mounted `ui2_app`-scoped DSN. Replace the image in an environment overlay;
the base contains no registry, credential, or secret value.

For a local CRC image stream, apply the bundled overlay instead:

```text
oc apply -k ui2/deploy/openshift/overlays/crc
```

The CRC overlay also contains a disposable PostgreSQL test deployment. Create
its secret out of band, apply the overlay, then forward the service locally:

```text
oc create secret generic ui2-postgres-test-secrets --from-literal=username=postgres --from-literal=password='<local-only-password>'
oc apply -k ui2/deploy/openshift/overlays/crc
oc port-forward service/ui2-postgres-test 15432:5432
UI2_TEST_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/ui2 \
UI2_TEST_POSTGRES_ADMIN_USER=postgres \
UI2_TEST_POSTGRES_ADMIN_PASSWORD='<local-only-password>' \
UI2_TEST_POSTGRES_DATABASE=ui2 \
./ui2/gradlew -p ui2 integrationTest
```

The PostgreSQL deployment is CRC validation infrastructure only; production
uses its separately managed dedicated PostgreSQL service and does not use this
disposable `emptyDir` database.
