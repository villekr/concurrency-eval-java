# Concurrency Evaluation - Java
Java code for [How Do You Like Your Lambda Concurrency](https://ville-karkkainen.medium.com/how-do-you-like-your-aws-lambda-concurrency-part-1-introduction-7a3f7ecfe4b5)-blog series.

# Requirements
* Java 21

The target is to implement the following pseudocode as effectively as possible using language-specific idioms and constrains to achieve concurrency/parallelism.
Mandatory requirements:
- Code must contain the following three constructs: 
  - handler: Language-specific AWS Lambda handler or equivalent entrypoint
  - processor: List objects from a specified S3 bucket and process them concurrently/parallel
  - get: Get a single object's body from S3, try to find a string if specified
- The processor-function must be encapsulated with timing functions
- S3 bucket will contain at maximum 1000 objects
- Each S3 objects' body must be fully read
- Code must return at least the following attributes as lambda handler response:
  - time (float): duration as float in seconds rounded to one decimal place
  - result (string): If find-string is specified, then the key of the first s3 object that contains that string (or None). Otherwise, the number of s3 objects listed
```
func handler(event):
    timer.start()
    result = processor(event)
    timer.stop()
    return {
        "time": timer.elapsed,
        "result": result
    }
    
func processor(event):
    s3_objects = aws_sdk.list_objects(s3_bucket)
    results = [get(s3_key, event[find]) for s3_objects]
    return first_non_none(results) if event[find] else str(len(s3_objects))

func get(s3_key, find):
    body = aws_sdk.get_object(s3_key).body
    return body.find(find) if find else None
```

# Implementation notes
- AWS SDK for Java v2 ListObjectsV2 returns up to 1000 keys per call by default. Because the requirement caps the bucket at ≤1000 objects, this implementation intentionally performs a single listObjectsV2 call without pagination.
- Object bodies are always fully read. Even when a match is found, the remainder of the stream is consumed to satisfy the requirement.
- Concurrency is achieved via Java 21 virtual threads with a bounded semaphore to limit concurrent S3 GETs.
- The handler measures elapsed time around the processor and rounds to one decimal place, and returns at least the required fields (time and result).