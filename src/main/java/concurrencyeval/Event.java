package concurrencyeval;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Event(
        @JsonAlias({"s3_bucket_name", "s3BucketName"})
        String s3BucketName,

        @JsonProperty("folder")
        String folder,

        @JsonProperty("find")
        String find
) {
}
