package concurrencyeval;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Handler implements RequestHandler<Event, Response> {

    private S3AsyncClient s3;

    public Handler() {
        this.s3 = S3AsyncClient.create();
    }

    @Override
    public Response handleRequest(Event event, Context context) {
        Instant start = Instant.now();
        String result;
        try {
            result = processor(event);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error processing event", e);
        }
        long millis = Duration.between(start, Instant.now()).toMillis();
        double elapsed = Math.round((millis / 1000.0) * 10.0) / 10.0; // seconds, one decimal place

        return new Response("java", "aws-sdk", result, elapsed);
    }

    private String processor(Event event) throws InterruptedException, ExecutionException {
        String bucket = event.s3BucketName();
        String prefix = event.folder();
        String find = event.find();
        boolean hasFind = find != null && !find.isBlank();

        List<CompletableFuture<String>> futures = new ArrayList<>();

        // Single list call is sufficient because bucket has <= 1000 objects
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        ListObjectsV2Response resp = s3.listObjectsV2(listReq).get();

        for (S3Object obj : resp.contents()) {
            futures.add(get(bucket, obj.key(), find));
        }

        // Ensure every object's body is fully read
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        all.join();

        List<String> results = futures.stream().map(CompletableFuture::join).toList();

        if (hasFind) {
            for (String r : results) {
                if (r != null) return r; // first matching key
            }
            return "None"; // no matches found
        }
        // No find-string: return the number of S3 objects listed
        return String.valueOf(results.size());
    }

    private CompletableFuture<String> get(String bucketName, String key, String find) {
        GetObjectRequest getObjectReq = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        CompletableFuture<ResponseBytes<GetObjectResponse>> responseFuture =
                s3.getObject(getObjectReq, AsyncResponseTransformer.toBytes());

        return responseFuture.thenApply(responseBytes -> {
            String body = responseBytes.asUtf8String();

            if (find != null && !find.isBlank()) {
                int index = body.indexOf(find);
                if (index != -1) {
                    return key;
                }
            }

            return null;
        });
    }

}