import java.util.Set;

public class Communication {
    // Server sends Client welcome packet
    public static final String WELCOME = "WELCOME";
    // Client informs Server he wants to authenticate (log in)
    public static final String CLIENT_AUTH = "CLIENT_AUTH";
    // Client informs Server he wants to reconnect
    public static final String CLIENT_RECONNECT = "CLIENT_RECONNECT";
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
    // Send client its Session Token
    // Message content -> Session Token
    // Example -> "TOKEN mytoken123"
    public static final String TOKEN = "TOKEN";
    // Request session token
    public static final String REQUEST_TOKEN = "REQUEST_TOKEN";
    // Inform Client that he reconnected successfully 
    // Message content -> Reconnection queue position
    // Example -> "REQUEST_SUCCESS 2"
    public static final String RECONNECT_SUCCESS = "RECONNECT_SUCCESS";
    // Inform Client that reconnection was refused
    public static final String RECONNECT_FAIL = "RECONNECT_FAIL";
    // Client sends register information to Server
    // Message content -> <USERNAME> <PASSOWRD>
    // Example -> "CLIENT_REGISTER myusername mypassword"
    public static final String CLIENT_REGISTER = "CLIENT_REGISTER";
    // Server informs Client that registration was a Success
    public static final String REGISTER_SUCCESS = "REGISTER_SUCCESS";
    // Server informs Client that registration Failed
    public static final String REGISTER_FAIL = "REGISTER_FAIL";
}
