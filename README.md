# lmoci
Script Oracle Cloud from lmscript: typed OCI SDK host, one native executable.

Powered by the official Oracle Cloud Infrastructure (OCI) SDK for Java v3 (`com.oracle.oci.sdk:oci-java-sdk-*` v3.92.1) and the lmcode Java SDK (pinned by reviewed full commit SHA in `sdk.lock`), `lmoci` gives `lmscript` programs and CLI callers a typed, sandboxed OCI host with offline demo fixtures, local emulator support (`ocisim`), and guarded live execution.

## Quickstart

Run any example offline with synthetic OCI fixtures (no Oracle Cloud tenancy or credentials required):

```bash
lmscript examples/inventory.lms
lmscript examples/object_storage.lms
lmscript examples/queue_pipeline.lms
lmscript examples/streaming_cursor.lms
lmscript examples/vault_kms.lms
lmscript examples/policy_audit.lms
lmscript examples/sandbox_lifecycle.lms
```

Inspect the lmcode host schema and signatures:

```bash
lmoci --lmcode-schema --json
lmoci --lmcode-signatures
lmoci --self-test
```


Examples call lmoci through `run_lmcode`, using manifest v1 and the terminal result frame. By default the helper resolves `lmoci` on `PATH`. To run a local JVM build, set `LMOCI_HOST=java`, `LMOCI_HOST_ARG1=-jar`, and `LMOCI_HOST_ARG2` to `target/lmoci-$(cat VERSION)-all.jar`.

## Direct CLI Subcommands

Every direct subcommand emits a deterministic JSON envelope (`ok`, `area`, `action`, `profile`, `endpoint`, `data`, `error`) suitable for shell pipelines and automation:

```bash
# Credential store (shared ~/.lmconn/creds.db SQLite store; secrets are redacted by default)
lmoci cred set default --tenancy ocid1.tenancy.oc1..demo --user ocid1.user.oc1..demo \
  --fingerprint aa:bb:cc:dd --region us-ashburn-1 --endpoint http://127.0.0.1:4599
lmoci cred list
lmoci cred get default

# Read-only inventory across all 16 OCI sections
lmoci inventory all --demo
lmoci inventory buckets --endpoint http://127.0.0.1:4599

# Object Storage
lmoci os namespace --demo
lmoci os create-bucket --name lmoci-demo-bucket --demo
lmoci os put-object --bucket lmoci-demo-bucket --key hello.txt --text "hello from lmoci" --demo
lmoci os get-object --bucket lmoci-demo-bucket --key hello.txt --demo

# IAM Compartments, Users, Groups, and Policy Lint
lmoci iam list-compartments --demo
lmoci iam lint-policy --name sandbox-policy \
  --statement "Allow group SandboxDevs to manage objects in compartment sandbox"

# OCI Queue & Streaming
lmoci queue create --name lmoci-jobs --demo
lmoci stream create --name lmoci-events --partitions 1 --demo

# Vault, KMS Keys, and Secrets
lmoci vault list-vaults --demo
```

## Using `lmoci` with `ocisim` (Local OCI Emulator)

Ready-made recipes that exercise Object Storage sites, queues, streams, IAM compartments and policies, Vault/KMS/Secrets, Functions applications and `--auth enforce` permissions against ocisim live in [recipes/sim/README.md](recipes/sim/README.md).

Point `lmoci` at a local [`ocisim`](https://github.com/lmctl-ai/ocisim) instance (`http://127.0.0.1:4599`) via `--endpoint` or `OCI_ENDPOINT`:

```bash
export OCI_ENDPOINT=http://127.0.0.1:4599
lmoci os namespace --live --endpoint http://127.0.0.1:4599
lmscript examples/object_storage.lms
```

When targeting a loopback emulator endpoint (`127.0.0.1` or `localhost`) without an `~/.oci/config` file, `lmoci` automatically synthesizes an ephemeral RSA-2048 signing keypair so the official OCI Java SDK request-signing pipeline runs end-to-end against `ocisim`.

## Safety & Live Guardrails

1. **Offline Demo Default**: Examples and `oci.connect("demo")` use deterministic offline fixtures and never contact external endpoints unless `--live` (`LMOCI_LIVE=1`) or an explicit `--endpoint` (`OCI_ENDPOINT`) is supplied.
2. **Owned Sandbox Prefix & Tags**: Every sandbox mutation (`create_bucket`, `create_compartment`, `create_queue`, `create_stream`, `create_policy`, `create_application`) enforces the `lmoci-<runId>-` / `lmoci_<runId>_` naming prefix and stamps `freeformTags.lmoci_test = <runId>`.
3. **Run Ledger & Automatic Teardown**: `oci.with_sandbox(name, fn)` records every created resource in a persistent JSON ledger and tears down owned resources in reverse order inside a `finally` block. Aged leftovers can be swept via `lmoci --sweep <ledger_dir> --older-than-hours 24`.
4. **Secret Redaction**: `lmoci cred get` and `lmoci cred list` always mask `private_key_pem` and `passphrase` as `***REDACTED***` unless `--reveal` is explicitly passed. Error messages automatically scrub PEM blocks, OCID secrets, and bearer tokens.

## Build & Verify

```bash
# Build shaded JAR and run JVM + fake-endpoint test suites
mvn package -DskipTests
bash test.sh

# Build standalone GraalVM native executable
bash scripts/build-native.sh
LMOCI_NATIVE=1 bash test.sh
```
