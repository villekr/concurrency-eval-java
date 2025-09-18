package concurrencyeval;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class Handler implements RequestHandler<Event, Response> {

    private final S3Client s3;

    public Handler() {
        this.s3 = S3Client.create();
    }

    @Override
    public Response handleRequest(Event event, Context context) {
        Instant start = Instant.now();
        String result;
        try {
            result = processor(event);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error processing event", e);
        }
        long millis = Duration.between(start, Instant.now()).toMillis();
        double elapsed = Math.round((millis / 1000.0) * 10.0) / 10.0; // seconds, one decimal place

        return new Response("java", "aws-sdk-sync", result, elapsed);
    }

    private String processor(Event event) throws InterruptedException, ExecutionException {
        String bucket = event.s3BucketName();
        String prefix = event.folder();
        String find = event.find();
        boolean hasFind = find != null && !find.isBlank();

        // Single list call is sufficient because bucket has <= 1000 objects
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        ListObjectsV2Response resp = s3.listObjectsV2(listReq);

        // Bounded concurrency to reduce memory pressure on low-memory Lambdas
        int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        int maxConcurrent = Math.min(32, cpu * 8); // reasonable upper bound
        Semaphore semaphore = new Semaphore(maxConcurrent);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (S3Object obj : resp.contents()) {
                final String key = obj.key();
                CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                        return get(bucket, key, find);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } finally {
                        semaphore.release();
                    }
                }, executor);
                futures.add(f);
            }

            // Ensure every object's body is fully read
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> results = futures.stream().map(CompletableFuture::join).toList();

            if (hasFind) {
                for (String r : results) {
                    if (r != null) return r; // first matching key
                }
                return "None"; // no matches found
            }
            // No find-string: return the number of S3 objects listed
            return String.valueOf(results.size());
        } finally {
            executor.shutdown();
        }
    }

    private String get(String bucketName, String key, String find) {
        GetObjectRequest getObjectReq = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(getObjectReq)) {
            // Always fully read the body
            byte[] buf = new byte[8192];
            if (find == null || find.isBlank()) {
                while (true) {
                    int n = in.read(buf);
                    if (n == -1) break;
                }
                return null;
            }

            byte[] needle = find.getBytes(StandardCharsets.UTF_8);
            if (needle.length == 0) {
                // Treat empty find as not specified
                while (true) {
                    int n = in.read(buf);
                    if (n == -1) break;
                }
                return null;
            }

            // Keep an overlap of last (needle.length - 1) bytes between chunks
            byte[] overlap = new byte[Math.max(0, needle.length - 1)];
            int overlapLen = 0;

            while (true) {
                int n = in.read(buf);
                if (n == -1) break;

                // Combine overlap + current chunk into one array to search
                byte[] hay = new byte[overlapLen + n];
                if (overlapLen > 0) System.arraycopy(overlap, 0, hay, 0, overlapLen);
                System.arraycopy(buf, 0, hay, overlapLen, n);

                if (indexOf(hay, needle) >= 0) {
                    // Found; continue reading remaining stream to satisfy "fully read" requirement
                    while (true) {
                        int m = in.read(buf);
                        if (m == -1) break;
                    }
                    return key;
                }

                // Update overlap with last (needle.length - 1) bytes of hay
                if (needle.length > 1) {
                    int keep = Math.min(needle.length - 1, hay.length);
                    overlapLen = keep;
                    System.arraycopy(hay, hay.length - keep, overlap, 0, keep);
                }
            }
        } catch (Exception e) {
            // On any error, we still want the overall workflow to proceed
            return null;
        }

        return null;
    }

    // Naive byte-array substring search
    private static int indexOf(byte[] haystack, byte[] needle) {
        Objects.requireNonNull(haystack);
        Objects.requireNonNull(needle);
        if (needle.length == 0) return 0;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}