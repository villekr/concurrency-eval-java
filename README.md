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

# AWS Lambda handler and packaging
- Runtime: `java21`
- Handler: `concurrencyeval.Handler::handleRequest`
- Deployment artifact: a single shaded JAR (named `function.jar`). Our CI (merge workflow) builds `function.jar`, validates it contains the handler class, and uploads it to S3.

## Troubleshooting ClassNotFoundException
If you see `Class not found: concurrencyeval.Handler`:
1. Inspect the exact artifact you deploy:
   - Ensure you are deploying a single shaded JAR (not an empty zip).
   - Inside the jar, the class `concurrencyeval/Handler.class` must exist.
2. Quick checks on your machine:
   ```bash
   jar tf function.jar | grep '^concurrencyeval/Handler.class$' || echo MISSING
   ```
3. Make sure the Lambda code configuration points to the same S3 key (and version) that the merge workflow uploaded (shown in the job summary). Re-deploying a different/stale artifact will cause this error.
4. You can also download the `function.jar` artifact produced by the workflow and upload it directly to the function to validate.
