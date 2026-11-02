# Go Port Pilot Findings (`lmoci` on `lmcode-go` + `lmcloud`)

## 1. Coexistence of Shared `lmcloud` Schema and Legacy `lmoci` Schema

Following `~/lmctl-mono/lmscript/durable-memory/dsl-framework.md` (and the `lmor cpsat` / `lmor lp` multi-schema subcommand pattern), one `lmoci` host binary serves multiple schemas via subcommands:

- **`lmoci cloud --lmcode FILE | --lmcode-schema [--json] | --lmcode-signatures [--without-std]`**:
  Selects the provider-neutral **`lmcloud`** schema (`schema/lmcloud.dsl.json`, context `cloud: Cloud`), identical across `lmaws`, `lmazure`, `lmgcp`, and `lmoci`.
- **Default top-level `lmoci ...`**:
  During this Phase A–C pilot (before the 68 legacy provider-specific OCI methods in `schema/lmoci.dsl.json` are ported to Go), top-level `lmoci --lmcode FILE | --lmcode-schema [--json] | --lmcode-signatures [--without-std]` also serves the shared `lmcloud` schema so `/home/ubuntu/cloud/lmcloud/tests/run-conformance.sh` works directly without wrapper scripts. When the legacy OCI surface is ported in a subsequent phase, `lmoci cloud ...` remains the canonical selector for `lmcloud.dsl.json` and top-level `lmoci ...` serves `lmoci.dsl.json`.

---

## 2. Spec & Conformance Findings (`lmcloud`)

### Finding 1 — Service selector `"storage"` in `tests/conformance/object-storage/*.lms`
- **Evidence**: All 12 object-storage conformance programs (`/home/ubuntu/cloud/lmcloud/tests/conformance/object-storage/01-bucket-lifecycle.lms` through `12-auth-enforced-denial.lms`) invoke `cloud.set_endpoint("storage", ep)`.
- **Gap in `02-object-storage-design.md`**: Section M2 listed `""`, `"s3"`, `"blob"`, `"gcs"`, and `"objectstorage"` as valid service selectors, omitting `"storage"`.
- **Resolution in `lmoci`**: `ValidateServiceName` and `resolveServiceEndpointLocked` in `internal/host/validation.go` and `internal/host/host.go` accept `"storage"` (as well as `"object-storage"` and `"object_storage"`) alongside `""`, `"s3"`, `"blob"`, `"gcs"`, `"objectstorage"`, `"sqs"`, `"queue"`, and `"pubsub"`.
- **Recommendation for `lmcloud`**: Add `"storage"` to the documented service allowlist in `durable-memory/02-object-storage-design.md`.

### Finding 2 — Queue `visibility_timeout_seconds` bound (`0..600`) and `"google"` substring guard in `11-queue-validation-guards.lms`
- **Evidence**: `/home/ubuntu/cloud/lmcloud/tests/conformance/queue/11-queue-validation-guards.lms` lines 43–44 (`cloud.queue_create("my-google-queue")`), lines 59–63 (`cloud.queue_receive(q, 1, 0, -1)` and `cloud.queue_receive(q, 1, 0, 601)`), and lines 72–76 (`cloud.queue_nack(q, "r", -1)` and `cloud.queue_nack(q, "r", 601)`) assert pre-flight `ErrorCode.INVALID_ARGUMENT` (`http_status() == 0`).
- **Resolution in `lmoci`**: `ValidateQueueName` rejects reserved substrings `"google"` and `"g00gle"`, and `ValidateQueueReceiveParams` / `ValidateQueueNackTimeout` enforce `0..600` seconds for `visibility_timeout_seconds`.

