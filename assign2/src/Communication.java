import java.util.Set;

public class Communication {
    // Server asks Client for username
    public static final String AUTH = "AUTH";
    // Server asks Client for password
    public static final String PASS = "PASS";
    // Server confirms successful authentication
    public static final String AUTH_SUCCESS = "AUTH_SUCCESS";
    // Server informs about failed authentication
    public static final String AUTH_FAIL = "AUTH_FAIL";
    // Hash Set with Authentication messages
    public static final Set<String> AUTH_MESSAGES = Set.of(AUTH, PASS, AUTH_SUCCESS, AUTH_FAIL);
    // Ping
    public static final String PING = "PING";
    // Pong (Answer to Ping)
    public static final String PONG = "PONG";
}
