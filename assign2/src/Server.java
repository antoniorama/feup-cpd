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
import java.util.concurrent.locks.ReentrantLock;

public class Server {
    private final ExecutorService gameThreadPool;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // General Info
    private final int MAX_NUMBER_GAMES = 5;
    private final int PLAYERS_PER_GAME = 2;
    private final Set<String> loggedInUsers;
    private final ReentrantLock loggedInUsers_lock = new ReentrantLock();

    // Client Queue
    private final List<Client> clientQueue;
    private final ReentrantLock clientQueue_lock = new ReentrantLock();

    // Game ID
    private int gameId;
    private final ReentrantLock gameId_lock = new ReentrantLock();

    // Database
    private final UserDatabase userDatabase;
    private final ReentrantLock userDatabase_lock = new ReentrantLock();

    // Game Mode : 0 -> Simple , 1 -> Ranked
    private final int gameMode;
    private static final int SIMPLE = 0;
    private static final int RANKED = 1;

    // - Ranked Mode -
    // Maximum difference between player's Ranks
    private int MATCHMAKING_MAX_DIFF = 100;
    private int MATCHMAKING_RELAX = 100; 

    // {username : position}
    // Stores the client's queue position when he disconnects
    // This shares the lock with the userDatabase
    private final Map<String, Integer> reconnectPosition;

