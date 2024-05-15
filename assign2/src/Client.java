import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;

public class Client {

    private String username;
    private Socket socket;

    public Client(Socket socket) {
        this.socket = socket;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Socket getSocket() {
        return socket;
    }

    private void sendMessageToServer(String message, Socket serverSocket) throws IOException{
        OutputStream output = serverSocket.getOutputStream();
        PrintWriter writer = new PrintWriter(output, true);
        writer.println(message);
    }

    private void readServerMessages(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String time = reader.readLine();
        System.out.println(time);
    }

    private String readMessageFromServer(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        return reader.readLine();
    }

    private void authenticationCommunication(Socket socket) throws IOException {
        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            String serverMessage = readMessageFromServer(socket);

            if (serverMessage.equals(Communication.AUTH)) {
                System.out.print("Username: ");
                String username = consoleReader.readLine();
                sendMessageToServer(username, socket);
            } else if (serverMessage.equals(Communication.PASS)) {
                System.out.print("Password: ");
                String password = consoleReader.readLine();
                sendMessageToServer(password, socket);
            } else if (serverMessage.equals(Communication.AUTH_FAIL)) {
                System.out.println("Authentication failed. Disconnecting...");
                socket.close();
                break;
            } else if (serverMessage.equals(Communication.AUTH_SUCCESS)) {
                System.out.println("Authenticated successfully.");
                break;
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) return;

        String hostname = args[0];
        int port = Integer.parseInt(args[1]);

        try (Socket socket = new Socket(hostname, port)) {

            Client client = new Client(socket);

            client.authenticationCommunication(socket);

            while (true) {
                client.readServerMessages(socket);
            }

        } catch (UnknownHostException ex) {
            System.out.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }
}
