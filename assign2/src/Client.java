import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;

public class Client {

    private String username;
    private Socket socket;
    private long lastResponseTime;
    private BufferedReader consoleReader;
    private BufferedReader serverReader;
    private PrintWriter serverWriter;

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

    public void setUsername(String username) {
        this.username = username;
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
            case Communication.AUTH_SUCCESS:
                System.out.println("Authenticated successfully.");
                break;
        }
    }

    private void handleServerMessage(String serverMessage) throws IOException {
        if (serverMessage.equals(Communication.PING)) {
            sendMessageToServer(Communication.PONG);
        } else if (Communication.AUTH_MESSAGES.contains(serverMessage)) {
            handleAuthentication(serverMessage);
        } else {
            System.out.println(serverMessage);
        }
    }

    private void readServerMessages() throws IOException {
        String serverMessage;
        while ((serverMessage = serverReader.readLine()) != null) {
            handleServerMessage(serverMessage);
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