### Finding 3 — Arbitrary binary bytes in `queue_send` (`04-message-unicode-and-binary.lms`)
- **Evidence**: `/home/ubuntu/cloud/lmcloud/tests/conformance/queue/04-message-unicode-and-binary.lms` line 19 passes `str_hex_decode("0001027f80feff41424300")` (containing NUL `0x00` and non-UTF-8 bytes `0x80`, `0xfe`, `0xff`) to `cloud.queue_send(q, p)`.
- **Resolution in `lmoci`**: `ValidateQueueBody` does not require valid UTF-8 (only `len(body) <= 49152`). `EncodeQueueBody` automatically encodes any payload that is not valid UTF-8 or contains control bytes (`< 0x20` other than `\t`, `\n`, `\r`, or `0x7F`) with the portable `"__lmcloud_b64__:"` base64 prefix so JSON serialization over OCI Queue preserves raw binary bytes verbatim.

### Finding 4 — `lmcode.RegisterTesting` vs `print(bool|float|int)` in `schema/lmcloud.dsl.json`
- **Evidence**: In `github.com/lmctl-ai/lmcode-go` (`stdlib/testing.schema-fragment.json`), `lmcode.RegisterTesting(reg, os.Stdout)` registers only `fn assert_close(float, float, float) -> void` and `fn print(string) -> void`. However, `schema/lmcloud.dsl.json` declares `print(bool)`, `print(float)`, `print(int)`, and `print(string)`.
- **Resolution in `lmoci`**: `host.Register` in `internal/host/register.go` calls `lmcode.RegisterTesting(reg, os.Stdout)` and registers the three additional `print` overloads (`bool`, `float`, `int`) with dry callbacks so `lmoci --lmcode-signatures` and `lmoci --lmcode-signatures --without-std` are byte-identical to `lmscript --lmcode-signatures schema/lmcloud.dsl.json`.

---

## 3. Simulator Findings (`ocisim`)

### Finding 5 — `ocisim` Queue `UpdateMessage` (`PUT /20210201/queues/{queueId}/messages/{receipt}`) Support and Receipt `404` Error Code
- **Good news**: `ocisim` (`v0.2.0`) **already supports** `PUT /20210201/queues/{queueId}/messages/{receipt}` (`UpdateMessage`) including `visibilityInSeconds=0` for immediate redelivery! Scenarios `06-visibility-timeout-and-nack` and `07-visibility-extend-then-ack` pass completely against `ocisim`.
- **Subtlety on invalid/already-acked receipts**: When `DELETE` (`DeleteMessage`) or `PUT` (`UpdateMessage`) is called on `/20210201/queues/{queueId}/messages/{receipt}` for a receipt that is no longer in-flight or does not exist, `ocisim` returns HTTP `404` with body:
  ```json
  {"code": "NotAuthorizedOrNotFound", "message": "No in-flight message for receipt: <receipt>"}
  ```
  Whereas IAM policy denial in `ocisim --auth enforce` (`OciAuthFilter.java` line 210) returns HTTP `403` with `{"code": "NotAuthorizedOrNotFound", "message": "Authorization failed or requested resource not found."}`.
- **Resolution in `lmoci`**: Because `QueueAck` and `QueueNack` first resolve the queue OCID via `ListQueues` (which authenticates the caller, checks queue permission, and verifies the queue exists), a subsequent HTTP `404` on `/messages/{receipt}` means the receipt itself is stale/unknown (`ErrorCode.OK` for idempotent `queue_ack`; `ErrorCode.INVALID_ARGUMENT` for `queue_nack`), while HTTP `403` maps to `ErrorCode.PERMISSION_DENIED`.

---

## hostkit port (branch `hostkit-port`, 2026-11-02)

The Go host now keeps only OCI-specific code: `internal/oci` (adapter, signing, simulator
credentials, OCI REST, error classification) and `cmd/lmoci` (CLI surface + memory watchdog).
Validation, codec, retry, endpoint policy, pagination, error model/abort format, invoke loop,
registrations and dry handling come from `github.com/lmctl-ai/lmcloud/hostkit` (branch
`shared-go`, published `github.com/lmctl-ai/lmcloud v0.1.0`). The old `internal/host`, `internal/invoke` and
`internal/safety` packages and the bespoke `--dry`, `--manifest`, `--endpoint`, `--region`,
`--sweep` flags are gone; the resolutions recorded above for `internal/host/*` are superseded.

