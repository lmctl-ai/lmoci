package ai.lmctl.lmoci;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.StringPrivateKeySupplier;
import com.oracle.bmc.containerengine.ContainerEngineClient;
import com.oracle.bmc.containerengine.model.ClusterSummary;
import com.oracle.bmc.containerengine.requests.ListClustersRequest;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.model.Application;
import com.oracle.bmc.functions.model.ApplicationSummary;
import com.oracle.bmc.functions.model.CreateApplicationDetails;
import com.oracle.bmc.functions.requests.CreateApplicationRequest;
import com.oracle.bmc.functions.requests.DeleteApplicationRequest;
import com.oracle.bmc.functions.requests.GetApplicationRequest;
import com.oracle.bmc.functions.requests.ListApplicationsRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.model.CreateCompartmentDetails;
import com.oracle.bmc.identity.model.CreatePolicyDetails;
import com.oracle.bmc.identity.model.Group;
import com.oracle.bmc.identity.model.Policy;
import com.oracle.bmc.identity.model.RegionSubscription;
import com.oracle.bmc.identity.model.Tenancy;
import com.oracle.bmc.identity.model.User;
import com.oracle.bmc.identity.requests.CreateCompartmentRequest;
import com.oracle.bmc.identity.requests.CreatePolicyRequest;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.DeletePolicyRequest;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.GetPolicyRequest;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.requests.ListGroupsRequest;
import com.oracle.bmc.identity.requests.ListPoliciesRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.identity.requests.ListUsersRequest;
import com.oracle.bmc.keymanagement.KmsCryptoClient;
import com.oracle.bmc.keymanagement.KmsManagementClient;
import com.oracle.bmc.keymanagement.KmsVaultClient;
import com.oracle.bmc.keymanagement.model.CreateKeyDetails;
import com.oracle.bmc.keymanagement.model.CreateVaultDetails;
import com.oracle.bmc.keymanagement.model.DecryptDataDetails;
import com.oracle.bmc.keymanagement.model.EncryptDataDetails;
import com.oracle.bmc.keymanagement.model.Key;
import com.oracle.bmc.keymanagement.model.KeyShape;
import com.oracle.bmc.keymanagement.model.Vault;
import com.oracle.bmc.keymanagement.model.VaultSummary;
import com.oracle.bmc.keymanagement.requests.CreateKeyRequest;
import com.oracle.bmc.keymanagement.requests.CreateVaultRequest;
import com.oracle.bmc.keymanagement.requests.DecryptRequest;
import com.oracle.bmc.keymanagement.requests.EncryptRequest;
import com.oracle.bmc.keymanagement.requests.ListVaultsRequest;
import com.oracle.bmc.keymanagement.responses.CreateKeyResponse;
import com.oracle.bmc.keymanagement.responses.CreateVaultResponse;
import com.oracle.bmc.keymanagement.responses.DecryptResponse;
import com.oracle.bmc.keymanagement.responses.EncryptResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.Bucket;
import com.oracle.bmc.objectstorage.model.BucketSummary;
import com.oracle.bmc.objectstorage.model.CreateBucketDetails;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.model.RenameObjectDetails;
import com.oracle.bmc.objectstorage.requests.CreateBucketRequest;
import com.oracle.bmc.objectstorage.requests.DeleteBucketRequest;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetBucketRequest;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.HeadBucketRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListBucketsRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.RenameObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.HeadBucketResponse;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import com.oracle.bmc.psql.PostgresqlClient;
import com.oracle.bmc.psql.model.DbSystemSummary;
import com.oracle.bmc.psql.requests.ListDbSystemsRequest;
import com.oracle.bmc.queue.QueueAdminClient;
import com.oracle.bmc.queue.QueueClient;
import com.oracle.bmc.queue.model.CreateQueueDetails;
import com.oracle.bmc.queue.model.GetMessage;
import com.oracle.bmc.queue.model.PutMessagesDetails;
import com.oracle.bmc.queue.model.PutMessagesDetailsEntry;
import com.oracle.bmc.queue.model.Queue;
import com.oracle.bmc.queue.model.QueueSummary;
import com.oracle.bmc.queue.requests.CreateQueueRequest;
import com.oracle.bmc.queue.requests.DeleteMessageRequest;
import com.oracle.bmc.queue.requests.DeleteQueueRequest;
import com.oracle.bmc.queue.requests.GetMessagesRequest;
import com.oracle.bmc.queue.requests.GetQueueRequest;
import com.oracle.bmc.queue.requests.ListQueuesRequest;
import com.oracle.bmc.queue.requests.PutMessagesRequest;
import com.oracle.bmc.queue.responses.GetMessagesResponse;
import com.oracle.bmc.queue.responses.PutMessagesResponse;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.secrets.responses.GetSecretBundleResponse;
import com.oracle.bmc.streaming.StreamAdminClient;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.streaming.model.CreateCursorDetails;
import com.oracle.bmc.streaming.model.CreateStreamDetails;
import com.oracle.bmc.streaming.model.Message;
import com.oracle.bmc.streaming.model.PutMessagesResultEntry;
import com.oracle.bmc.streaming.model.Stream;
import com.oracle.bmc.streaming.model.StreamSummary;
import com.oracle.bmc.streaming.requests.CreateCursorRequest;
import com.oracle.bmc.streaming.requests.CreateStreamRequest;
import com.oracle.bmc.streaming.requests.DeleteStreamRequest;
import com.oracle.bmc.streaming.requests.GetStreamRequest;
import com.oracle.bmc.streaming.requests.ListStreamsRequest;
import com.oracle.bmc.streaming.responses.CreateCursorResponse;
import com.oracle.bmc.tenantmanagercontrolplane.OrganizationClient;
import com.oracle.bmc.tenantmanagercontrolplane.model.OrganizationTenancySummary;
import com.oracle.bmc.tenantmanagercontrolplane.requests.ListOrganizationsRequest;
import com.oracle.bmc.vault.VaultsClient;
import com.oracle.bmc.vault.model.Base64SecretContentDetails;
import com.oracle.bmc.vault.model.CreateSecretDetails;
import com.oracle.bmc.vault.model.Secret;
import com.oracle.bmc.vault.model.SecretSummary;
import com.oracle.bmc.vault.requests.CreateSecretRequest;
import com.oracle.bmc.vault.requests.ListSecretsRequest;
import com.oracle.bmc.vault.responses.CreateSecretResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class OciBackend {
    public static final String DEFAULT_REGION = "us-ashburn-1";
    public static final String DEFAULT_TENANCY_ID =
            "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000";
    public static final String DEFAULT_USER_ID =
            "ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000000";
    public static final String DEFAULT_FINGERPRINT = "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff";
    public static final String DEFAULT_NAMESPACE = "floci-local";

    public static final List<String> INVENTORY_SECTIONS = List.of(
            "identity",
            "regions",
            "availability_domains",
            "compartments",
            "users",
            "groups",
            "policies",
            "buckets",
            "queues",
            "streams",
            "vaults",
            "secrets",
            "functions",
            "clusters",
            "db_systems",
            "tenancies");

    public static final List<String> OWNED_RESOURCE_TYPES = List.of(
            "bucket",
            "compartment",
            "queue",
            "stream",
            "policy",
            "application");

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|passphrase=[^&\\s]+|secret=[^&\\s]+)");

    private static volatile String cachedEphemeralPem;

    private final Map<String, String> env;
    private final CostLedger costLedger;
    private final RuntimeBudget runtimeBudget;
    private final ConcurrentHashMap<String, String> regionEndpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> demoResources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, byte[]>> demoObjects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> demoQueueMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Map<String, Object>>> demoStreamMessages = new ConcurrentHashMap<>();

    private String mode;
    private String profile;
    private String defaultRegion;
    private String defaultEndpoint;

    public OciBackend(Map<String, String> env, CostLedger costLedger, RuntimeBudget runtimeBudget) {
        this.env = env;
        this.costLedger = costLedger;
        this.runtimeBudget = runtimeBudget;
        this.profile = firstNonBlank(
                env.get("LMOCI_PROFILE"),
                env.get("OCI_CLI_PROFILE"),
                "default");
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", this.profile);
        this.defaultRegion = firstNonBlank(
                env.get("LMOCI_REGION"),
                env.get("OCI_CLI_REGION"),
                env.get("OCI_REGION"),
                Rows.str(stored, "region", ""),
                DEFAULT_REGION);
        this.defaultEndpoint = firstNonBlank(
                env.get("LMOCI_ENDPOINT"),
                env.get("OCI_ENDPOINT"),
                Rows.str(stored, "endpoint", ""),
                "");
        this.mode = !this.defaultEndpoint.isBlank() ? "live" : "demo";
    }

    public String mode() {
        return mode;
    }

    public String setMode(String nextMode) {
        String normalized = nextMode == null ? "" : nextMode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("demo") && !normalized.equals("live")) {
            throw new IllegalArgumentException("mode must be 'demo' or 'live', got: " + nextMode);
        }
        this.mode = normalized;
        return this.mode;
    }

    public String profile() {
        return profile;
    }

    public String setProfile(String nextProfile) {
        String normalized = nextProfile == null ? "" : nextProfile.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("profile must not be blank");
        }
        this.profile = normalized;
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", this.profile);
        String storedRegion = Rows.str(stored, "region", "");
        if (!storedRegion.isBlank()) {
            this.defaultRegion = storedRegion;
        }
        String storedEndpoint = Rows.str(stored, "endpoint", "");
        if (!storedEndpoint.isBlank()) {
            this.defaultEndpoint = storedEndpoint;
        }
        return this.profile;
    }

    public String defaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(String region) {
        if (region != null && !region.isBlank()) {
            this.defaultRegion = region.trim();
        }
    }

    public String endpointFor(String region) {
        String resolvedRegion = resolveRegion(region);
        String specific = regionEndpoints.get(resolvedRegion);
        if (specific != null && !specific.isBlank()) {
            return specific;
        }
        if (defaultEndpoint != null && !defaultEndpoint.isBlank()) {
            return defaultEndpoint;
        }
        return "";
    }

    public Map<String, Object> setEndpoint(String region, String url) {
        String resolvedRegion = resolveRegion(region);
        String trimmed = url == null ? "" : url.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("endpoint URL must not be blank");
        }
        regionEndpoints.put(resolvedRegion, trimmed);
        if (defaultEndpoint == null || defaultEndpoint.isBlank()) {
            defaultEndpoint = trimmed;
        }
        return Rows.row(
                "region", resolvedRegion,
                "endpoint", trimmed,
                "mode", mode);
    }

    public String resolveRegion(String region) {
        if (region == null || region.isBlank()) {
            return defaultRegion;
        }
        return region.trim();
    }

    public String resolveCompartmentId(Map<?, ?> options) {
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", profile);
        return firstNonBlank(
                Rows.str(options, "compartment_id", ""),
                Rows.str(options, "compartmentId", ""),
                env.get("LMOCI_COMPARTMENT_ID"),
                env.get("OCI_COMPARTMENT_ID"),
                env.get("OCI_CLI_TENANCY"),
                env.get("LMOCI_TENANCY"),
                Rows.str(stored, "compartment_id", ""),
                Rows.str(stored, "tenancy", ""),
                DEFAULT_TENANCY_ID);
    }

    public String resolveTenancyId() {
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", profile);
        return firstNonBlank(
                env.get("OCI_CLI_TENANCY"),
                env.get("LMOCI_TENANCY"),
                Rows.str(stored, "tenancy", ""),
                DEFAULT_TENANCY_ID);
    }

    public String resolveNamespace(String region, Map<?, ?> options) {
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", profile);
        String explicit = firstNonBlank(
                Rows.str(options, "namespace", ""),
                env.get("LMOCI_NAMESPACE"),
                env.get("OCI_NAMESPACE"),
                Rows.str(stored, "namespace", ""));
        if (!explicit.isBlank()) {
            return explicit;
        }
        if ("demo".equals(mode)) {
            return DEFAULT_NAMESPACE;
        }
        try (ObjectStorageClient client = objectStorageClient(region)) {
            record("objectstorage", "GetNamespace");
            String ns = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();
            return ns == null || ns.isBlank() ? DEFAULT_NAMESPACE : ns.trim().replaceAll("^\"|\"$", "");
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.GetNamespace", exc);
        }
    }

    public Map<String, Object> snapshot(String region, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        Map<String, Object> sections = new LinkedHashMap<>();
        for (String section : INVENTORY_SECTIONS) {
            sections.put(section, inventory(section, resolvedRegion, options));
        }
        return Rows.row(
                "provider", "oci",
                "mode", mode,
                "profile", profile,
                "region", resolvedRegion,
                "compartment_id", resolveCompartmentId(options),
                "sections", Collections.unmodifiableMap(sections),
                "cost", costLedger.summary());
    }

    public List<Map<String, Object>> inventory(String section, String region, Map<?, ?> options) {
        String normalized = section == null ? "" : section.trim().toLowerCase(Locale.ROOT);
        if (!INVENTORY_SECTIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unknown OCI inventory section: " + section + " (expected one of " + String.join(", ", INVENTORY_SECTIONS) + ")");
        }
        String resolvedRegion = resolveRegion(region);
        String compartmentId = resolveCompartmentId(options);
        if ("demo".equals(mode)) {
            record(normalized, "List" + capitalize(normalized));
            return demoInventory(normalized, resolvedRegion, compartmentId);
        }
        return liveInventory(normalized, resolvedRegion, compartmentId, options);
    }

    private List<Map<String, Object>> demoInventory(String section, String region, String compartmentId) {
        return switch (section) {
            case "identity" -> List.of(Rows.row(
                    "tenancy_id", resolveTenancyId(),
                    "user_id", DEFAULT_USER_ID,
                    "region", region,
                    "profile", profile,
                    "mode", "demo"));
            case "regions" -> List.of(
                    Rows.row("name", region, "key", "IAD", "status", "READY", "is_home_region", true),
                    Rows.row("name", "us-phoenix-1", "key", "PHX", "status", "READY", "is_home_region", false));
            case "availability_domains" -> List.of(
                    Rows.row("name", "Uocm:" + region.toUpperCase(Locale.ROOT) + "-AD-1", "compartment_id", compartmentId),
                    Rows.row("name", "Uocm:" + region.toUpperCase(Locale.ROOT) + "-AD-2", "compartment_id", compartmentId));
            case "compartments" -> List.of(Rows.row(
                    "id", compartmentId,
                    "name", "root",
                    "description", "Demo root compartment",
                    "lifecycle_state", "ACTIVE"));
            case "users" -> List.of(Rows.row(
                    "id", DEFAULT_USER_ID,
                    "name", "demo-operator",
                    "description", "Demo OCI operator",
                    "lifecycle_state", "ACTIVE"));
            case "groups" -> List.of(Rows.row(
                    "id", "ocid1.group.oc1..demoadmins",
                    "name", "Administrators",
                    "description", "Demo administrators group",
                    "lifecycle_state", "ACTIVE"));
            case "policies" -> List.of(Rows.row(
                    "id", "ocid1.policy.oc1..demopolicy",
                    "name", "demo-baseline-policy",
                    "compartment_id", compartmentId,
                    "statement_count", 1,
                    "statements", List.of("Allow group Administrators to read all-resources in tenancy"),
                    "lifecycle_state", "ACTIVE"));
            case "buckets" -> List.of(Rows.row(
                    "name", "demo-artifacts-" + region,
                    "namespace", DEFAULT_NAMESPACE,
                    "compartment_id", compartmentId,
                    "region", region));
            case "queues" -> List.of(Rows.row(
                    "id", "ocid1.queue.oc1." + region + ".demoqueue",
                    "name", "demo-events-queue",
                    "compartment_id", compartmentId,
                    "lifecycle_state", "ACTIVE",
                    "messages_endpoint", "http://127.0.0.1:4599"));
            case "streams" -> List.of(Rows.row(
                    "id", "ocid1.stream.oc1." + region + ".demostream",
                    "name", "demo-audit-stream",
                    "compartment_id", compartmentId,
                    "partitions", 1,
                    "lifecycle_state", "ACTIVE",
                    "messages_endpoint", "http://127.0.0.1:4599"));
            case "vaults" -> List.of(Rows.row(
                    "id", "ocid1.vault.oc1." + region + ".demovault",
                    "display_name", "demo-kms-vault",
                    "compartment_id", compartmentId,
                    "vault_type", "DEFAULT",
                    "lifecycle_state", "ACTIVE",
                    "crypto_endpoint", "http://127.0.0.1:4599",
                    "management_endpoint", "http://127.0.0.1:4599"));
            case "secrets" -> List.of(Rows.row(
                    "id", "ocid1.vaultsecret.oc1." + region + ".demosecret",
                    "secret_name", "demo-app-secret",
                    "compartment_id", compartmentId,
                    "vault_id", "ocid1.vault.oc1." + region + ".demovault",
                    "lifecycle_state", "ACTIVE"));
            case "functions" -> List.of(Rows.row(
                    "id", "ocid1.fnapp.oc1." + region + ".demoapp",
                    "display_name", "demo-fn-app",
                    "compartment_id", compartmentId,
                    "lifecycle_state", "ACTIVE"));
            case "clusters" -> List.of(Rows.row(
                    "id", "ocid1.cluster.oc1." + region + ".demooke",
                    "name", "demo-oke-cluster",
                    "compartment_id", compartmentId,
                    "kubernetes_version", "v1.31.1",
                    "lifecycle_state", "ACTIVE"));
            case "db_systems" -> List.of(Rows.row(
                    "id", "ocid1.postgresqldbsystem.oc1." + region + ".demopsql",
                    "display_name", "demo-psql-system",
                    "compartment_id", compartmentId,
                    "db_version", "16",
                    "lifecycle_state", "ACTIVE"));
            case "tenancies" -> List.of(Rows.row(
                    "id", resolveTenancyId(),
                    "name", "floci-local-tenancy",
                    "home_region_key", "IAD",
                    "lifecycle_state", "ACTIVE"));
            default -> List.of();
        };
    }

    private List<Map<String, Object>> liveInventory(
            String section, String region, String compartmentId, Map<?, ?> options) {
        try {
            return switch (section) {
                case "identity" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "GetTenancy");
                        Tenancy tenancy = client.getTenancy(
                                GetTenancyRequest.builder().tenancyId(resolveTenancyId()).build()).getTenancy();
                        yield List.of(Rows.row(
                                "tenancy_id", tenancy.getId() == null ? resolveTenancyId() : tenancy.getId(),
                                "name", tenancy.getName() == null ? "tenancy" : tenancy.getName(),
                                "home_region_key", tenancy.getHomeRegionKey() == null ? "IAD" : tenancy.getHomeRegionKey(),
                                "region", region,
                                "profile", profile,
                                "mode", "live"));
                    }
                }
                case "regions" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListRegionSubscriptions");
                        List<RegionSubscription> subs = client.listRegionSubscriptions(
                                ListRegionSubscriptionsRequest.builder().tenancyId(resolveTenancyId()).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (subs != null) {
                            for (RegionSubscription sub : subs) {
                                rows.add(Rows.row(
                                        "name", nullToEmpty(sub.getRegionName()),
                                        "key", nullToEmpty(sub.getRegionKey()),
                                        "status", sub.getStatus() == null ? "READY" : sub.getStatus().getValue(),
                                        "is_home_region", Boolean.TRUE.equals(sub.getIsHomeRegion())));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "availability_domains" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListAvailabilityDomains");
                        List<AvailabilityDomain> domains = client.listAvailabilityDomains(
                                ListAvailabilityDomainsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (domains != null) {
                            for (AvailabilityDomain ad : domains) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(ad.getId()),
                                        "name", nullToEmpty(ad.getName()),
                                        "compartment_id", nullToEmpty(ad.getCompartmentId())));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "compartments" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListCompartments");
                        List<Compartment> items = client.listCompartments(
                                ListCompartmentsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (Compartment item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "description", nullToEmpty(item.getDescription()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "users" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListUsers");
                        List<User> items = client.listUsers(
                                ListUsersRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (User item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "description", nullToEmpty(item.getDescription()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "groups" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListGroups");
                        List<Group> items = client.listGroups(
                                ListGroupsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (Group item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "description", nullToEmpty(item.getDescription()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "policies" -> {
                    try (IdentityClient client = identityClient(region)) {
                        record("identity", "ListPolicies");
                        List<Policy> items = client.listPolicies(
                                ListPoliciesRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (Policy item : items) {
                                List<String> stmts = item.getStatements() == null ? List.of() : List.copyOf(item.getStatements());
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "statement_count", stmts.size(),
                                        "statements", stmts,
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "buckets" -> {
                    String ns = resolveNamespace(region, options);
                    try (ObjectStorageClient client = objectStorageClient(region)) {
                        record("objectstorage", "ListBuckets");
                        List<BucketSummary> items = client.listBuckets(
                                ListBucketsRequest.builder()
                                        .namespaceName(ns)
                                        .compartmentId(compartmentId)
                                        .build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (BucketSummary item : items) {
                                rows.add(Rows.row(
                                        "name", nullToEmpty(item.getName()),
                                        "namespace", nullToEmpty(item.getNamespace() == null ? ns : item.getNamespace()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "region", region));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "queues" -> {
                    try (QueueAdminClient client = queueAdminClient(region)) {
                        record("queue", "ListQueues");
                        List<QueueSummary> items = client.listQueues(
                                ListQueuesRequest.builder().compartmentId(compartmentId).build())
                                .getQueueCollection()
                                .getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (QueueSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getDisplayName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue(),
                                        "messages_endpoint", nullToEmpty(item.getMessagesEndpoint())));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "streams" -> {
                    try (StreamAdminClient client = streamAdminClient(region)) {
                        record("streaming", "ListStreams");
                        List<StreamSummary> items = client.listStreams(
                                ListStreamsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (StreamSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "partitions", item.getPartitions() == null ? 1 : item.getPartitions(),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue(),
                                        "messages_endpoint", nullToEmpty(item.getMessagesEndpoint())));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "vaults" -> {
                    try (KmsVaultClient client = kmsVaultClient(region)) {
                        record("kms", "ListVaults");
                        List<VaultSummary> items = client.listVaults(
                                ListVaultsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (VaultSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "display_name", nullToEmpty(item.getDisplayName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "vault_type", item.getVaultType() == null ? "DEFAULT" : item.getVaultType().getValue(),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue(),
                                        "crypto_endpoint", nullToEmpty(item.getCryptoEndpoint()),
                                        "management_endpoint", nullToEmpty(item.getManagementEndpoint())));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "secrets" -> {
                    try (VaultsClient client = vaultsClient(region)) {
                        record("vault", "ListSecrets");
                        List<SecretSummary> items = client.listSecrets(
                                ListSecretsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (SecretSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "secret_name", nullToEmpty(item.getSecretName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "vault_id", nullToEmpty(item.getVaultId()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "functions" -> {
                    try (FunctionsManagementClient client = functionsClient(region)) {
                        record("functions", "ListApplications");
                        List<ApplicationSummary> items = client.listApplications(
                                ListApplicationsRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (ApplicationSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "display_name", nullToEmpty(item.getDisplayName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "clusters" -> {
                    try (ContainerEngineClient client = containerEngineClient(region)) {
                        record("containerengine", "ListClusters");
                        List<ClusterSummary> items = client.listClusters(
                                ListClustersRequest.builder().compartmentId(compartmentId).build()).getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (ClusterSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "name", nullToEmpty(item.getName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "kubernetes_version", nullToEmpty(item.getKubernetesVersion()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "db_systems" -> {
                    try (PostgresqlClient client = postgresqlClient(region)) {
                        record("psql", "ListDbSystems");
                        List<DbSystemSummary> items = client.listDbSystems(
                                ListDbSystemsRequest.builder().compartmentId(compartmentId).build())
                                .getDbSystemCollection()
                                .getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (items != null) {
                            for (DbSystemSummary item : items) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(item.getId()),
                                        "display_name", nullToEmpty(item.getDisplayName()),
                                        "compartment_id", nullToEmpty(item.getCompartmentId()),
                                        "db_version", nullToEmpty(item.getDbVersion()),
                                        "lifecycle_state", item.getLifecycleState() == null ? "ACTIVE" : item.getLifecycleState().getValue()));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                }
                case "tenancies" -> {
                    try (OrganizationClient client = organizationClient(region)) {
                        record("tenantmanager", "ListOrganizations");
                        var orgs = client.listOrganizations(
                                ListOrganizationsRequest.builder().compartmentId(compartmentId).build())
                                .getOrganizationCollection()
                                .getItems();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (orgs != null && !orgs.isEmpty()) {
                            for (var org : orgs) {
                                rows.add(Rows.row(
                                        "id", nullToEmpty(org.getId()),
                                        "name", nullToEmpty(org.getDisplayName()),
                                        "compartment_id", nullToEmpty(org.getCompartmentId()),
                                        "lifecycle_state", org.getLifecycleState() == null ? "ACTIVE" : org.getLifecycleState().getValue()));
                            }
                        } else {
                            rows.add(Rows.row(
                                    "id", resolveTenancyId(),
                                    "name", "default-tenancy",
                                    "compartment_id", compartmentId,
                                    "lifecycle_state", "ACTIVE"));
                        }
                        yield List.copyOf(rows);
                    }
                }
                default -> List.of();
            };
        } catch (Exception exc) {
            throw sanitizeException("inventory." + section, exc);
        }
    }

    // ---- Owned Sandbox Resource Lifecycles ----

    public Map<String, Object> createOwnedResource(
            String type, String region, String name, Map<String, String> tags, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String compartmentId = resolveCompartmentId(options);
        if ("demo".equals(mode)) {
            record(type, "Create" + capitalize(type));
            String id = "bucket".equals(type)
                    ? name
                    : "ocid1." + type + ".oc1." + resolvedRegion + "." + name.replaceAll("[^a-zA-Z0-9]", "");
            Map<String, Object> record = Rows.row(
                    "type", type,
                    "id", id,
                    "name", name,
                    "region", resolvedRegion,
                    "compartment_id", compartmentId,
                    "tags", tags,
                    "status", "ACTIVE");
            demoResources.put(type + ":" + id, record);
            return record;
        }
        try {
            return switch (type) {
                case "bucket" -> {
                    String ns = resolveNamespace(resolvedRegion, options);
                    try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
                        record("objectstorage", "CreateBucket");
                        Bucket bucket = client.createBucket(CreateBucketRequest.builder()
                                .namespaceName(ns)
                                .createBucketDetails(CreateBucketDetails.builder()
                                        .name(name)
                                        .compartmentId(compartmentId)
                                        .freeformTags(tags)
                                        .build())
                                .build()).getBucket();
                        yield Rows.row(
                                "type", "bucket",
                                "id", bucket != null && bucket.getName() != null ? bucket.getName() : name,
                                "name", name,
                                "namespace", ns,
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", "ACTIVE");
                    }
                }
                case "compartment" -> {
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "CreateCompartment");
                        Compartment comp = client.createCompartment(CreateCompartmentRequest.builder()
                                .createCompartmentDetails(CreateCompartmentDetails.builder()
                                        .compartmentId(compartmentId)
                                        .name(name)
                                        .description(Rows.str(options, "description", "lmoci sandbox compartment " + name))
                                        .freeformTags(tags)
                                        .build())
                                .build()).getCompartment();
                        yield Rows.row(
                                "type", "compartment",
                                "id", comp.getId(),
                                "name", comp.getName(),
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", comp.getLifecycleState() == null ? "ACTIVE" : comp.getLifecycleState().getValue());
                    }
                }
                case "queue" -> {
                    try (QueueAdminClient client = queueAdminClient(resolvedRegion)) {
                        record("queue", "CreateQueue");
                        var resp = client.createQueue(CreateQueueRequest.builder()
                                .createQueueDetails(CreateQueueDetails.builder()
                                        .displayName(name)
                                        .compartmentId(compartmentId)
                                        .freeformTags(tags)
                                        .build())
                                .build());
                        String id = firstNonBlank(resp.getOpcWorkRequestId(), "ocid1.queue.oc1." + resolvedRegion + "." + name);
                        List<QueueSummary> queues = client.listQueues(
                                ListQueuesRequest.builder().compartmentId(compartmentId).displayName(name).build())
                                .getQueueCollection()
                                .getItems();
                        if (queues != null) {
                            for (QueueSummary q : queues) {
                                if (name.equals(q.getDisplayName()) && q.getId() != null) {
                                    id = q.getId();
                                    break;
                                }
                            }
                        }
                        yield Rows.row(
                                "type", "queue",
                                "id", id,
                                "name", name,
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", "ACTIVE");
                    }
                }
                case "stream" -> {
                    try (StreamAdminClient client = streamAdminClient(resolvedRegion)) {
                        record("streaming", "CreateStream");
                        Stream stream = client.createStream(CreateStreamRequest.builder()
                                .createStreamDetails(CreateStreamDetails.builder()
                                        .name(name)
                                        .compartmentId(compartmentId)
                                        .partitions((int) Rows.longOpt(options, "partitions", 1L))
                                        .freeformTags(tags)
                                        .build())
                                .build()).getStream();
                        yield Rows.row(
                                "type", "stream",
                                "id", stream.getId(),
                                "name", stream.getName(),
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", stream.getLifecycleState() == null ? "ACTIVE" : stream.getLifecycleState().getValue());
                    }
                }
                case "policy" -> {
                    List<String> statements = Rows.stringList(
                            options != null && options.containsKey("statements")
                                    ? options.get("statements")
                                    : List.of("Allow group Administrators to read all-resources in compartment id " + compartmentId));
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "CreatePolicy");
                        Policy policy = client.createPolicy(CreatePolicyRequest.builder()
                                .createPolicyDetails(CreatePolicyDetails.builder()
                                        .compartmentId(compartmentId)
                                        .name(name)
                                        .description(Rows.str(options, "description", "lmoci sandbox policy " + name))
                                        .statements(statements)
                                        .freeformTags(tags)
                                        .build())
                                .build()).getPolicy();
                        yield Rows.row(
                                "type", "policy",
                                "id", policy.getId(),
                                "name", policy.getName(),
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", policy.getLifecycleState() == null ? "ACTIVE" : policy.getLifecycleState().getValue());
                    }
                }
                case "application" -> {
                    List<String> subnets = Rows.stringList(
                            options != null && options.containsKey("subnet_ids")
                                    ? options.get("subnet_ids")
                                    : List.of("ocid1.subnet.oc1." + resolvedRegion + ".demosubnet"));
                    try (FunctionsManagementClient client = functionsClient(resolvedRegion)) {
                        record("functions", "CreateApplication");
                        Application app = client.createApplication(CreateApplicationRequest.builder()
                                .createApplicationDetails(CreateApplicationDetails.builder()
                                        .compartmentId(compartmentId)
                                        .displayName(name)
                                        .subnetIds(subnets)
                                        .freeformTags(tags)
                                        .build())
                                .build()).getApplication();
                        yield Rows.row(
                                "type", "application",
                                "id", app.getId(),
                                "name", app.getDisplayName(),
                                "region", resolvedRegion,
                                "compartment_id", compartmentId,
                                "tags", tags,
                                "status", app.getLifecycleState() == null ? "ACTIVE" : app.getLifecycleState().getValue());
                    }
                }
                default -> throw new IllegalArgumentException("unsupported owned resource type: " + type);
            };
        } catch (Exception exc) {
            throw sanitizeException("create." + type, exc);
        }
    }

    public Map<String, Object> describeOwnedResource(String type, String region, String id, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record(type, "Get" + capitalize(type));
            Map<String, Object> existing = demoResources.get(type + ":" + id);
            if (existing != null) {
                return existing;
            }
            return Rows.row("type", type, "id", id, "region", resolvedRegion, "status", "DELETED");
        }
        try {
            return switch (type) {
                case "bucket" -> {
                    String ns = resolveNamespace(resolvedRegion, options);
                    try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
                        record("objectstorage", "GetBucket");
                        Bucket bucket = client.getBucket(GetBucketRequest.builder()
                                .namespaceName(ns)
                                .bucketName(id)
                                .build()).getBucket();
                        yield Rows.row(
                                "type", "bucket",
                                "id", bucket.getName(),
                                "name", bucket.getName(),
                                "namespace", ns,
                                "region", resolvedRegion,
                                "status", "ACTIVE");
                    }
                }
                case "compartment" -> {
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "GetCompartment");
                        Compartment comp = client.getCompartment(GetCompartmentRequest.builder().compartmentId(id).build()).getCompartment();
                        yield Rows.row(
                                "type", "compartment",
                                "id", comp.getId(),
                                "name", comp.getName(),
                                "region", resolvedRegion,
                                "status", comp.getLifecycleState() == null ? "ACTIVE" : comp.getLifecycleState().getValue());
                    }
                }
                case "queue" -> {
                    try (QueueAdminClient client = queueAdminClient(resolvedRegion)) {
                        record("queue", "GetQueue");
                        Queue queue = client.getQueue(GetQueueRequest.builder().queueId(id).build()).getQueue();
                        yield Rows.row(
                                "type", "queue",
                                "id", queue.getId(),
                                "name", queue.getDisplayName(),
                                "region", resolvedRegion,
                                "status", queue.getLifecycleState() == null ? "ACTIVE" : queue.getLifecycleState().getValue());
                    }
                }
                case "stream" -> {
                    try (StreamAdminClient client = streamAdminClient(resolvedRegion)) {
                        record("streaming", "GetStream");
                        Stream stream = client.getStream(GetStreamRequest.builder().streamId(id).build()).getStream();
                        yield Rows.row(
                                "type", "stream",
                                "id", stream.getId(),
                                "name", stream.getName(),
                                "region", resolvedRegion,
                                "status", stream.getLifecycleState() == null ? "ACTIVE" : stream.getLifecycleState().getValue());
                    }
                }
                case "policy" -> {
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "GetPolicy");
                        Policy policy = client.getPolicy(GetPolicyRequest.builder().policyId(id).build()).getPolicy();
                        yield Rows.row(
                                "type", "policy",
                                "id", policy.getId(),
                                "name", policy.getName(),
                                "region", resolvedRegion,
                                "status", policy.getLifecycleState() == null ? "ACTIVE" : policy.getLifecycleState().getValue());
                    }
                }
                case "application" -> {
                    try (FunctionsManagementClient client = functionsClient(resolvedRegion)) {
                        record("functions", "GetApplication");
                        Application app = client.getApplication(GetApplicationRequest.builder().applicationId(id).build()).getApplication();
                        yield Rows.row(
                                "type", "application",
                                "id", app.getId(),
                                "name", app.getDisplayName(),
                                "region", resolvedRegion,
                                "status", app.getLifecycleState() == null ? "ACTIVE" : app.getLifecycleState().getValue());
                    }
                }
                default -> throw new IllegalArgumentException("unsupported owned resource type: " + type);
            };
        } catch (BmcException bmc) {
            if (bmc.getStatusCode() == 404) {
                return Rows.row("type", type, "id", id, "region", resolvedRegion, "status", "DELETED");
            }
            throw sanitizeException("describe." + type, bmc);
        } catch (Exception exc) {
            throw sanitizeException("describe." + type, exc);
        }
    }

    public Map<String, Object> deleteOwnedResource(String type, String region, String id, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record(type, "Delete" + capitalize(type));
            demoResources.remove(type + ":" + id);
            return Rows.row("type", type, "id", id, "region", resolvedRegion, "deleted", true, "status", "DELETED");
        }
        try {
            switch (type) {
                case "bucket" -> {
                    String ns = resolveNamespace(resolvedRegion, options);
                    try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
                        record("objectstorage", "DeleteBucket");
                        client.deleteBucket(DeleteBucketRequest.builder()
                                .namespaceName(ns)
                                .bucketName(id)
                                .build());
                    }
                }
                case "compartment" -> {
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "DeleteCompartment");
                        client.deleteCompartment(DeleteCompartmentRequest.builder().compartmentId(id).build());
                    }
                }
                case "queue" -> {
                    try (QueueAdminClient client = queueAdminClient(resolvedRegion)) {
                        record("queue", "DeleteQueue");
                        client.deleteQueue(DeleteQueueRequest.builder().queueId(id).build());
                    }
                }
                case "stream" -> {
                    try (StreamAdminClient client = streamAdminClient(resolvedRegion)) {
                        record("streaming", "DeleteStream");
                        client.deleteStream(DeleteStreamRequest.builder().streamId(id).build());
                    }
                }
                case "policy" -> {
                    try (IdentityClient client = identityClient(resolvedRegion)) {
                        record("identity", "DeletePolicy");
                        client.deletePolicy(DeletePolicyRequest.builder().policyId(id).build());
                    }
                }
                case "application" -> {
                    try (FunctionsManagementClient client = functionsClient(resolvedRegion)) {
                        record("functions", "DeleteApplication");
                        client.deleteApplication(DeleteApplicationRequest.builder().applicationId(id).build());
                    }
                }
                default -> throw new IllegalArgumentException("unsupported owned resource type: " + type);
            }
            return Rows.row("type", type, "id", id, "region", resolvedRegion, "deleted", true, "status", "DELETED");
        } catch (BmcException bmc) {
            if (bmc.getStatusCode() == 404) {
                return Rows.row("type", type, "id", id, "region", resolvedRegion, "deleted", true, "status", "DELETED");
            }
            throw sanitizeException("delete." + type, bmc);
        } catch (Exception exc) {
            throw sanitizeException("delete." + type, exc);
        }
    }

    // ---- Direct Object Storage Operations ----

    public Map<String, Object> bucketCreate(String region, String bucketName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        String compartmentId = resolveCompartmentId(options);
        if ("demo".equals(mode)) {
            record("objectstorage", "CreateBucket");
            demoObjects.computeIfAbsent(ns + "/" + bucketName, ignored -> new ConcurrentHashMap<>());
            return Rows.row("bucket", bucketName, "namespace", ns, "compartment_id", compartmentId, "region", resolvedRegion, "created", true);
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "CreateBucket");
            Bucket b = client.createBucket(CreateBucketRequest.builder()
                    .namespaceName(ns)
                    .createBucketDetails(CreateBucketDetails.builder()
                            .name(bucketName)
                            .compartmentId(compartmentId)
                            .build())
                    .build()).getBucket();
            return Rows.row(
                    "bucket", b != null && b.getName() != null ? b.getName() : bucketName,
                    "namespace", ns,
                    "compartment_id", compartmentId,
                    "region", resolvedRegion,
                    "created", true);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.CreateBucket", exc);
        }
    }

    public Map<String, Object> bucketHead(String region, String bucketName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "HeadBucket");
            return Rows.row("bucket", bucketName, "namespace", ns, "etag", "demo-etag-" + bucketName, "exists", true);
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "HeadBucket");
            HeadBucketResponse resp = client.headBucket(HeadBucketRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .build());
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "etag", nullToEmpty(resp.getETag()),
                    "exists", true);
        } catch (BmcException bmc) {
            if (bmc.getStatusCode() == 404) {
                return Rows.row("bucket", bucketName, "namespace", ns, "etag", "", "exists", false);
            }
            throw sanitizeException("objectstorage.HeadBucket", bmc);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.HeadBucket", exc);
        }
    }

    public Map<String, Object> bucketDelete(String region, String bucketName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "DeleteBucket");
            demoObjects.remove(ns + "/" + bucketName);
            return Rows.row("bucket", bucketName, "namespace", ns, "deleted", true);
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "DeleteBucket");
            client.deleteBucket(DeleteBucketRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .build());
            return Rows.row("bucket", bucketName, "namespace", ns, "deleted", true);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.DeleteBucket", exc);
        }
    }

    public Map<String, Object> objectPut(
            String region, String bucketName, String objectName, String content, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if ("demo".equals(mode)) {
            record("objectstorage", "PutObject");
            demoObjects.computeIfAbsent(ns + "/" + bucketName, ignored -> new ConcurrentHashMap<>())
                    .put(objectName, bytes);
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "content_length", (long) bytes.length,
                    "etag", "demo-etag-" + objectName.hashCode());
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "PutObject");
            PutObjectResponse resp = client.putObject(PutObjectRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .objectName(objectName)
                    .contentLength((long) bytes.length)
                    .contentType(Rows.str(options, "content_type", "text/plain; charset=utf-8"))
                    .putObjectBody(new ByteArrayInputStream(bytes))
                    .build());
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "content_length", (long) bytes.length,
                    "etag", nullToEmpty(resp.getETag()),
                    "opc_content_md5", nullToEmpty(resp.getOpcContentMd5()));
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.PutObject", exc);
        }
    }

    public Map<String, Object> objectGet(String region, String bucketName, String objectName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "GetObject");
            byte[] data = demoObjects.getOrDefault(ns + "/" + bucketName, Map.of())
                    .getOrDefault(objectName, ("demo-body:" + objectName).getBytes(StandardCharsets.UTF_8));
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "content", new String(data, StandardCharsets.UTF_8),
                    "content_length", (long) data.length,
                    "etag", "demo-etag-" + objectName.hashCode());
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "GetObject");
            GetObjectResponse resp = client.getObject(GetObjectRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .objectName(objectName)
                    .build());
            byte[] bytes;
            try (InputStream stream = resp.getInputStream()) {
                bytes = stream == null ? new byte[0] : stream.readAllBytes();
            }
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "content", new String(bytes, StandardCharsets.UTF_8),
                    "content_length", resp.getContentLength() == null ? (long) bytes.length : resp.getContentLength(),
                    "etag", nullToEmpty(resp.getETag()));
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.GetObject", exc);
        }
    }

    public Map<String, Object> objectHead(String region, String bucketName, String objectName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "HeadObject");
            Map<String, byte[]> bucketMap = demoObjects.getOrDefault(ns + "/" + bucketName, Map.of());
            byte[] data = bucketMap.get(objectName);
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "exists", data != null,
                    "content_length", data == null ? 0L : (long) data.length,
                    "etag", data == null ? "" : "demo-etag-" + objectName.hashCode());
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "HeadObject");
            HeadObjectResponse resp = client.headObject(HeadObjectRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .objectName(objectName)
                    .build());
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "object", objectName,
                    "exists", true,
                    "content_length", resp.getContentLength() == null ? 0L : resp.getContentLength(),
                    "etag", nullToEmpty(resp.getETag()));
        } catch (BmcException bmc) {
            if (bmc.getStatusCode() == 404) {
                return Rows.row(
                        "bucket", bucketName,
                        "namespace", ns,
                        "object", objectName,
                        "exists", false,
                        "content_length", 0L,
                        "etag", "");
            }
            throw sanitizeException("objectstorage.HeadObject", bmc);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.HeadObject", exc);
        }
    }

    public Map<String, Object> objectList(String region, String bucketName, String prefix, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        String resolvedPrefix = prefix == null ? Rows.str(options, "prefix", "") : prefix;
        if ("demo".equals(mode)) {
            record("objectstorage", "ListObjects");
            Map<String, byte[]> bucketMap = demoObjects.getOrDefault(ns + "/" + bucketName, Map.of());
            List<Map<String, Object>> objects = new ArrayList<>();
            bucketMap.entrySet().stream()
                    .filter(e -> resolvedPrefix.isEmpty() || e.getKey().startsWith(resolvedPrefix))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> objects.add(Rows.row(
                            "name", e.getKey(),
                            "size", (long) e.getValue().length)));
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "prefix", resolvedPrefix,
                    "count", objects.size(),
                    "objects", List.copyOf(objects));
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "ListObjects");
            ListObjectsRequest.Builder req = ListObjectsRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName);
            if (!resolvedPrefix.isEmpty()) {
                req.prefix(resolvedPrefix);
            }
            ListObjectsResponse resp = client.listObjects(req.build());
            List<Map<String, Object>> objects = new ArrayList<>();
            if (resp.getListObjects() != null && resp.getListObjects().getObjects() != null) {
                for (ObjectSummary summary : resp.getListObjects().getObjects()) {
                    objects.add(Rows.row(
                            "name", nullToEmpty(summary.getName()),
                            "size", summary.getSize() == null ? 0L : summary.getSize(),
                            "etag", nullToEmpty(summary.getEtag())));
                }
            }
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "prefix", resolvedPrefix,
                    "count", objects.size(),
                    "objects", List.copyOf(objects));
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.ListObjects", exc);
        }
    }

    public Map<String, Object> objectRename(
            String region, String bucketName, String sourceName, String newName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "RenameObject");
            Map<String, byte[]> bucketMap = demoObjects.computeIfAbsent(ns + "/" + bucketName, ignored -> new ConcurrentHashMap<>());
            byte[] existing = bucketMap.remove(sourceName);
            if (existing != null) {
                bucketMap.put(newName, existing);
            }
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "source_name", sourceName,
                    "new_name", newName,
                    "renamed", true);
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "RenameObject");
            client.renameObject(RenameObjectRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .renameObjectDetails(RenameObjectDetails.builder()
                            .sourceName(sourceName)
                            .newName(newName)
                            .build())
                    .build());
            return Rows.row(
                    "bucket", bucketName,
                    "namespace", ns,
                    "source_name", sourceName,
                    "new_name", newName,
                    "renamed", true);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.RenameObject", exc);
        }
    }

    public Map<String, Object> objectDelete(String region, String bucketName, String objectName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String ns = resolveNamespace(resolvedRegion, options);
        if ("demo".equals(mode)) {
            record("objectstorage", "DeleteObject");
            Map<String, byte[]> bucketMap = demoObjects.get(ns + "/" + bucketName);
            if (bucketMap != null) {
                bucketMap.remove(objectName);
            }
            return Rows.row("bucket", bucketName, "namespace", ns, "object", objectName, "deleted", true);
        }
        try (ObjectStorageClient client = objectStorageClient(resolvedRegion)) {
            record("objectstorage", "DeleteObject");
            client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(ns)
                    .bucketName(bucketName)
                    .objectName(objectName)
                    .build());
            return Rows.row("bucket", bucketName, "namespace", ns, "object", objectName, "deleted", true);
        } catch (Exception exc) {
            throw sanitizeException("objectstorage.DeleteObject", exc);
        }
    }

    // ---- Direct Queue Operations ----

    public Map<String, Object> queuePut(String region, String queueId, String content, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("queue", "PutMessages");
            demoQueueMessages.computeIfAbsent(queueId, ignored -> Collections.synchronizedList(new ArrayList<>()))
                    .add(content);
            return Rows.row("queue_id", queueId, "message_count", 1, "content", content);
        }
        try (QueueClient client = queueDataClient(resolvedRegion, options)) {
            record("queue", "PutMessages");
            PutMessagesResponse resp = client.putMessages(PutMessagesRequest.builder()
                    .queueId(queueId)
                    .putMessagesDetails(PutMessagesDetails.builder()
                            .messages(List.of(PutMessagesDetailsEntry.builder().content(content).build()))
                            .build())
                    .build());
            int count = resp.getPutMessages() != null && resp.getPutMessages().getMessages() != null
                    ? resp.getPutMessages().getMessages().size()
                    : 1;
            return Rows.row("queue_id", queueId, "message_count", count, "content", content);
        } catch (Exception exc) {
            throw sanitizeException("queue.PutMessages", exc);
        }
    }

    public Map<String, Object> queueReceive(String region, String queueId, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("queue", "GetMessages");
            List<String> list = demoQueueMessages.getOrDefault(queueId, List.of());
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                messages.add(Rows.row(
                        "id", (long) (index + 1),
                        "content", list.get(index),
                        "receipt", "demo-receipt-" + (index + 1)));
            }
            return Rows.row("queue_id", queueId, "count", messages.size(), "messages", List.copyOf(messages));
        }
        try (QueueClient client = queueDataClient(resolvedRegion, options)) {
            record("queue", "GetMessages");
            GetMessagesResponse resp = client.getMessages(GetMessagesRequest.builder()
                    .queueId(queueId)
                    .timeoutInSeconds((int) Rows.longOpt(options, "timeout_seconds", 0L))
                    .limit((int) Rows.longOpt(options, "limit", 10L))
                    .build());
            List<Map<String, Object>> messages = new ArrayList<>();
            if (resp.getGetMessages() != null && resp.getGetMessages().getMessages() != null) {
                for (GetMessage msg : resp.getGetMessages().getMessages()) {
                    messages.add(Rows.row(
                            "id", msg.getId() == null ? 0L : msg.getId(),
                            "content", nullToEmpty(msg.getContent()),
                            "receipt", nullToEmpty(msg.getReceipt())));
                }
            }
            return Rows.row("queue_id", queueId, "count", messages.size(), "messages", List.copyOf(messages));
        } catch (Exception exc) {
            throw sanitizeException("queue.GetMessages", exc);
        }
    }

    public Map<String, Object> queueDeleteMessage(String region, String queueId, String receipt, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("queue", "DeleteMessage");
            List<String> list = demoQueueMessages.get(queueId);
            if (list != null && !list.isEmpty()) {
                list.remove(0);
            }
            return Rows.row("queue_id", queueId, "receipt", receipt, "deleted", true);
        }
        try (QueueClient client = queueDataClient(resolvedRegion, options)) {
            record("queue", "DeleteMessage");
            client.deleteMessage(DeleteMessageRequest.builder()
                    .queueId(queueId)
                    .messageReceipt(receipt)
                    .build());
            return Rows.row("queue_id", queueId, "receipt", receipt, "deleted", true);
        } catch (Exception exc) {
            throw sanitizeException("queue.DeleteMessage", exc);
        }
    }

    // ---- Direct Streaming Operations ----

    public Map<String, Object> streamPut(String region, String streamId, String key, String value, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("streaming", "PutMessages");
            List<Map<String, Object>> list =
                    demoStreamMessages.computeIfAbsent(streamId, ignored -> Collections.synchronizedList(new ArrayList<>()));
            long offset = list.size();
            Map<String, Object> msg = Rows.row(
                    "partition", "0",
                    "offset", offset,
                    "key", key == null ? "" : key,
                    "value", value == null ? "" : value);
            list.add(msg);
            return Rows.row("stream_id", streamId, "partition", "0", "offset", offset, "failures", 0);
        }
        try (StreamClient client = streamDataClient(resolvedRegion, options)) {
            record("streaming", "PutMessages");
            var details = com.oracle.bmc.streaming.model.PutMessagesDetails.builder()
                    .messages(List.of(com.oracle.bmc.streaming.model.PutMessagesDetailsEntry.builder()
                            .key(key == null ? null : key.getBytes(StandardCharsets.UTF_8))
                            .value((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))
                            .build()))
                    .build();
            var resp = client.putMessages(com.oracle.bmc.streaming.requests.PutMessagesRequest.builder()
                    .streamId(streamId)
                    .putMessagesDetails(details)
                    .build());
            String partition = "0";
            long offset = 0L;
            int failures = 0;
            if (resp.getPutMessagesResult() != null) {
                failures = resp.getPutMessagesResult().getFailures() == null ? 0 : resp.getPutMessagesResult().getFailures();
                List<PutMessagesResultEntry> entries = resp.getPutMessagesResult().getEntries();
                if (entries != null && !entries.isEmpty()) {
                    PutMessagesResultEntry first = entries.get(0);
                    if (first.getPartition() != null) {
                        partition = first.getPartition();
                    }
                    if (first.getOffset() != null) {
                        offset = first.getOffset();
                    }
                }
            }
            return Rows.row("stream_id", streamId, "partition", partition, "offset", offset, "failures", failures);
        } catch (Exception exc) {
            throw sanitizeException("streaming.PutMessages", exc);
        }
    }

    public Map<String, Object> streamCursor(
            String region, String streamId, String partition, String cursorType, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String part = partition == null || partition.isBlank() ? "0" : partition;
        String typeStr = cursorType == null || cursorType.isBlank() ? "TRIM_HORIZON" : cursorType.toUpperCase(Locale.ROOT);
        if ("demo".equals(mode)) {
            record("streaming", "CreateCursor");
            return Rows.row(
                    "stream_id", streamId,
                    "partition", part,
                    "type", typeStr,
                    "cursor", "demo-cursor-" + streamId + "-" + part);
        }
        try (StreamClient client = streamDataClient(resolvedRegion, options)) {
            record("streaming", "CreateCursor");
            CreateCursorDetails.Type enumType;
            try {
                enumType = CreateCursorDetails.Type.create(typeStr);
            } catch (Exception ignored) {
                enumType = CreateCursorDetails.Type.TrimHorizon;
            }
            if (enumType == null) {
                enumType = CreateCursorDetails.Type.TrimHorizon;
            }
            CreateCursorResponse resp = client.createCursor(CreateCursorRequest.builder()
                    .streamId(streamId)
                    .createCursorDetails(CreateCursorDetails.builder()
                            .partition(part)
                            .type(enumType)
                            .build())
                    .build());
            return Rows.row(
                    "stream_id", streamId,
                    "partition", part,
                    "type", typeStr,
                    "cursor", resp.getCursor() == null ? "" : nullToEmpty(resp.getCursor().getValue()));
        } catch (Exception exc) {
            throw sanitizeException("streaming.CreateCursor", exc);
        }
    }

    public Map<String, Object> streamGetMessages(String region, String streamId, String cursor, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("streaming", "GetMessages");
            List<Map<String, Object>> messages = demoStreamMessages.getOrDefault(streamId, List.of());
            return Rows.row(
                    "stream_id", streamId,
                    "cursor", cursor,
                    "next_cursor", cursor + "-next",
                    "count", messages.size(),
                    "messages", List.copyOf(messages));
        }
        try (StreamClient client = streamDataClient(resolvedRegion, options)) {
            record("streaming", "GetMessages");
            var resp = client.getMessages(com.oracle.bmc.streaming.requests.GetMessagesRequest.builder()
                    .streamId(streamId)
                    .cursor(cursor)
                    .limit((int) Rows.longOpt(options, "limit", 10L))
                    .build());
            List<Map<String, Object>> messages = new ArrayList<>();
            if (resp.getItems() != null) {
                for (Message item : resp.getItems()) {
                    messages.add(Rows.row(
                            "partition", nullToEmpty(item.getPartition()),
                            "offset", item.getOffset() == null ? 0L : item.getOffset(),
                            "key", item.getKey() == null ? "" : new String(item.getKey(), StandardCharsets.UTF_8),
                            "value", item.getValue() == null ? "" : new String(item.getValue(), StandardCharsets.UTF_8)));
                }
            }
            return Rows.row(
                    "stream_id", streamId,
                    "cursor", cursor,
                    "next_cursor", nullToEmpty(resp.getOpcNextCursor()),
                    "count", messages.size(),
                    "messages", List.copyOf(messages));
        } catch (Exception exc) {
            throw sanitizeException("streaming.GetMessages", exc);
        }
    }

    // ---- Direct Vault / KMS / Secrets Operations ----

    public Map<String, Object> vaultCreate(String region, String displayName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String compartmentId = resolveCompartmentId(options);
        if ("demo".equals(mode)) {
            record("kms", "CreateVault");
            String ep = endpointFor(resolvedRegion).isEmpty() ? "http://127.0.0.1:4599" : endpointFor(resolvedRegion);
            return Rows.row(
                    "id", "ocid1.vault.oc1." + resolvedRegion + "." + displayName.replaceAll("[^a-zA-Z0-9]", ""),
                    "display_name", displayName,
                    "compartment_id", compartmentId,
                    "crypto_endpoint", ep,
                    "management_endpoint", ep,
                    "status", "ACTIVE");
        }
        try (KmsVaultClient client = kmsVaultClient(resolvedRegion)) {
            record("kms", "CreateVault");
            CreateVaultResponse resp = client.createVault(CreateVaultRequest.builder()
                    .createVaultDetails(CreateVaultDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .vaultType(CreateVaultDetails.VaultType.Default)
                            .build())
                    .build());
            Vault vault = resp.getVault();
            return Rows.row(
                    "id", nullToEmpty(vault.getId()),
                    "display_name", nullToEmpty(vault.getDisplayName()),
                    "compartment_id", nullToEmpty(vault.getCompartmentId()),
                    "crypto_endpoint", nullToEmpty(vault.getCryptoEndpoint()),
                    "management_endpoint", nullToEmpty(vault.getManagementEndpoint()),
                    "status", vault.getLifecycleState() == null ? "ACTIVE" : vault.getLifecycleState().getValue());
        } catch (Exception exc) {
            throw sanitizeException("kms.CreateVault", exc);
        }
    }

    public Map<String, Object> keyCreate(String region, String displayName, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String compartmentId = resolveCompartmentId(options);
        if ("demo".equals(mode)) {
            record("kms", "CreateKey");
            return Rows.row(
                    "id", "ocid1.key.oc1." + resolvedRegion + "." + displayName.replaceAll("[^a-zA-Z0-9]", ""),
                    "display_name", displayName,
                    "compartment_id", compartmentId,
                    "algorithm", "AES",
                    "length", 32,
                    "status", "ENABLED");
        }
        try (KmsManagementClient client = kmsManagementClient(resolvedRegion, options)) {
            record("kms", "CreateKey");
            CreateKeyResponse resp = client.createKey(CreateKeyRequest.builder()
                    .createKeyDetails(CreateKeyDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .keyShape(KeyShape.builder()
                                    .algorithm(KeyShape.Algorithm.Aes)
                                    .length((int) Rows.longOpt(options, "length", 32L))
                                    .build())
                            .build())
                    .build());
            Key key = resp.getKey();
            return Rows.row(
                    "id", nullToEmpty(key.getId()),
                    "display_name", nullToEmpty(key.getDisplayName()),
                    "compartment_id", nullToEmpty(key.getCompartmentId()),
                    "algorithm", "AES",
                    "length", 32,
                    "status", key.getLifecycleState() == null ? "ENABLED" : key.getLifecycleState().getValue());
        } catch (Exception exc) {
            throw sanitizeException("kms.CreateKey", exc);
        }
    }

    public Map<String, Object> kmsEncrypt(String region, String keyId, String plaintext, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String b64Plain = Base64.getEncoder().encodeToString((plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
        if ("demo".equals(mode)) {
            record("kms", "Encrypt");
            String cipher = Base64.getEncoder().encodeToString(("demo-cipher:" + b64Plain).getBytes(StandardCharsets.UTF_8));
            return Rows.row("key_id", keyId, "ciphertext", cipher);
        }
        try (KmsCryptoClient client = kmsCryptoClient(resolvedRegion, options)) {
            record("kms", "Encrypt");
            EncryptResponse resp = client.encrypt(EncryptRequest.builder()
                    .encryptDataDetails(EncryptDataDetails.builder()
                            .keyId(keyId)
                            .plaintext(b64Plain)
                            .build())
                    .build());
            return Rows.row(
                    "key_id", keyId,
                    "ciphertext", resp.getEncryptedData() == null ? "" : nullToEmpty(resp.getEncryptedData().getCiphertext()));
        } catch (Exception exc) {
            throw sanitizeException("kms.Encrypt", exc);
        }
    }

    public Map<String, Object> kmsDecrypt(String region, String keyId, String ciphertext, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("kms", "Decrypt");
            String decoded = new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8);
            String innerB64 = decoded.startsWith("demo-cipher:") ? decoded.substring("demo-cipher:".length()) : ciphertext;
            String plain = new String(Base64.getDecoder().decode(innerB64), StandardCharsets.UTF_8);
            return Rows.row("key_id", keyId, "plaintext", plain);
        }
        try (KmsCryptoClient client = kmsCryptoClient(resolvedRegion, options)) {
            record("kms", "Decrypt");
            DecryptResponse resp = client.decrypt(DecryptRequest.builder()
                    .decryptDataDetails(DecryptDataDetails.builder()
                            .keyId(keyId)
                            .ciphertext(ciphertext)
                            .build())
                    .build());
            String b64Plain = resp.getDecryptedData() == null ? "" : nullToEmpty(resp.getDecryptedData().getPlaintext());
            String plain = b64Plain.isEmpty()
                    ? ""
                    : new String(Base64.getDecoder().decode(b64Plain), StandardCharsets.UTF_8);
            return Rows.row("key_id", keyId, "plaintext", plain);
        } catch (Exception exc) {
            throw sanitizeException("kms.Decrypt", exc);
        }
    }

    public Map<String, Object> secretCreate(
            String region, String secretName, String vaultId, String keyId, String secretText, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        String compartmentId = resolveCompartmentId(options);
        String b64 = Base64.getEncoder().encodeToString((secretText == null ? "" : secretText).getBytes(StandardCharsets.UTF_8));
        if ("demo".equals(mode)) {
            record("vault", "CreateSecret");
            return Rows.row(
                    "id", "ocid1.vaultsecret.oc1." + resolvedRegion + "." + secretName.replaceAll("[^a-zA-Z0-9]", ""),
                    "secret_name", secretName,
                    "vault_id", vaultId,
                    "key_id", keyId,
                    "compartment_id", compartmentId,
                    "status", "ACTIVE");
        }
        try (VaultsClient client = vaultsClient(resolvedRegion)) {
            record("vault", "CreateSecret");
            CreateSecretResponse resp = client.createSecret(CreateSecretRequest.builder()
                    .createSecretDetails(CreateSecretDetails.builder()
                            .compartmentId(compartmentId)
                            .secretName(secretName)
                            .vaultId(vaultId)
                            .keyId(keyId)
                            .secretContent(Base64SecretContentDetails.builder().content(b64).build())
                            .build())
                    .build());
            Secret secret = resp.getSecret();
            return Rows.row(
                    "id", nullToEmpty(secret.getId()),
                    "secret_name", nullToEmpty(secret.getSecretName()),
                    "vault_id", nullToEmpty(secret.getVaultId()),
                    "key_id", nullToEmpty(secret.getKeyId()),
                    "compartment_id", nullToEmpty(secret.getCompartmentId()),
                    "status", secret.getLifecycleState() == null ? "ACTIVE" : secret.getLifecycleState().getValue());
        } catch (Exception exc) {
            throw sanitizeException("vault.CreateSecret", exc);
        }
    }

    public Map<String, Object> secretGet(String region, String secretId, boolean reveal, Map<?, ?> options) {
        String resolvedRegion = resolveRegion(region);
        if ("demo".equals(mode)) {
            record("secrets", "GetSecretBundle");
            return Rows.row(
                    "secret_id", secretId,
                    "version_number", 1L,
                    "secret_value", reveal ? "demo-secret-value" : "***REDACTED***");
        }
        try (SecretsClient client = secretsClient(resolvedRegion)) {
            record("secrets", "GetSecretBundle");
            GetSecretBundleResponse resp = client.getSecretBundle(GetSecretBundleRequest.builder()
                    .secretId(secretId)
                    .build());
            var bundle = resp.getSecretBundle();
            String value = "***REDACTED***";
            if (reveal && bundle != null && bundle.getSecretBundleContent() instanceof Base64SecretBundleContentDetails b64) {
                String encoded = b64.getContent();
                if (encoded != null) {
                    value = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                }
            }
            return Rows.row(
                    "secret_id", secretId,
                    "version_number", bundle == null || bundle.getVersionNumber() == null ? 1L : bundle.getVersionNumber(),
                    "secret_value", value);
        } catch (Exception exc) {
            throw sanitizeException("secrets.GetSecretBundle", exc);
        }
    }

    // ---- Client Factories ----

    private IdentityClient identityClient(String region) {
        IdentityClient.Builder builder = IdentityClient.builder();
        return configureClient(builder, region, null, IdentityClient::setRegion, IdentityClient::setEndpoint);
    }

    private ObjectStorageClient objectStorageClient(String region) {
        ObjectStorageClient.Builder builder = ObjectStorageClient.builder();
        return configureClient(builder, region, null, ObjectStorageClient::setRegion, ObjectStorageClient::setEndpoint);
    }

    private QueueAdminClient queueAdminClient(String region) {
        QueueAdminClient.Builder builder = QueueAdminClient.builder();
        return configureClient(builder, region, null, QueueAdminClient::setRegion, QueueAdminClient::setEndpoint);
    }

    private QueueClient queueDataClient(String region, Map<?, ?> options) {
        String override = Rows.str(options, "messages_endpoint", Rows.str(options, "endpoint", ""));
        QueueClient.Builder builder = QueueClient.builder();
        return configureClient(builder, region, override, QueueClient::setRegion, QueueClient::setEndpoint);
    }

    private StreamAdminClient streamAdminClient(String region) {
        StreamAdminClient.Builder builder = StreamAdminClient.builder();
        return configureClient(builder, region, null, StreamAdminClient::setRegion, StreamAdminClient::setEndpoint);
    }

    private StreamClient streamDataClient(String region, Map<?, ?> options) {
        String override = firstNonBlank(
                Rows.str(options, "messages_endpoint", ""),
                Rows.str(options, "endpoint", ""),
                endpointFor(region),
                "https://cell-1.streaming." + resolveRegion(region) + ".oci.oraclecloud.com");
        return StreamClient.builder()
                .endpoint(override)
                .build(authProvider(region));
    }

    private KmsVaultClient kmsVaultClient(String region) {
        KmsVaultClient.Builder builder = KmsVaultClient.builder();
        return configureClient(builder, region, null, KmsVaultClient::setRegion, KmsVaultClient::setEndpoint);
    }

    private KmsManagementClient kmsManagementClient(String region, Map<?, ?> options) {
        String override = firstNonBlank(
                Rows.str(options, "management_endpoint", ""),
                Rows.str(options, "endpoint", ""),
                endpointFor(region),
                "https://management.kms." + resolveRegion(region) + ".oraclecloud.com");
        KmsManagementClient client = KmsManagementClient.builder()
                .endpoint(override)
                .build(authProvider(region));
        return client;
    }

    private KmsCryptoClient kmsCryptoClient(String region, Map<?, ?> options) {
        String override = firstNonBlank(
                Rows.str(options, "crypto_endpoint", ""),
                Rows.str(options, "endpoint", ""),
                endpointFor(region),
                "https://crypto.kms." + resolveRegion(region) + ".oraclecloud.com");
        KmsCryptoClient client = KmsCryptoClient.builder()
                .endpoint(override)
                .build(authProvider(region));
        return client;
    }

    private VaultsClient vaultsClient(String region) {
        VaultsClient.Builder builder = VaultsClient.builder();
        return configureClient(builder, region, null, VaultsClient::setRegion, VaultsClient::setEndpoint);
    }

    private SecretsClient secretsClient(String region) {
        SecretsClient.Builder builder = SecretsClient.builder();
        return configureClient(builder, region, null, SecretsClient::setRegion, SecretsClient::setEndpoint);
    }

    private FunctionsManagementClient functionsClient(String region) {
        FunctionsManagementClient.Builder builder = FunctionsManagementClient.builder();
        return configureClient(builder, region, null, FunctionsManagementClient::setRegion, FunctionsManagementClient::setEndpoint);
    }

    private ContainerEngineClient containerEngineClient(String region) {
        ContainerEngineClient.Builder builder = ContainerEngineClient.builder();
        return configureClient(builder, region, null, ContainerEngineClient::setRegion, ContainerEngineClient::setEndpoint);
    }

    private PostgresqlClient postgresqlClient(String region) {
        PostgresqlClient.Builder builder = PostgresqlClient.builder();
        return configureClient(builder, region, null, PostgresqlClient::setRegion, PostgresqlClient::setEndpoint);
    }

    private OrganizationClient organizationClient(String region) {
        OrganizationClient.Builder builder = OrganizationClient.builder();
        return configureClient(builder, region, null, OrganizationClient::setRegion, OrganizationClient::setEndpoint);
    }

    @FunctionalInterface
    private interface ClientSetter<C> {
        void accept(C client, String value);
    }

    private <C, B extends com.oracle.bmc.common.RegionalClientBuilder<B, C>> C configureClient(
            B builder,
            String region,
            String explicitEndpoint,
            ClientSetter<C> regionSetter,
            ClientSetter<C> endpointSetter) {
        String resolvedRegion = resolveRegion(region);
        String ep = firstNonBlank(explicitEndpoint, endpointFor(resolvedRegion));
        if (!ep.isBlank()) {
            builder.endpoint(ep);
        }
        C client = builder.build(authProvider(resolvedRegion));
        if (ep.isBlank()) {
            regionSetter.accept(client, resolvedRegion);
        } else {
            endpointSetter.accept(client, ep);
        }
        return client;
    }

    private AbstractAuthenticationDetailsProvider authProvider(String region) {
        Map<String, Object> stored = CredStore.findRawFields(null, "oci", profile);
        String tenancy = firstNonBlank(
                env.get("OCI_CLI_TENANCY"),
                env.get("LMOCI_TENANCY"),
                Rows.str(stored, "tenancy", ""),
                DEFAULT_TENANCY_ID);
        String user = firstNonBlank(
                env.get("OCI_CLI_USER"),
                env.get("LMOCI_USER"),
                Rows.str(stored, "user", ""),
                DEFAULT_USER_ID);
        String fingerprint = firstNonBlank(
                env.get("OCI_CLI_FINGERPRINT"),
                env.get("LMOCI_FINGERPRINT"),
                Rows.str(stored, "fingerprint", ""),
                DEFAULT_FINGERPRINT);
        String passphrase = firstNonBlank(
                env.get("OCI_CLI_PASSPHRASE"),
                env.get("LMOCI_PASSPHRASE"),
                Rows.str(stored, "passphrase", ""),
                "");
        String privateKeyPem = resolvePrivateKeyPem(stored);

        if (privateKeyPem.isBlank()) {
            String configFile = firstNonBlank(env.get("OCI_CONFIG_FILE"), "");
            if (!configFile.isBlank() && Files.isRegularFile(Path.of(configFile))) {
                try {
                    return new ConfigFileAuthenticationDetailsProvider(
                            ConfigFileReader.parse(configFile, profile.toUpperCase(Locale.ROOT)));
                } catch (Exception ignored) {
                }
            }
            privateKeyPem = ephemeralPrivateKeyPem();
        }

        SimpleAuthenticationDetailsProvider.SimpleAuthenticationDetailsProviderBuilder builder =
                SimpleAuthenticationDetailsProvider.builder()
                        .tenantId(tenancy)
                        .userId(user)
                        .fingerprint(fingerprint)
                        .privateKeySupplier(new StringPrivateKeySupplier(privateKeyPem));
        if (!passphrase.isEmpty()) {
            builder.passPhrase(passphrase);
        }
        try {
            builder.region(Region.fromRegionCodeOrId(resolveRegion(region)));
        } catch (Exception ignored) {
            builder.region(Region.US_ASHBURN_1);
        }
        return builder.build();
    }

    private String resolvePrivateKeyPem(Map<String, Object> stored) {
        String inline = firstNonBlank(
                env.get("OCI_CLI_KEY_CONTENT"),
                env.get("LMOCI_PRIVATE_KEY"),
                Rows.str(stored, "private_key", ""));
        if (!inline.isBlank()) {
            return inline;
        }
        String keyFile = firstNonBlank(
                env.get("OCI_CLI_KEY_FILE"),
                env.get("LMOCI_PRIVATE_KEY_FILE"),
                Rows.str(stored, "private_key_file", ""));
        if (!keyFile.isBlank()) {
            try {
                return Files.readString(Path.of(keyFile), StandardCharsets.UTF_8);
            } catch (Exception exc) {
                throw new IllegalArgumentException("failed to read OCI private key file: " + keyFile);
            }
        }
        return "";
    }

    private static String ephemeralPrivateKeyPem() {
        String existing = cachedEphemeralPem;
        if (existing != null) {
            return existing;
        }
        synchronized (OciBackend.class) {
            if (cachedEphemeralPem != null) {
                return cachedEphemeralPem;
            }
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair pair = gen.generateKeyPair();
                String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded());
                cachedEphemeralPem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
                return cachedEphemeralPem;
            } catch (Exception exc) {
                throw new IllegalStateException("failed to generate ephemeral RSA keypair", exc);
            }
        }
    }

    private void record(String service, String operation) {
        runtimeBudget.check(service + "." + operation);
        costLedger.recordRequest(service, operation);
    }

    public static RuntimeException sanitizeException(String context, Throwable exc) {
        String raw = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
        String sanitized = SECRET_PATTERN.matcher(raw).replaceAll("[REDACTED]");
        if (exc instanceof BmcException bmc) {
            return new IllegalStateException(
                    "OCI error in " + context + " (HTTP " + bmc.getStatusCode() + ", code="
                            + nullToEmpty(bmc.getServiceCode()) + "): " + sanitized);
        }
        return new IllegalStateException("OCI error in " + context + ": " + sanitized);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
