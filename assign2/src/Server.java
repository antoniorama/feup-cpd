import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Server {

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // General Info
    private final int MAX_NUMBER_GAMES = 5;
    private final int PLAYERS_PER_GAME = 3;

    // Client Queue
    private final List<Client> clientQueue;
    private final Lock clientQueue_lock = new ReentrantLock();

    // Game List
    private final List<Game> gameList;
    private final Lock gameList_lock = new ReentrantLock();

    // Database
    private final UserDatabase userDatabase;
    private final Lock userDatabase_lock = new ReentrantLock();

    public Server() throws IOException{
        this.clientQueue = new ArrayList<Client>();
        this.gameList = new ArrayList<Game>();
        this.userDatabase = new UserDatabase();
        executor = Executors.newFixedThreadPool(MAX_NUMBER_GAMES);
    }

    private void writeToClient(Socket clientSocket, String message) throws IOException{
        OutputStream output = clientSocket.getOutputStream();
        PrintWriter writer = new PrintWriter(output, true);
        writer.println(message);
    }

    private String readFromClient(Socket clientSocket) throws IOException {
        InputStream input = clientSocket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        return reader.readLine();
    }

    private void handleClient(Socket socket) throws IOException {
        Client client = new Client(socket);
        if (authenticateClient(client)) {
            writeToClient(client.getSocket(), Communication.AUTH_SUCCESS);
            addClientToQueue(client);
            checkForNewGame();
        } else {
            writeToClient(socket, Communication.AUTH_FAIL);
            socket.close();
        }
    }

    private boolean authenticateClient(Client client) throws IOException{
        writeToClient(client.getSocket(), Communication.AUTH);
        String username = readFromClient(client.getSocket());
        client.setUsername(username);

        writeToClient(client.getSocket(), Communication.PASS);
        String password = readFromClient(client.getSocket());

        userDatabase_lock.lock();
        boolean authSuccess = userDatabase.authenticate(username, password) == true;
        userDatabase_lock.unlock();

        return authSuccess;
    }

    // Adds a Client to the clientQueue
    private void addClientToQueue(Client client) {
        clientQueue_lock.lock();
        clientQueue.add(client);
        String log = String.format("Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
        System.out.println(log);
        clientQueue_lock.unlock();
    }

    // Checks if a new Game should start
    private void checkForNewGame() {
        clientQueue_lock.lock();
        if (clientQueue.size() == PLAYERS_PER_GAME) {
            startNewGame(clientQueue);
        }
        clientQueue_lock.unlock();
    }

    // Starts a new game with players (Clients) in playerList
    private void startNewGame(List<Client> playerList) {
        Game game = new Game(playerList);
        gameList_lock.lock();
        gameList.add(game);
        String log = String.format("[Game %d] Started Game", gameList.size());
        System.out.println(log);
        gameList_lock.unlock();
        clientQueue.clear();
    }

    // private void removePlayerFromGame(Socket socket) {
    //     for (Game game : ongoingGames) {
    //         if (game.getPlayerSockets().contains(socket)) {
    //             System.out.println("A player disconnected.");
    //             game.removePlayer(socket);
    //         }
    //     }
    // }
    
    // monitor the socket's input stream. 
    // When the socket is disconnected, it will catch an IOException.
    // private void monitorSocket(Socket socket) {
    //     executor.execute(() -> {
    //         try {
    //             InputStream input = socket.getInputStream();
    //             while (input.read() != -1) {
    //                 // Keep reading to detect socket disconnection
    //             }
    //         } catch (IOException e) {
    //             // Socket is disconnected
    //             System.out.println("A player disconnected 2.");
    //             removePlayerFromGame(socket);
    //         }
    //     });
    // }

    // private void pingClients() {
    //     scheduler.scheduleAtFixedRate(() -> {
    //         long currentTime = System.currentTimeMillis();
    //         for (Socket socket : clientLastPingTime.keySet()) {
    //             if (currentTime - clientLastPingTime.get(socket) > 5000) { // 5 seconds timeout
    //                 removePlayerFromGame(socket);
    //             } else {
    //                 writeToClient(socket, "PING");
    //             }
    //         }
    //     }, 0, 5, TimeUnit.SECONDS); // Ping every 5 seconds
    // }

    public static void main(String[] args) {
        if (args.length < 1) return;

        int port = Integer.parseInt(args[0]);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("Server is listening on port " + port);

            Server server = new Server();
            // server.pingClients();

            while (true) {
                Socket socket = serverSocket.accept();
                server.executor.execute(() -> {
                    try {
                        server.handleClient(socket);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

        } catch (IOException ex) {
            System.out.println("Server exception: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}