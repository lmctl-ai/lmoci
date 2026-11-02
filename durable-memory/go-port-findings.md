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
