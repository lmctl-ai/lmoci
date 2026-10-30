package ai.lmctl.lmoci;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oracle.bmc.http.client.ClientProperty;
import com.oracle.bmc.http.client.HttpClient;
import com.oracle.bmc.http.client.HttpClientBuilder;
import com.oracle.bmc.http.client.HttpProvider;
import com.oracle.bmc.http.client.HttpProviderCapability;
import com.oracle.bmc.http.client.HttpRequest;
import com.oracle.bmc.http.client.HttpResponse;
import com.oracle.bmc.http.client.Method;
import com.oracle.bmc.http.client.RequestInterceptor;
import com.oracle.bmc.http.client.Serializer;
import com.oracle.bmc.http.client.StandardClientProperties;
import com.oracle.bmc.http.client.StandardHttpProviderCapability;
import com.oracle.bmc.http.client.internal.DelegationTokenClientFilterProvider;
import com.oracle.bmc.http.client.internal.ExplicitlySetBmcModel;
import com.oracle.bmc.http.client.internal.parameterizedendpoints.ParameterizedEndpointUtil;
import com.oracle.bmc.http.client.io.DuplicatableInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.FieldPosition;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Native-image friendly {@link HttpProvider} for the OCI Java SDK backed by {@link java.net.http.HttpClient}
 * and Jackson. Avoids Jersey/HK2/Apache-HttpClient reflection and AWT reachability traps while preserving
 * full OCI SDK request signing, model serialization, and streaming semantics.
 */
