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

    // - Time Intervals (in seconds) -
    // Interval to send PING to all clients
    private final int PING_INTERVAL = 3;
    // Interval to notify clients of their Queue position
    private final int NOTIFY_QUEUE_POS_INTERVAL = 10;
    // Interval to relax Matchmaking
    private final int RELAX_MATCHMAKING_INTERVAL = 30;

    // Game Mode : 0 -> Simple , 1 -> Ranked
    private final int gameMode;
    private static final int SIMPLE = 0;
    private static final int RANKED = 1;

    // - Ranked Mode -
    // Maximum difference beetween player's Ranks
    private int MATCHMAKING_MAX_DIFF = 100;
    // Ammount of Rank to relax (add to MATCHMAKING_MAX_DIFF)
    private int MATCHMAKING_RELAX = 100;

    // {username : position}
    // Stores the client's queue position when he disconnects
    // This shares the lock with the userDatabase
    private Map<String, Integer> reconnectPosition;

    public Server(int gameMode) throws IOException{
        this.clientQueue = new ArrayList<Client>();
        this.gameList = new ArrayList<Game>();
        this.userDatabase = new UserDatabase();
        this.gameMode = gameMode;
        executor = Executors.newFixedThreadPool(MAX_NUMBER_GAMES);

        // Schedulers
        schedulePing();
        scheduleNotifyQueuePos();
        if (this.gameMode == RANKED) {
            scheduleMatchmakingRelax();
        }

        this.reconnectPosition = new HashMap<String,Integer>();
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
        String clientAction = questionClient(client);

        switch (clientAction) {
            case Communication.CLIENT_AUTH:
                System.out.println("[AUTH] A Client is authenticating");
                handleClientAuthentication(client);
                break;

            case Communication.CLIENT_RECONNECT:
                System.out.println("[RECONNECT] A Client is reconnecting with token");
                handleClientReconnection(client);
                break;
        
            default:
                break;
        }
    }

    // Questions client what he wants to do and returns desired mode
    // 1. Log In
    // 2. Reconnect with Token
    private String questionClient(Client client) throws IOException{
        writeToClient(client.getSocket(), Communication.WELCOME);
        String answer = readFromClient(client.getSocket());
        return answer;
    }

    private void handleClientAuthentication(Client client) throws IOException{
        if (authenticateClient(client)) {
            System.out.println("[AUTH] " + client.getUsername() + " authenticated successfully");
            writeToClient(client.getSocket(), Communication.AUTH_SUCCESS);
            assignToken(client);
            addClientToQueue(client);
            checkForNewGame();
        } else {
            System.out.println("[AUTH] " + client.getUsername() + " failed authentication");
            writeToClient(client.getSocket(), Communication.AUTH_FAIL);
            client.getSocket().close();
        }
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
        notifyClientPosition(client, clientQueue.size());
        String log = String.format("[QUEUE] Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
        System.out.println(log);
        clientQueue_lock.unlock();
    }

    // TODO -> refactor this to the previous function
    // Adds a Client to the clientQueue with specific pos
    private void addClientToQueuePos(Client client, int queuePos) throws IOException{
        clientQueue_lock.lock();
        clientQueue.add(queuePos - 1, client);
        notifyClientPosition(client, queuePos);
        String log = String.format("[QUEUE] Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
        System.out.println(log);
        clientQueue_lock.unlock();
    }

    // Checks if a new Game should start
    private void checkForNewGame() {
        clientQueue_lock.lock();
        if (clientQueue.size() >= PLAYERS_PER_GAME) {
            switch (gameMode) {
                case SIMPLE:
                    startNewGame(clientQueue);     
                    clientQueue.clear();
                case RANKED:
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
                    storeQueuePosition(client);
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

    private void scheduleNotifyQueuePos() throws IOException {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                notifyAllClientsPositions();;
            } catch (IOException e) {
                System.out.println("[ERROR] Failed to notify clients positions: " + e.getMessage());
            }
        }, 0, NOTIFY_QUEUE_POS_INTERVAL, TimeUnit.SECONDS);
    }

    // Sends a message to the Client regarding his Queue position
    private void notifyClientPosition(Client client, int position) throws IOException {
        String message = "Your queue position: " + String.valueOf(position);
        writeToClient(client.getSocket(), message);
    }

    // Notifies all clients of their Queue position
    private void notifyAllClientsPositions() throws IOException {
        clientQueue_lock.lock();
        for (int i = 0; i < clientQueue.size(); i++) {
            notifyClientPosition(clientQueue.get(i), i + 1);
        }
        clientQueue_lock.unlock();
    }

    // Choose Mode, Simple or Ranked
    private static int chooseGameMode() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("Choose Type of Mode Simple(0) or Ranked(1): ");
                if (scanner.hasNextInt()) {
                    int modeChoice = scanner.nextInt();
                    if (modeChoice == SIMPLE) {
                        System.out.println("Simple Mode Selected");
                        return SIMPLE;
                    } else if (modeChoice == RANKED) {
                        System.out.println("Ranked Mode Selected");
                        return RANKED;
                    } else {
                        System.out.println("Invalid Mode Selected. Please enter 0 for Simple or 1 for Ranked.");
                    }
                } else {
                    System.out.println("Invalid input. Please enter a number (0 or 1).");
                    scanner.next(); // Consume the invalid input
                }
            }
        }
    }

    private void relaxMatchmaking() {
        MATCHMAKING_MAX_DIFF += MATCHMAKING_RELAX;
        System.out.println("[MATCHMAKING] Increased Max Difference to " + MATCHMAKING_MAX_DIFF);
    }

    private void scheduleMatchmakingRelax() throws IOException {
        scheduler.scheduleAtFixedRate(() -> {
            relaxMatchmaking();
            checkForNewGame();
        }, RELAX_MATCHMAKING_INTERVAL, RELAX_MATCHMAKING_INTERVAL, TimeUnit.SECONDS);
    }

    // Assigns a token to a client
    private void assignToken(Client client) throws IOException {
        userDatabase_lock.lock();
        try {
            userDatabase.assignSessionToken(client.getUsername());
            String sessionToken = userDatabase.getSessionToken(client.getUsername());
            writeToClient(client.getSocket(), Communication.TOKEN + " " + sessionToken);
        } finally {
            userDatabase_lock.unlock();
        }
    }

    // Handles Client reconnection with token
    private void handleClientReconnection(Client client) throws IOException {

        if (reconnectClient(client)) {
            this.userDatabase_lock.lock();
            int queuePos;
            try {
                queuePos = this.reconnectPosition.get(client.getUsername());
            } finally {
                this.userDatabase_lock.unlock();
            }
            String messageToClient = String.format("%s %d", Communication.RECONNECT_SUCCESS, queuePos);
            writeToClient(client.getSocket(), messageToClient);
            addClientToQueuePos(client, queuePos);
        } else {
            System.out.println("[RECONNECT] Cient reconnection failed");
            writeToClient(client.getSocket(), Communication.RECONNECT_FAIL);
            client.getSocket().close();
        }
    }

    // Checks if Client reconnection is valid
    private boolean reconnectClient(Client client) throws IOException {
        writeToClient(client.getSocket(), Communication.REQUEST_TOKEN);
        String providedToken = readFromClient(client.getSocket());

        userDatabase_lock.lock();

        try {
            String clientUsername = userDatabase.getUsernameFromToken(providedToken);
            if (clientUsername != null) { // success
                client.setUsername(clientUsername);
                System.out.println("[RECONENCT] " + clientUsername + " reconnected with token");
                return true;
            }
            return false;
        } finally {
            userDatabase_lock.unlock();
        }
    }

    // Stores the queue position of a Client
    // This is useful for reconnections
    private void storeQueuePosition(Client client) {
        clientQueue_lock.lock();
        try {
            String username = client.getUsername();
            int queuePos = getQueuePosition(client);
            reconnectPosition.put(username, queuePos);
        } finally {
             clientQueue_lock.unlock();
        }
    }

    // Gets a Client's queue position
    // Returns -1 if client is not in the queue
    private int getQueuePosition(Client client) {
        clientQueue_lock.lock();
        try {
            for (int pos = 0; pos < clientQueue.size(); pos++) {
                if (clientQueue.get(pos) == client) return pos + 1;
            }
            return -1;
        } finally {
            clientQueue_lock.unlock();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) return;

        // Choose Mode, Simple or Ranked
        int gameMode = chooseGameMode();
        int port = Integer.parseInt(args[0]);

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            Server server = new Server(gameMode);
            System.out.println("Server is listening on port " + port);

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