    public Server(int gameMode) throws IOException{
        this.clientQueue = new ArrayList<>();
        this.userDatabase = new UserDatabase();
        this.gameMode = gameMode;
        this.loggedInUsers = new HashSet<>();
        this.gameThreadPool = Executors.newVirtualThreadPerTaskExecutor();
        this.gameId = 1;

        File directory = new File("src/database/tokens/");
        if (directory.exists()) {

            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.getName().equals(".empty")) {
                        boolean success = file.delete();
                        if (!success) {
                            throw new IOException("Failed to delete token file");
                        }
                    }
                }
            }
        }

        // Schedulers
        schedulePing();
        scheduleNotifyQueuePos();
        if (this.gameMode == RANKED) {
            scheduleMatchmakingRelax();
        }

        this.reconnectPosition = new HashMap<>();
    }

    public static void writeToClient(Socket clientSocket, String message) throws IOException{
        OutputStream output = clientSocket.getOutputStream();
        PrintWriter writer = new PrintWriter(output, true);
        writer.println(message);
    }

    public static String readFromClient(Socket clientSocket) throws IOException {
        InputStream input = clientSocket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        return reader.readLine();
    }

    public static void serverLog(String log) {
        System.out.println(log);
    }

    private void handleClient(Socket socket) throws IOException {
        Client client = new Client(socket);
        String clientAction = questionClient(client);

        if (clientAction == null || clientAction.isEmpty()) {
            return;
        }

        String command = clientAction.split(" ")[0];

        switch (command) {
            case Communication.CLIENT_AUTH:
                System.out.println("[AUTH] A Client is authenticating");
                handleClientAuthentication(client);
                break;

            case Communication.CLIENT_RECONNECT:
                System.out.println("[RECONNECT] A Client is reconnecting with token");
                handleClientReconnection(client);
                break;

            case Communication.CLIENT_REGISTER:
                System.out.println("[AUTH] A Client is creating a new account");
                handleClientRegistration(client);
                break;
        
            default:
                break;
        }
    }

    // Questions client what he wants to do and returns desired mode
    // 1. Log In
    // 2. Reconnect with Token
    // 3. Register
    private String questionClient(Client client) throws IOException{
        writeToClient(client.getSocket(), Communication.WELCOME);
        return readFromClient(client.getSocket());
    }

    private void userLoggedIn(String username) {
        loggedInUsers_lock.lock();
        try { loggedInUsers.add(username); }
        finally { loggedInUsers_lock.unlock(); }
    }

    private void userLoggedOut(String username) {
        loggedInUsers_lock.lock();
        try { loggedInUsers.remove(username); }
        finally { loggedInUsers_lock.unlock(); }
    }

    private boolean isUserLoggedIn(String username) {
        loggedInUsers_lock.lock();
        try { 
            boolean result = loggedInUsers.contains(username); 
            return result;
        } finally {
            loggedInUsers_lock.unlock();
        }
    }

    private void handleClientAuthentication(Client client) throws IOException{
        if (authenticateClient(client)) {
            System.out.println("[AUTH] " + client.getUsername() + " authenticated successfully");
            writeToClient(client.getSocket(), Communication.AUTH_SUCCESS);
            assignToken(client);
            userLoggedIn(client.getUsername());
            addClientToQueue(client);
        } else {
            System.out.println("[AUTH] " + (client.getUsername() != null ? client.getUsername() : "Client") + " failed authentication");
            writeToClient(client.getSocket(), Communication.AUTH_FAIL);
            client.getSocket().close();
        }
    }

    private boolean authenticateClient(Client client) throws IOException {
        writeToClient(client.getSocket(), Communication.USERNAME);
        String username = readFromClient(client.getSocket());
        client.setUsername(username);
    
        writeToClient(client.getSocket(), Communication.PASSWORD);
        String password = readFromClient(client.getSocket());
    
        boolean authSuccess = false;
        int userRank = -1;
    
        userDatabase_lock.lock();
        try {
            if (isUserLoggedIn(username)) {
                writeToClient(client.getSocket(), Communication.AUTH_ALREADY_LOGGED_IN);
                System.out.println("[AUTH] " + username + " is already logged in");
                return false;
            }
    
            authSuccess = userDatabase.authenticate(username, password);
            if (authSuccess) {
                userRank = userDatabase.getUserRank(username);
                client.setRank(userRank);
                loggedInUsers.add(username);
            }
        } finally {
            userDatabase_lock.unlock();
        }
    
        if (!authSuccess) {
            writeToClient(client.getSocket(), Communication.AUTH_FAIL);
            System.out.println("[AUTH] " + username + " failed authentication");
            client.getSocket().close();
            return false;
        }
    
        return true;
    }

    private void handleClientRegistration(Client client) throws IOException {
        if (registerClient(client)) {
            System.out.println("[AUTH] " + client.getUsername() + " registered successfully");
            writeToClient(client.getSocket(), Communication.REGISTER_SUCCESS);
            handleClient(client.getSocket());

        } else {
            writeToClient(client.getSocket(), Communication.REGISTER_FAIL);
            System.out.println("[AUTH] " + (client.getUsername() != null ? client.getUsername() : "Client") + " failed registration");
            client.getSocket().close();
        }
    }

    private boolean registerClient(Client client) throws IOException {
        writeToClient(client.getSocket(), Communication.USERNAME);
        String username = readFromClient(client.getSocket());
        client.setUsername(username);

        writeToClient(client.getSocket(), Communication.PASSWORD);
        String password = readFromClient(client.getSocket());

        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            return false;
        }

        userDatabase_lock.lock();
        try {
            userDatabase.createUser(username, password);
            String log = String.format("[AUTH] New account created -> %s:%s", username, password);
            System.out.println(log);

        } catch (IOException e) {
            handleRegistrationError(client, e);
            return false;
        } finally {
            userDatabase_lock.unlock();
        }

        return true;
    }

    private void handleRegistrationError(Client client, Exception e) {
        try {
            writeToClient(client.getSocket(), Communication.REGISTER_FAIL);
        } catch (IOException e2) {
            System.out.println("[AUTH] Error communicating with Client: " + e.getMessage());
        }
        System.out.println("[AUTH] Client failed registration: " + e.getMessage());
    }

    // Adds a Client to the clientQueue
    private void addClientToQueue(Client client) throws IOException{
        clientQueue_lock.lock();
        try {
            clientQueue.add(client);
            notifyClientPosition(client, clientQueue.size());
            String log = String.format("[QUEUE] Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
            System.out.println(log);
            checkForNewGame();
        } finally {
            clientQueue_lock.unlock();
        }
    }

    // TODO -> refactor this to the previous function
    // Adds a Client to the clientQueue with specific pos
    private void addClientToQueuePos(Client client, int queuePos) throws IOException{
        clientQueue_lock.lock();
        try {
            clientQueue.add(queuePos - 1, client);
            notifyClientPosition(client, queuePos);
            String log = String.format("[QUEUE] Client %s was added to the Queue (%d/%d)", client.getUsername(), clientQueue.size(), PLAYERS_PER_GAME);
            System.out.println(log);
        } finally {
            clientQueue_lock.unlock();
        }
    }

    // Checks if a new Game should start
    private void checkForNewGame() throws IOException {
        List<Client> playerList = null;
        boolean startGame = false;
    
        clientQueue_lock.lock();
        try {
            if (clientQueue.size() >= PLAYERS_PER_GAME) {
                switch (gameMode) {
                    case SIMPLE:
                        playerList = new ArrayList<>(clientQueue);
                        clientQueue.clear();
                        startGame = true;
                        break;
                    case RANKED:
                        playerList = getPlayerListRanked();
                        if (playerList != null) {
                            removeClientsFromQueue(playerList);
                            MATCHMAKING_MAX_DIFF = 100;
                            startGame = true;
                        }
                        break;
                }
            }
        } finally {
            clientQueue_lock.unlock();
        }
    
        if (startGame && playerList != null) {
            startNewGame(playerList);
        }
    }

    // Removes clientsToRemove from Queue
    private void removeClientsFromQueue(List<Client> clientsToRemove) {
        clientQueue_lock.lock();
        try {
            for (Client client : clientsToRemove) {
                clientQueue.remove(client);
            }
        } finally {
            clientQueue_lock.unlock();
        }
    }

    // Function that returns the list of players to start a ranked game with close rank
    private List<Client> getPlayerListRanked() {
        List<Client> playerList = new ArrayList<Client>();

        clientQueue_lock.lock();
        
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

        clientQueue_lock.unlock();

        return null;
    }

    // Starts a new game with players (Clients) in playerList
    private void startNewGame(List<Client> playerList) throws IOException {
        gameId_lock.lock();
        userDatabase_lock.lock();
        try {
            Game game = new Game(gameId++, new ArrayList<>(playerList), userDatabase, userDatabase_lock, this);

            gameThreadPool.execute(() -> {
                try {
                    game.startGame();
                } catch (IOException e) {
                    serverLog(e.getMessage());
                }
            });
            String log = String.format("[Game %d] Started Game", game.getId());
            System.out.println(log);
        } finally {
            userDatabase_lock.unlock();
            gameId_lock.unlock();
        }
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
                    userLoggedOut(client.getUsername());
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
        // - Time Intervals (in seconds) -
        // Interval to send PING to all clients
        int PING_INTERVAL = 3;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                pingAllClients();
            } catch (IOException e) {
                System.out.println("Failed to ping clients: " + e.getMessage());
            }
        }, 0, PING_INTERVAL, TimeUnit.SECONDS);
    }

    private void scheduleNotifyQueuePos() throws IOException {
        // Interval to notify clients of their Queue position
        int NOTIFY_QUEUE_POS_INTERVAL = 10;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                notifyAllClientsPositions();
            } catch (IOException e) {
                System.out.println("[ERROR] Failed to notify clients positions: " + e.getMessage());
            }
        }, NOTIFY_QUEUE_POS_INTERVAL, NOTIFY_QUEUE_POS_INTERVAL, TimeUnit.SECONDS);
    }

    // Sends a message to the Client regarding his Queue position
    private void notifyClientPosition(Client client, int position) throws IOException {
        String message = "Your queue position: " + position;
        writeToClient(client.getSocket(), message);
    }

    // Notifies all clients of their Queue position
    private void notifyAllClientsPositions() throws IOException {
        clientQueue_lock.lock();
        try {
            for (int i = 0; i < clientQueue.size(); i++) {
                notifyClientPosition(clientQueue.get(i), i + 1);
            }
        } finally {
            clientQueue_lock.unlock();
        }
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

    // Amount of Rank to relax (add to MATCHMAKING_MAX_DIFF)
    private void relaxMatchmaking() {
        MATCHMAKING_MAX_DIFF += MATCHMAKING_RELAX;
        System.out.println("[MATCHMAKING] Increased Max Difference to " + MATCHMAKING_MAX_DIFF);
    }

    private void scheduleMatchmakingRelax() throws IOException {
        // Interval to relax Matchmaking
        int RELAX_MATCHMAKING_INTERVAL = 30;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                relaxMatchmaking();
                checkForNewGame();
            } catch (IOException e) {
                serverLog(e.getMessage());
            }
        }, RELAX_MATCHMAKING_INTERVAL, RELAX_MATCHMAKING_INTERVAL, TimeUnit.SECONDS);
    }

    // Assigns a token to a client
    private void assignToken(Client client) throws IOException {
        userDatabase_lock.lock();
        try {
            String sessionToken = userDatabase.assignSessionToken(client.getUsername());
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

        if (providedToken == null || providedToken.isEmpty()) {
            return false;
        }

        userDatabase_lock.lock();

        try {
            String clientUsername = userDatabase.getUsernameFromToken(providedToken);
            if (clientUsername != null) { // success

                if (isUserLoggedIn(clientUsername)) {
                    writeToClient(client.getSocket(), Communication.RECONNECT_ALREADY_LOGGED_IN);
                    return false;
                }
                client.setUsername(clientUsername);
                userLoggedIn(clientUsername);
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

    public void reQueuePlayers(List<Client> clients) throws IOException {
        for (Client client : clients) {
            requeueOrExit(client);
        }
    }

    // Asks a client if he wasnt to requeue or exit
    public void requeueOrExit(Client client) {
        try {
            writeToClient(client.getSocket(), Communication.REQUEUE_OR_QUIT);
            String clientAnswer = readFromClient(client.getSocket());

            switch (clientAnswer) {
                case Communication.REQUEUE:
                    addClientToQueue(client);
                    break;
    
                case Communication.QUIT:
                    client.getSocket().close();
                    break;
            
                default:
                    break;
            }
        } catch (IOException e) {
            serverLog("Failed!");
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
                Thread.startVirtualThread(() -> {
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