public final class JdkHttpProvider implements HttpProvider, DelegationTokenClientFilterProvider {
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "date,host,content-length,expect,connection");
    }

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final JdkSerializer SERIALIZER = new JdkSerializer(OBJECT_MAPPER);

    public JdkHttpProvider() {}

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    @Override
    public HttpClientBuilder newBuilder() {
        return new JdkHttpClientBuilder();
    }

    @Override
    public Serializer getSerializer() {
        return SERIALIZER;
    }

    @Override
    public boolean hasCapability(HttpProviderCapability capability) {
        return capability == StandardHttpProviderCapability.PARAMETERIZED_ENDPOINTS;
    }

    @Override
    public RequestInterceptor newDelegationTokenClientFilter(String delegationToken) {
        return request -> {
            if (delegationToken != null) {
                request.header("opc-obo-token", delegationToken);
            }
        };
    }

    @Override
    public int getPriority() {
        return 998;
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.setDateFormat(new OciRfc3339DateFormat());
        mapper.registerModule(new JavaTimeModule());
        SimpleFilterProvider filters = new SimpleFilterProvider()
                .addFilter("explicitlySetFilter", OciExplicitlySetFilter.INSTANCE)
                .setFailOnUnknownId(false);
        mapper.setFilterProvider(filters);
        return mapper;
    }

    static final class OciRfc3339DateFormat extends StdDateFormat {
        private static final DateTimeFormatter FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

        @Override
        public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
            if (date != null) {
                toAppendTo.append(FORMATTER.format(Instant.ofEpochMilli(date.getTime())));
            }
            return toAppendTo;
        }

        @Override
        public OciRfc3339DateFormat clone() {
            return new OciRfc3339DateFormat();
        }
    }

    static final class OciExplicitlySetFilter extends SimpleBeanPropertyFilter {
        static final OciExplicitlySetFilter INSTANCE = new OciExplicitlySetFilter();

        @Override
        public void serializeAsField(
                Object pojo, JsonGenerator jgen, SerializerProvider provider, PropertyWriter writer) throws Exception {
            String name = writer.getName();
            if ("__explicitlySet__".equals(name)) {
                return;
            }
            Object value = readFieldOrProperty(pojo, writer);
            if (value != null) {
                writer.serializeAsField(pojo, jgen, provider);
                return;
            }
            if (pojo instanceof ExplicitlySetBmcModel model && model.wasPropertyExplicitlySet(name)) {
                writer.serializeAsField(pojo, jgen, provider);
            }
        }

        private static Object readFieldOrProperty(Object pojo, PropertyWriter writer) {
            if (writer instanceof BeanPropertyWriter bpw) {
                try {
                    return bpw.get(pojo);
                } catch (Exception ignored) {
                }
            }
            Class<?> current = pojo.getClass();
            String fieldName = writer.getName();
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(pojo);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    public static final class JdkSerializer implements Serializer {
        private final ObjectMapper mapper;

        JdkSerializer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public <T> T readValue(String value, Class<T> type) throws IOException {
            if (type == String.class) {
                return type.cast(value);
            }
            return mapper.readValue(value, type);
        }

        @Override
        public <T> T readValue(byte[] bytes, Class<T> type) throws IOException {
            if (type == String.class) {
                return type.cast(new String(bytes, StandardCharsets.UTF_8));
            }
            return mapper.readValue(bytes, type);
        }

        @Override
        public String writeValueAsString(Object value) throws IOException {
            if (value instanceof String str) {
                return str;
            }
            return mapper.writeValueAsString(value);
        }
    }

    private record PrioritizedInterceptor(int priority, RequestInterceptor interceptor) {}

    static final class JdkHttpClientBuilder implements HttpClientBuilder {
        private String baseUri = "http://127.0.0.1:4599";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private final List<PrioritizedInterceptor> interceptors = new ArrayList<>();
        private final Map<ClientProperty<?>, Object> properties = new HashMap<>();

        @Override
        public HttpClientBuilder baseUri(URI uri) {
            this.baseUri = uri.toString();
            return this;
        }

        @Override
        public HttpClientBuilder baseUri(String uri) {
            this.baseUri = uri;
            return this;
        }

        @Override
        public <T> HttpClientBuilder property(ClientProperty<T> key, T value) {
            properties.put(key, value);
            if (key == StandardClientProperties.CONNECT_TIMEOUT && value instanceof Duration d) {
                this.connectTimeout = d;
            } else if (key == StandardClientProperties.READ_TIMEOUT && value instanceof Duration d) {
                this.readTimeout = d;
            }
            return this;
        }

        @Override
        public HttpClientBuilder registerRequestInterceptor(int priority, RequestInterceptor interceptor) {
            interceptors.add(new PrioritizedInterceptor(priority, interceptor));
            return this;
        }

        @Override
        public HttpClient build() {
            List<RequestInterceptor> sorted = interceptors.stream()
                    .sorted(Comparator.comparingInt(PrioritizedInterceptor::priority))
                    .map(PrioritizedInterceptor::interceptor)
                    .toList();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(connectTimeout)
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            return new JdkHttpClient(client, baseUri, readTimeout, sorted);
        }
    }

    static final class JdkHttpClient implements HttpClient {
        final java.net.http.HttpClient client;
        final String baseUriString;
        final Duration readTimeout;
        final List<RequestInterceptor> requestInterceptors;

        JdkHttpClient(
                java.net.http.HttpClient client,
                String baseUriString,
                Duration readTimeout,
                List<RequestInterceptor> requestInterceptors) {
            this.client = client;
            this.baseUriString = baseUriString;
            this.readTimeout = readTimeout;
            this.requestInterceptors = requestInterceptors;
        }

        @Override
        public HttpRequest createRequest(Method method) {
            return createRequest(method, Collections.emptyMap(), Collections.emptyMap());
        }

        @Override
        public HttpRequest createRequest(
                Method method,
                Map<String, Object> requiredParametersMap,
                Map<String, Boolean> optionsMap) {
            String populated = ParameterizedEndpointUtil.INSTANCE.getEndpointWithPopulatedServiceParameters(
                    baseUriString,
                    requiredParametersMap == null ? Collections.emptyMap() : requiredParametersMap,
                    optionsMap == null ? Collections.emptyMap() : optionsMap);
            return new JdkHttpRequest(this, method, populated);
        }

        @Override
        public boolean isProcessingException(Exception exception) {
            return exception instanceof IOException;
        }

        @Override
        public boolean hasCapability(HttpProviderCapability capability) {
            return capability == StandardHttpProviderCapability.PARAMETERIZED_ENDPOINTS;
        }

        @Override
        public void close() {}
    }

    static final class DuplicatableBytesStream extends ByteArrayInputStream implements DuplicatableInputStream {
        private final byte[] raw;

        DuplicatableBytesStream(byte[] raw) {
            super(raw);
            this.raw = raw;
        }

        byte[] rawBytes() {
            return raw;
        }

        @Override
        public InputStream duplicate() {
            return new DuplicatableBytesStream(raw);
        }
    }

    static final class JdkHttpRequest implements HttpRequest {
        private final JdkHttpClient client;
        private final Method method;
        private final String baseEndpoint;
        private String rawPath;
        private final List<String> queryParts;
        private final Map<String, List<String>> headers;
        private final Map<String, Object> attributes;
        private Object processedBody;

        JdkHttpRequest(JdkHttpClient client, Method method, String baseEndpoint) {
            this.client = client;
            this.method = method;
            URI parsed = URI.create(baseEndpoint);
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme();
            String authority = parsed.getRawAuthority();
            this.baseEndpoint = scheme + "://" + authority;
            this.rawPath = parsed.getRawPath() == null ? "" : parsed.getRawPath();
            this.queryParts = new ArrayList<>();
            if (parsed.getRawQuery() != null && !parsed.getRawQuery().isEmpty()) {
                this.queryParts.add(parsed.getRawQuery());
            }
            this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.attributes = new HashMap<>();
        }

        private JdkHttpRequest(JdkHttpRequest other) {
            this.client = other.client;
            this.method = other.method;
            this.baseEndpoint = other.baseEndpoint;
            this.rawPath = other.rawPath;
            this.queryParts = new ArrayList<>(other.queryParts);
            this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, List<String>> entry : other.headers.entrySet()) {
                this.headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            this.attributes = new HashMap<>(other.attributes);
            this.processedBody = other.processedBody;
        }

        @Override
        public Method method() {
            return method;
        }

        @Override
        public HttpRequest body(Object body) {
            if (body == null) {
                this.processedBody = "";
            } else if (body instanceof String str) {
                this.processedBody = str;
            } else if (body instanceof DuplicatableBytesStream dup) {
                this.processedBody = dup;
            } else if (body instanceof InputStream stream) {
                try {
                    this.processedBody = new DuplicatableBytesStream(stream.readAllBytes());
                } catch (IOException exc) {
                    throw new IllegalArgumentException("Unable to read InputStream body", exc);
                }
            } else if (body instanceof byte[] bytes) {
                this.processedBody = new DuplicatableBytesStream(bytes);
            } else {
                try {
                    this.processedBody = SERIALIZER.writeValueAsString(body);
                } catch (IOException exc) {
                    throw new IllegalArgumentException("Unable to serialize JSON body", exc);
                }
            }
            return this;
        }

        @Override
        public HttpRequest body(InputStream body, long contentLength) {
            return body(body);
        }

        @Override
        public Object body() {
            return processedBody;
        }

        @Override
        public HttpRequest appendPathPart(String encodedSubpath) {
            if (encodedSubpath == null || encodedSubpath.isEmpty()) {
                return this;
            }
            if ("/".equals(encodedSubpath)) {
                if (rawPath.isEmpty()) {
                    rawPath = "/";
                }
                return this;
            }
            String part = encodedSubpath.startsWith("/") ? encodedSubpath.substring(1) : encodedSubpath;
            if (part.isEmpty()) {
                if (rawPath.isEmpty()) {
                    rawPath = "/";
                }
                return this;
            }
            if (rawPath.isEmpty() || "/".equals(rawPath)) {
                rawPath = "/" + part;
            } else if (rawPath.endsWith("/")) {
                rawPath = rawPath + part;
            } else {
                rawPath = rawPath + "/" + part;
            }
            return this;
        }

        @Override
        public HttpRequest query(String name, String value) {
            if (name != null && value != null) {
                queryParts.add(name + "=" + value);
            }
            return this;
        }

        @Override
        public URI uri() {
            String path = rawPath.isEmpty() ? "/" : rawPath;
            String query = queryParts.isEmpty() ? "" : "?" + String.join("&", queryParts);
            return URI.create(baseEndpoint + path + query);
        }

        @Override
        public HttpRequest header(String name, String value) {
            if (name != null && value != null) {
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
            return this;
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public Object attribute(String name) {
            return attributes.get(name);
        }

        @Override
        public HttpRequest removeAttribute(String name) {
            attributes.remove(name);
            return this;
        }

        @Override
        public HttpRequest attribute(String name, Object value) {
            attributes.put(name, value);
            return this;
        }

        @Override
        public HttpRequest offloadExecutor(Executor executor) {
            return this;
        }

        @Override
        public HttpRequest copy() {
            return new JdkHttpRequest(this);
        }

        @Override
        public void discard() {}

        @Override
        public CompletionStage<HttpResponse> execute() {
            try {
                for (RequestInterceptor interceptor : client.requestInterceptors) {
                    interceptor.intercept(this);
                }
                URI targetUri = uri();
                java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(targetUri)
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .timeout(client.readTimeout);

                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String name = entry.getKey();
                    if ("content-length".equalsIgnoreCase(name)
                            || "expect".equalsIgnoreCase(name)
                            || "connection".equalsIgnoreCase(name)) {
                        continue;
                    }
                    for (String val : entry.getValue()) {
                        builder.header(name, val);
                    }
                }

                byte[] payloadBytes = extractPayloadBytes();
                java.net.http.HttpRequest.BodyPublisher publisher =
                        payloadBytes == null
                                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                : java.net.http.HttpRequest.BodyPublishers.ofByteArray(payloadBytes);

                switch (method) {
                    case GET -> builder.GET();
                    case DELETE -> {
                        if (payloadBytes != null && payloadBytes.length > 0) {
                            builder.method("DELETE", publisher);
                        } else {
                            builder.DELETE();
                        }
                    }
                    case HEAD -> builder.method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody());
                    case POST -> builder.POST(
                            payloadBytes == null
                                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[0])
                                    : publisher);
                    case PUT -> builder.PUT(
                            payloadBytes == null
                                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[0])
                                    : publisher);
                    case PATCH -> builder.method(
                            "PATCH",
                            payloadBytes == null
                                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[0])
                                    : publisher);
                }

                java.net.http.HttpResponse<byte[]> response =
                        client.client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                return CompletableFuture.completedFuture(new JdkHttpResponse(response));
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(new IOException("HTTP request interrupted", exc));
            } catch (Throwable exc) {
                return CompletableFuture.failedFuture(exc);
            }
        }

        private byte[] extractPayloadBytes() throws IOException {
            if (processedBody == null) {
                return null;
            }
            if (processedBody instanceof String str) {
                return str.getBytes(StandardCharsets.UTF_8);
            }
            if (processedBody instanceof DuplicatableBytesStream dup) {
                return dup.rawBytes();
            }
            if (processedBody instanceof InputStream stream) {
                return stream.readAllBytes();
            }
            if (processedBody instanceof byte[] bytes) {
                return bytes;
            }
            return SERIALIZER.writeValueAsString(processedBody).getBytes(StandardCharsets.UTF_8);
        }
    }

    static final class JdkHttpResponse implements HttpResponse {
        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] bodyBytes;

        JdkHttpResponse(java.net.http.HttpResponse<byte[]> response) {
            this.status = response.statusCode();
            this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.headers.putAll(response.headers().map());
            this.bodyBytes = response.body() == null ? new byte[0] : response.body();
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : String.join(",", values);
        }

        @Override
        public CompletionStage<InputStream> streamBody() {
            return CompletableFuture.completedFuture(new ByteArrayInputStream(bodyBytes));
        }

        @Override
        public <T> CompletionStage<T> body(Class<T> type) {
            try {
                if (bodyBytes.length == 0) {
                    return CompletableFuture.completedFuture(null);
                }
                if (type == InputStream.class) {
                    return CompletableFuture.completedFuture(type.cast(new ByteArrayInputStream(bodyBytes)));
                }
                return CompletableFuture.completedFuture(SERIALIZER.readValue(bodyBytes, type));
            } catch (Throwable exc) {
                return CompletableFuture.failedFuture(exc);
            }
        }

        @Override
        public <T> CompletionStage<List<T>> listBody(Class<T> type) {
            try {
                if (bodyBytes.length == 0) {
                    return CompletableFuture.completedFuture(Collections.emptyList());
                }
                JavaType listType = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, type);
                List<T> value = OBJECT_MAPPER.readValue(bodyBytes, listType);
                return CompletableFuture.completedFuture(value);
            } catch (Throwable exc) {
                return CompletableFuture.failedFuture(exc);
            }
        }

        @Override
        public CompletionStage<String> textBody() {
            return CompletableFuture.completedFuture(new String(bodyBytes, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {}
    }
}
