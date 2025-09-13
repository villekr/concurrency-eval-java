package concurrencyeval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Event(
        @JsonProperty("s3_bucket_name") String s3BucketName,
        @JsonProperty("folder") String folder,

        @JsonProperty("find") String find
) {
}
