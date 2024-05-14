import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class UserDatabase {
    private static final String FILE_PATH = "src/database/users.json";
    private Map<String, User> users;
    private final ObjectMapper objectMapper;

    public UserDatabase() throws IOException {
        this.objectMapper = new ObjectMapper();
        loadUsers();
    }

    private void loadUsers() throws IOException {
        File file = new File(FILE_PATH);
        if (file.exists()) {
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
        return user != null && password.equals(user.getPassword());
    }

    public void assignScore(String username, int score) throws IOException {
        User user = users.get(username);
        if (user != null) {
            user.setScore(score);
            saveUsers();
        }
    }

    public static class User {
        private String password;
        private int score;

        public User() {
        }

        public User(String password, int score) {
            this.password = password;
            this.score = score;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
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
