import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public class UserDatabase {
    private static final String FILE_PATH = "src/database/users.json";
    private Map<String, User> users;
    private final HashSet<String> loggedInUsers = new HashSet<>();
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final BCryptPasswordEncoder tokenEncoder = new BCryptPasswordEncoder();

    public UserDatabase() throws IOException {
        this.objectMapper = new ObjectMapper();
        loadUsers();
    }

    void userLoggedIn(String username) { loggedInUsers.add(username); }

    void userLoggedOut(String username) { loggedInUsers.remove(username); }

    boolean isUserLoggedIn(String username) { return loggedInUsers.contains(username); }

    private void loadUsers() throws IOException {
        File file = new File(FILE_PATH);
        if (file.exists()) {
            if (file.length() == 0) {
                users = new HashMap<>();
                return;
            }
            users = objectMapper.readValue(file, new TypeReference<Map<String, User>>() {});
        } else {
            throw new IOException("User database file not found.");
        }
    }

    private void saveUsers() throws IOException {
        objectMapper.writeValue(new File(FILE_PATH), users);
    }

    public boolean authenticate(String username, String password) {
        User user = users.get(username);
        return user != null && passwordEncoder.matches(password, user.getPassword());
    }

    public void assignRank(String username, int rank) throws IOException {
        User user = users.get(username);
        if (user != null) {
            user.setRank(rank);
            saveUsers();
        }
    }

    // Increments User rank by 'addedRank'
    public void incrementRank(String username, int addedRank) throws IOException{
        User user = users.get(username);
        if (user != null) {
            int currRank = user.getRank();
            user.setRank(currRank + addedRank);
            saveUsers();
        }
    }

    public int getUserRank(String username) {
        User user = users.get(username);
        return user != null ? user.getRank() : -1; // Return -1 if user is not found
    }

    public String generateSessionToken() {
        return UUID.randomUUID().toString();
    }

    // Assigns session token
    // Returns not encoded session token
    // Or null if user doesn't exist
    public String assignSessionToken(String username) throws IOException {
        User user = users.get(username);
        if (user != null) {
            String token = generateSessionToken();
            String encodedToken = tokenEncoder.encode(token);
            user.setSessionToken(encodedToken);
            saveUsers();
            return token;
        }

        return null;
    }

    public String getSessionToken(String username) {
        User user = users.get(username);
        return user != null ? user.getSessionToken() : null;
    }

    // Gets username form a sessionToken
    // returns null if no user found with that sessionToken
    public String getUsernameFromToken(String sessionToken) {
        for (Map.Entry<String, User> entry : users.entrySet()) {
            String retrievedToken = entry.getValue().getSessionToken();
            if (retrievedToken != null && tokenEncoder.matches(sessionToken, retrievedToken)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void createUser(String username, String password) throws IOException {

        if (!users.containsKey(username)) {
            String encodedPassword = passwordEncoder.encode(password);
            User newUser = new User(encodedPassword, 100);
            users.put(username, newUser);
            saveUsers();
        } else {
            throw new IllegalArgumentException("Username already exists.");
        }
    }

    public static class User {
        private String password;
        private int rank;
        private String sessionToken;

        public User() {
        }

        public User(String password, int rank) {
            this.password = password;
            this.rank = rank;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }

        public String getSessionToken() {
            return sessionToken;
        }
    
        public void setSessionToken(String sessionToken) {
            this.sessionToken = sessionToken;
        }

    }

    // Main method only for testing purposes
    public static void main(String[] args) {
        try {
            UserDatabase userDatabase = new UserDatabase();
            
            // Test authentication
            System.out.println("Authenticating user1 with correct password: " + userDatabase.authenticate("user1", "password1")); // true
            System.out.println("Authenticating user2 with wrong password: " + userDatabase.authenticate("user2", "wrongpassword")); // false

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