### OCI adapter behaviour
- Routing per call by the endpoint hostkit resolved: none + `LMOCI_LIVE` unset → offline
  in-process fake (hostkit `fakeadapter`); none + `LMOCI_LIVE=1`, or any non-loopback endpoint →
  `UNAUTHENTICATED`/`LiveCredentialsNotImplemented` (TODO: OCI_* env + `~/.oci/config` chain);
  loopback → signed OCI REST (oci-go-sdk used for signing only, so no SDK retries).
- Simulator credentials (spec 02 M3): `LMOCI_SIM_KEY_ID` + `LMOCI_SIM_PRIVATE_KEY_PATH` (PEM,
  64 KiB cap), else a synthetic open-mode keyId with an ephemeral key; `LMOCI_SIM_NAMESPACE`
  (else `GET /n/`; `floci-local` only when `/n/` is an unknown route); `LMOCI_SIM_COMPARTMENT_ID`
  (else the keyId's tenancy). The old `LMOCI_TENANCY/USER/FINGERPRINT/PRIVATE_KEY*` and `OCI_CLI_*`
  aliases and the loopback-403 namespace fallback were removed.
- Classification: 401 → UNAUTHENTICATED; 403 and 404 `NotAuthorizedOrNotFound` → PERMISSION_DENIED;
  `BucketNotFound`/`ObjectNotFound` → NOT_FOUND bucket/object; 404 on queue routes → NOT_FOUND queue;
  409 → CONFLICT (non-empty delete is a native 409 `Conflict`, no pre-list); 429/5xx → INTERNAL.
- Object HEAD: ocisim HEAD 404s carry no code (no body, no `opc-error-code`), so a code-less 404 is
  re-issued as `GET` with `Range: bytes=0-0` to read the code; still code-less → PERMISSION_DENIED.
- Receipts quirk (narrow): ocisim answers ack/nack of a stale or unknown receipt with
  `404 NotAuthorizedOrNotFound` "No in-flight message for receipt: …". Only that message on a message
  route is classified NOT_FOUND/message (hostkit then makes re-ack OK and nack INVALID_ARGUMENT); every
  other `NotAuthorizedOrNotFound` stays PERMISSION_DENIED. Live OCI behaviour here is unverified.
- Queues: displayName → OCID lookup follows `opc-next-page` and counts only ACTIVE queues; create and
  delete wait for the `opc-work-request-id` work request (`SUCCEEDED`; `FAILED`/`CANCELED` → INTERNAL);
  data plane stays on a loopback override, otherwise uses the queue's `messagesEndpoint` after
  `hostkit.ValidateEndpoint`. `UpdateMessage` keeps the receipt, so nack returns it unchanged.
- Never fabricated: missing message id, ETag, Content-Length or undecodable bodies → INTERNAL.

### hostkit contract feedback (no hostkit change was needed)
1. **Typed-nil trap**: adapter methods return `error`; returning a nil `*hostkit.CloudError` through
   `error` yields a non-nil interface and hostkit reports `[INTERNAL] … : OK`. Hit in `deleteQueue`
   (`return r.waitWorkRequest(...)`). Suggest `AsCloudError` treat a typed-nil `*CloudError` as success,
   or adapter methods return `*CloudError`.
2. **`cloudhost.Program` lacks `invoke.Hooks`** (memory watchdog, BeforeRun) and host CLI extras
   (`--lmcode-signatures --without-std`, `--help`, `--notices`, the `cloud` word): lmoci re-assembles
   ~30 lines of `Program.Main` from exported pieces. Suggest `Program.Hooks` and an extra-flag callback.
3. **No offline routing concept**: `""` means "live default" in the contract, but hosts that keep an
   offline fake need a per-call router, which is 17 near-identical methods (most of `adapter.go`).
   Suggest a `hostkit.Router{Offline, Remote, Pick(ctx)}` helper.
4. **`QueueCaps` is global**: the offline fake does not long-poll while OCI does, so offline mode
   ignores `wait_seconds`. Caps should be per route (or the router should apply `WaitLoop` itself).
5. **Retry of non-idempotent POST on 5xx** (spec 02 §5.2): a 500 after OCI created the bucket turns into
   409 → probe → `false` instead of `true`; a retried PutMessages may enqueue twice (at-least-once). Spec
   decision, recorded here.
6. `ReadBounded` reports http=0; adapters must copy the response status in (done here).

### Verification on this branch
`./test-go.sh`: build, linkage, CLI, lock-pinned schema bytes served, signature parity, failure exit
codes, offline smoke, HTTP-spy dry-run proof (0 mutating requests under `lmscript --dry-run`; control
real run does POST), `go vet`, `go test -race -count=3`, open ocisim conformance 25/25 (2 SKIP), and —
with `OCISIM_AUTH_JAR`/`OCISIM_AUTH_CONFIG` and the allow/deny keys — enforced conformance 25/25 (0 SKIP).
Adapter tests cover the classification table, 503 retried 3x → INTERNAL, 33,562,624-byte body
rejected, HEAD denial never absent, stalled list token, paged queue lookup, work-request wait/failure,
the narrow receipt quirk, missing message id, simulator credentials/scope and endpoint routing
(0.0.0.0 rejected before any request; live/non-loopback → not implemented; none → offline).

## hostkit v3 adoption (2026-11-02)

- **Routing:** `internal/oci/adapter.go` is now `hostkit.Router{Pick: hostkit.EndpointRoutes{Offline: fake,
  Sim: remote, UseLive: LMOCI_LIVE=1, Unavailable: live-not-implemented}}`; the 17 hand-written routing
  methods are gone and the remote implements the hostkit interfaces directly (endpoint from ctx).
- **Per-route caps:** offline fake short-polls (host wait loop: empty `queue_receive(q,1,1,30)` takes ~1 s),
  ocisim route long-polls (`timeoutInSeconds`); previously the offline route claimed LongPoll and returned at once.
- **CLI:** `cmd/lmoci` uses `cloudhost.Program` (`Init` = memory budget, `Hooks` = watchdog, `Help`, `Notices`).
- **Create ownership:** CreateBucket/CreateQueue send `freeformTags.lmcloud-create-token =
  hostkit.CreateToken(ctx)` in the create request; `BucketCreateToken` (GET bucket) and `QueueCreateToken`
  (GetQueue) read it back (ocisim stores and returns freeformTags on both). The token makes the bucket POST
  retry-safe (a retry after a lost reply gets 409 and is proven by token); the queue POST is never retried
  (OCI allows duplicate displayNames) — after an ambiguous reply the adapter re-looks-up the name and reports
  CONFLICT so hostkit resolves ownership by token. Creation timers are not used.
- **Dry enums:** go.mod replaces lmcode-go with `published lmcode-go 06cd9ae991d9` (temporary; remove when `Registry.Enum`
  is upstream and pins are bumped). `scripts/test-dry-spy.py` asserts a dry run evaluates
  `last_error().code() == ErrorCode.OK` with zero mutating requests.
- **Tests:** httpguard-based 503×3, oversize (http=200 kept), cross-origin redirect (no signature reaches the
  foreign origin), send hang-up not retried; token ownership (plain, pre-existing, lost reply) for buckets and
  queues; per-route caps. `test-go.sh`: open 25/25 (2 SKIP), enforced 25/25 (0 SKIP), race ×3.
- No hostkit v3 bug found during adoption.
