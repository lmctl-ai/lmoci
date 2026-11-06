# lmoci recipes against ocisim

Runnable lmscript recipes that drive the real `lmoci` hosts against the local
[ocisim](https://github.com/lmctl-ai/ocisim) emulator on `127.0.0.1`. Nothing here
contacts Oracle Cloud: programs open their session with
`oci.endpoint("us-ashburn-1", "http://127.0.0.1:4599")`, the host signs loopback
requests with an ephemeral key, and no `~/.oci/config` is read. Every recipe
creates `lmoci-`prefixed, run-owned resources, asserts what the emulator did,
writes a JSON report under `data/sim/<recipe>/` and ends with `PASS <recipe>`.

Two host surfaces are used:

- **native lmoci** (`cli="lmoci"`, the OCI SDK for Java host released as `lmoci`):
  run ledgers, Object Storage, Queue, Streaming, Identity compartments and policies,
  policy risk analysis, Vault/KMS/Secrets, Functions applications, OKE inventory.
- **lmoci cloud** (`cmd/lmoci`, the Go build serving the shared
  [lmcloud](https://github.com/lmctl-ai/lmcloud) schema): portable bucket, object and
  queue operations plus `last_error()` classification used by the permissions recipe.

## Start the emulator

```bash
ocisim serve                                                   # open mode, :4599
lmscript recipes/sim/auth_enforced_denial.lms --prepare        # openssl keys + data/sim/auth_enforced_denial/ocisim-auth.json
ocisim serve --port 4600 --auth enforce --auth-config data/sim/auth_enforced_denial/ocisim-auth.json
```

## Run

```bash
export PATH="$PWD/bin:$PATH"                 # or wherever the lmoci release binary lives
go build -o build/sim/lmoci-cloud ./cmd/lmoci
lmscript recipes/sim/static_site_bucket.lms --cloud-host build/sim/lmoci-cloud
lmscript recipes/sim/object_storage_site.lms
lmscript recipes/sim/auth_enforced_denial.lms --cloud-host build/sim/lmoci-cloud
```

Common flags: `--endpoint URL` (default `OCISIM_ENDPOINT` or `http://127.0.0.1:4599`),
`--host PATH` (native host; its schema is read from the `lmoci` on PATH),
`--cloud-host PATH` (Go build, default `LMOCI_CLOUD_BIN` or `lmoci`), `--region NAME`,
`--run ID`, `--out DIR`, `--help`.

`python3 scripts/test-sim-recipes.py` starts ocisim in open and enforce mode on free
ports, prepares the enforce keys, runs every recipe in a temporary directory and tears
everything down. It takes `--ocisim`, `--lmoci`, `--cloud-host`, `--lmscript`
(or `OCISIM_BIN`, `LMOCI_BIN`, `LMOCI_CLOUD_BIN`, `LMSCRIPT_BIN`) and `--only NAME`.

## Catalog

| Recipe | Host | ocisim services | What it proves |
| --- | --- | --- | --- |
| `static_site_bucket.lms` | lmoci cloud | Object Storage | Publish a five-file static site with content types and cache metadata, list by prefix, republish with a new ETag, tear down |
| `queue_worker.lms` | lmoci cloud | Queue | Enqueue site jobs, drain with ack, nack one for redelivery, end empty, delete the queue |
| `auth_enforced_denial.lms` | lmoci cloud | Identity signatures and policy engine (`--auth enforce`) | Generate admin and deployer RSA keys, author the credential/policy file with `_json_` (group statements plus JSON denies); the deployer's own bucket and queue work, writes to and deletion of a protected bucket and any queue deletion fail `PERMISSION_DENIED`/403, an unknown key id fails `UNAUTHENTICATED`/401; SKIPs on an open-mode emulator |
| `object_storage_site.lms` | lmoci | Object Storage | Namespace, bucket, uploads, HEAD, prefix listing, atomic publish by rename, deletes, bucket inventory |
| `queue_pipeline.lms` | lmoci | Queue | Owned queue, JSON job messages, receive, acknowledge by receipt, queue inventory, ledger cleanup |
| `streaming_events.lms` | lmoci | Streaming | Owned stream, keyed page-view events, TRIM_HORIZON cursor, ordered replay |
| `iam_compartment_policy.lms` | lmoci | Identity | Owned compartment and policy, offline risk analysis from a scoped deployer statement (LOW) to tenancy-wide any-user (CRITICAL), identity listings |
| `vault_kms_secrets.lms` | lmoci | Vault, KMS, Secrets | Vault, AES key, encrypt/decrypt round trip, secret whose value reads back redacted, inventories |
| `functions_application.lms` | lmoci | Functions, OKE (mock control planes) | Owned Functions application, function and cluster inventories, availability domains and regions |
| `inventory_snapshot.lms` | lmoci | Identity, Object Storage, Queue, Streaming, Vault, Functions, OKE | Read-only snapshot as JSON plus a per-service count CSV with the request cost summary |
| `sandbox_lifecycle.lms` | lmoci | all of the above | All six owned kinds created, one deleted explicitly, cleanup in reverse order, empty ledger sweep |
| `smoke_test.lms` | lmoci cloud | Object Storage, Queue | CI smoke test: emulator answers, bucket/object/queue/message round trips, timed |
| `error_handling.lms` | lmoci cloud | Object Storage, Queue | Error contract: idempotent creates and deletes, false-not-fail probes, NOT_FOUND/CONFLICT with HTTP status, pre-flight INVALID_ARGUMENT guards |
| `object_listing_pagination.lms` | lmoci cloud | Object Storage | 120 objects, continuation-token paging in pages of 50, ascending order, prefix counts, exhaustive list |
| `binary_object_roundtrip.lms` | lmoci cloud | Object Storage | PNG header, all 256 byte values, empty object, UTF-8 text, 64 KiB file byte-exact with content types and ETags |
| `queue_visibility_timeout.lms` | lmoci cloud | Queue | In-flight messages invisible, redelivered after the visibility timeout with the same ids, nack(0) immediate redelivery, drained |
| `upload_thumbnail_pipeline.lms` | lmoci cloud | Object Storage, Queue | Uploads enqueue jobs, a worker writes thumbnails with provenance metadata, every upload gets exactly one |
| `blue_green_release.lms` | lmoci cloud | Object Storage | Two release trees, a current.json pointer, instant switch, verified rollback, prune of the retired release |
| `concurrent_uploaders.lms` | lmoci cloud | Object Storage | Four host processes upload disjoint prefixes at once (parallel_map), one listing proves nothing lost |
| `persistence_restart.lms` | lmoci cloud | Object Storage, Queue | Write phase, emulator restart with --data-dir, verify phase: objects intact, queue present (runner shows it as persistence_restart*) |
| `stream_cursor_types.lms` | lmoci | Streaming | TRIM_HORIZON replays from offset 0 while a LATEST cursor created mid-stream only sees later events |
| `policy_risk_matrix.lms` | lmoci (offline analyzer) | none | Eight candidate statements scored LOW to CRITICAL with asserted expectations and a CSV matrix |

## Emulator findings

Recorded while verifying against ocisim v0.1.9 (floci-oci 0.5.0) and lmoci v0.2.13:

- ocisim has no Organizations (`tenancies()`) or PostgreSQL (`db_systems()`) control
  plane; both return 404 and are left out of the inventory recipe.
- Vaults, keys and secrets have no delete in the host API; they stay in the
  emulator's memory until it restarts.
- Queue, bucket and object names cannot be denied by name for `CreateQueue`, which is
  authorised against the compartment; the permissions recipe denies object writes and
  bucket deletion on protected names and queue deletion on every queue instead.
- The released `lmoci` accepts `--endpoint` only on direct subcommands, not after
  `--lmcode FILE`; programs set the emulator through `oci.endpoint()`.
