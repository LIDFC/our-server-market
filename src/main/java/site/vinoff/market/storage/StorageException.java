package site.vinoff.market.storage;

/** Anything the database refused to do. Never carries item bytes or secrets, only what an admin needs to read. */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
