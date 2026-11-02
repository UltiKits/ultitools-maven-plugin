package com.ultikits.ulticloud;

/**
 * A failed UltiCloud call or a rejected configuration, with a message meant for
 * the person running the build. It carries the HTTP status, the error code and
 * the request id, never the response body.
 */
public class UltiCloudException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final String requestId;

    /**
     * Creates an exception.
     *
     * @param message   the message shown in the build output
     * @param status    the HTTP status, or 0 when no response was received
     * @param code      the error code from the error envelope, or null
     * @param requestId the request id from the error envelope, or null
     */
    public UltiCloudException(String message, int status, String code, String requestId) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
    }

    /**
     * Creates an exception for a failure that is not an HTTP response.
     *
     * @param message the message shown in the build output
     */
    public UltiCloudException(String message) {
        this(message, 0, null, null);
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status, or 0 when no response was received
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the error code.
     *
     * @return the code from the error envelope, or null
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the request id.
     *
     * @return the request id, or null
     */
    public String getRequestId() {
        return requestId;
    }
}
