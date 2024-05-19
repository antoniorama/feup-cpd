import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;

public class Client {

    private String username;
    private int rank;
    private final Socket socket;
    private long lastResponseTime;
    private final BufferedReader consoleReader;
    private final BufferedReader serverReader;
    private final PrintWriter serverWriter;
    private String sessionToken;

    public Client(Socket socket) throws IOException {
        this.socket = socket;
        this.consoleReader = new BufferedReader(new InputStreamReader(System.in));
        this.serverReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.serverWriter = new PrintWriter(socket.getOutputStream(), true);
    }

    public String getUsername() {
        return this.username;
    }

    public Socket getSocket() {
        return this.socket;
    }

    public int getRank() {
        return this.rank;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public void clearSessionToken() {
        this.sessionToken = "";
    }

    public long getLastResponseTime() {
        return lastResponseTime;
    }

    public void setLastResponseTime() {
        this.lastResponseTime = System.currentTimeMillis();
    }

    private void sendMessageToServer(String message) {
        serverWriter.println(message);
    }

    private void handleAuthentication(String serverMessage) throws IOException {
        switch (serverMessage) {
            case Communication.AUTH:
                System.out.print("Username: ");
                String username = consoleReader.readLine();
                setUsername(username);
                sendMessageToServer(username);
                break;
            case Communication.PASS:
                System.out.print("Password: ");
                String password = consoleReader.readLine();
                sendMessageToServer(password);
                break;
            case Communication.AUTH_FAIL:
                System.out.println("Authentication failed. Disconnecting...");
                socket.close();
                break;
            case Communication.AUTH_ALREADY_LOGGED_IN:
                System.out.println("User is already logged in.");
                break;
            case Communication.AUTH_SUCCESS:
                System.out.println("Authenticated successfully.");
                break;
        }
    }

    private void handleServerReconnection(String serverMessage) throws IOException {
        if (serverMessage.startsWith(Communication.RECONNECT_SUCCESS)) {
            String queuePos = getMessageContent(serverMessage);
            System.out.println("Reconnected with position " + queuePos);
        } else if (serverMessage.equals(Communication.RECONNECT_ALREADY_LOGGED_IN)) {
            System.out.println("You are already logged in.");
        } else if (serverMessage.equals(Communication.RECONNECT_FAIL)) {
            System.out.println("Reconnection failed. Disconnecting...");
            this.socket.close();
        } 
    }

    // TODO -> REFACTOR THIS
    private void handleServerMessage(String serverMessage) throws IOException {
        if (serverMessage.equals(Communication.PING)) {
            sendMessageToServer(Communication.PONG);
        } else if (Communication.AUTH_MESSAGES.contains(serverMessage)) {
            handleAuthentication(serverMessage);
        } else if (serverMessage.startsWith(Communication.TOKEN)) {
            storeToken(getMessageContent(serverMessage));
        } else if (serverMessage.equals(Communication.WELCOME)) {
            handleServerWelcome();
        } else if (serverMessage.equals(Communication.REQUEST_TOKEN)) {
            sendMessageToServer(retrieveToken());
        }
        else if (serverMessage.startsWith("RECONNECT")) {
            handleServerReconnection(serverMessage);
        } else if (serverMessage.startsWith("REGISTER")) {
            handleRegistration(serverMessage);
        }
        else {
            System.out.println(serverMessage);
        }
    }

    private void handleRegistration(String serverMessage) throws IOException{
        switch (serverMessage) {
            case Communication.REGISTER_SUCCESS:
                System.out.println("Account created successfully.");
                break;
            case Communication.REGISTER_FAIL:
                System.out.println("Account creation failed. Disconnecting...");
                socket.close();
                break;
            default:
                break;
        }
    }

    private void readServerMessages() throws IOException {
        String serverMessage;
        while (!socket.isClosed() && (serverMessage = serverReader.readLine()) != null) {
            handleServerMessage(serverMessage);
        }
    }

    // Example : "PROTOCOL CONTENT"
    // retrieves CONTENT
    private String getMessageContent(String serverMessage) {
        String[] parts = serverMessage.split(" ");
        if (parts.length >= 2) {
            return parts[1];
        } else {
            return "";
        }
    }

    // Stores the token in a file 
    // This simulates what would be the Client's system storage
    private void storeToken(String sessionToken) {
        try {
            String filename = "token-" + this.username + ".txt";
            File file = new File("src/database/tokens/" + filename);

            // Create the file if it doesn't exist
            FileWriter writer = new FileWriter(file, false);
            writer.write(sessionToken);
            writer.close();
            System.out.println("Token stored successfully.");
        } catch (IOException e) {
            System.out.println("Error storing token: " + e.getMessage());
        }
    }

    // Gets the token from the Client's 'system'
    private String retrieveToken() throws IOException {
        try {
            System.out.print("Token filename: ");
            String filename = consoleReader.readLine();
            File file = new File("src/database/tokens/" + filename);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (IOException e) {
            System.out.println("Your session token is invalid");
            System.out.println("Disconnecting...");
            this.socket.close();
            return null;
        }
    }

    private void handleRegister() {
        try {
            System.out.println("Create your account!");
            System.out.print("Enter username: ");
            String username = consoleReader.readLine();
            System.out.print("Enter password: ");
            String password = consoleReader.readLine();
            String msgToServer = String.format("%s %s %s", Communication.CLIENT_REGISTER, username, password);
            sendMessageToServer(msgToServer);
        } catch (IOException e) {
            System.out.println("Invalid username.");
        } 
    }

    private void handleServerWelcome() throws IOException{
        System.out.println("1. Log In");
        System.out.println("2. Reconnect");
        System.out.println("3. Create Account");
        System.out.print("Select: ");
        String answer = consoleReader.readLine();

        switch (answer) {
            case "1":
                sendMessageToServer(Communication.CLIENT_AUTH);
                break;

            case "2":
                sendMessageToServer(Communication.CLIENT_RECONNECT);
                break;

            case "3":
                handleRegister();
                break;
        
            default:
                break;
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Client <hostname> <port>");
            return;
        }

        String hostname = args[0];
        int port = Integer.parseInt(args[1]);

        try (Socket socket = new Socket(hostname, port)) {
            Client client = new Client(socket);
            client.readServerMessages();
        } catch (UnknownHostException ex) {
            System.out.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }
}
