import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    // Heartbeat Ping
    private final int PING_INTERVAL = 3;

    // Mode
    private final int gameMode;

    // Ranked Mode
    private final int MATCHMAKING_MAX_DIFF = 100;


    public Server(int gameMode) throws IOException{
        this.clientQueue = new ArrayList<Client>();
        this.gameList = new ArrayList<Game>();
        this.userDatabase = new UserDatabase();
        this.gameMode = gameMode;
        executor = Executors.newFixedThreadPool(MAX_NUMBER_GAMES);
        schedulePing();
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
        System.out.println("[AUTH] A Client is authenticating");
        if (authenticateClient(client)) {
            System.out.println("[AUTH] " + client.getUsername() + " authenticated successfully");
            writeToClient(client.getSocket(), Communication.AUTH_SUCCESS);
            addClientToQueue(client);
            checkForNewGame();
        } else {
            System.out.println("[AUTH] " + client.getUsername() + " failed authentication");
            writeToClient(socket, Communication.AUTH_FAIL);
            socket.close();
        }

        pingAllClients();
    }

    private boolean authenticateClient(Client client) throws IOException{
        writeToClient(client.getSocket(), Communication.AUTH);
        String username = readFromClient(client.getSocket());
        client.setUsername(username);

        writeToClient(client.getSocket(), Communication.PASS);
        String password = readFromClient(client.getSocket());

        userDatabase_lock.lock();
        boolean authSuccess = userDatabase.authenticate(username, password);

        // get the rank of current user
        if (authSuccess){
            int userRank = userDatabase.getUserRank(username);
            client.setRank(userRank);
        }
        userDatabase_lock.unlock();

        return authSuccess;
    }

    // Adds a Client to the clientQueue
    private void addClientToQueue(Client client) throws IOException{
        clientQueue_lock.lock();
        clientQueue.add(client);
        String log = String.format("[QUEUE] Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
        System.out.println(log);
        clientQueue_lock.unlock();
    }

    // Checks if a new Game should start
    private void checkForNewGame() {
        clientQueue_lock.lock();
        if (clientQueue.size() >= PLAYERS_PER_GAME) {
            switch (gameMode) {
                // Unranked
                case 0:
                    startNewGame(clientQueue);     
                    clientQueue.clear();
                // Ranked
                case 1:
                    List<Client> playerList = getPlayerListRanked();
                    if (playerList != null) {
                        removeClientsFromQueue(playerList);
                        startNewGame(playerList);
                    }
            }
        }
        clientQueue_lock.unlock();
    }

    // Removes clientsToRemove from Queue
    private void removeClientsFromQueue(List<Client> clientsToRemove) {
        for (Client client : clientsToRemove) {
            clientQueue.remove(client);
        }
    }

    // Funtion that returns the list of players to start a ranked game with close rank
    private List<Client> getPlayerListRanked() {
        List<Client> playerList = new ArrayList<Client>();
        
        for (int i1 = 0; i1 < clientQueue.size(); i1++) {
            playerList.clear();
            int rankFirst = clientQueue.get(i1).getRank();
            playerList.add(clientQueue.get(i1));
            for (int i2 = i1 + 1; i2 < clientQueue.size(); i2++) {
                int rankSecond = clientQueue.get(i2).getRank();

                if (Math.abs(rankFirst - rankSecond) <= MATCHMAKING_MAX_DIFF) {
                    playerList.add(clientQueue.get(i2));
                }

                if (playerList.size() == PLAYERS_PER_GAME) {
                    return playerList;
                }
            }
        }

        return null;
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

    // Pings client
    private boolean pingClient(Client client) throws IOException {
        writeToClient(client.getSocket(), Communication.PING);

        ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = pingExecutor.submit(() -> {
            try {
                String response = readFromClient(client.getSocket());
                if (response.equals(Communication.PONG)) {
                    client.setLastResponseTime();
                    return true;
                }
            } catch (IOException e) {
                // Ignore, we'll handle the client removal below
            }
            return false;
        });

        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return false;
        } finally {
            pingExecutor.shutdown();
        }
    }

    // Ping all clients in Queue
    private void pingAllClients() throws IOException {
        clientQueue_lock.lock();
        try {
            Iterator<Client> iterator = clientQueue.iterator();
            while (iterator.hasNext()) {
                Client client = iterator.next();
                if (!pingClient(client)) {
                    iterator.remove();
                    String log = String.format("[QUEUE] Client %s disconnected (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
                    System.out.println(log);
                    client.getSocket().close();
                }
            }
        } finally {
            clientQueue_lock.unlock();
        }
    }

    private void schedulePing() throws IOException {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                pingAllClients();
            } catch (IOException e) {
                System.out.println("Failed to ping clients: " + e.getMessage());
            }
        }, 0, PING_INTERVAL, TimeUnit.SECONDS);
    }

    public static void main(String[] args) {
        if (args.length < 1) return;

        // Choose Mode, Simple or Ranked
        int gameMode;
        Scanner scanner = new Scanner(System.in);
        System.out.println("Choose Type of Mode Simple(0) or Ranked(1):");
        int modeChoice = scanner.nextInt();
        if (modeChoice == 0) {
            System.out.println("Simple Mode Selected");
            gameMode = 0;
        } else if (modeChoice == 1) {
            System.out.println("Ranked Mode Selected");
            gameMode = 1;
        } else {
            System.out.println("Invalid Mode Selected");
            return;
        }

        int port = Integer.parseInt(args[0]);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("Server is listening on port " + port);

            Server server = new Server(gameMode);
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