package concurrencyeval;

public class Run {
    public static void main(String[] args) {
        Handler handler = new Handler();

        String s3BucketName = System.getenv("S3_BUCKET_NAME");
        String folder = System.getenv("FOLDER");
        String find = System.getenv("FIND");

        Event event = new Event(s3BucketName, folder, find);

        Response response = handler.handleRequest(event, null);
        System.out.println(response);
    }
}
