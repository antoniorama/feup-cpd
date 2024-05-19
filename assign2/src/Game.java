import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import game_logic.TriviaResponse;
import game_logic.TriviaResult;

public class Game {
    private final int gameId;
    private final List<Client> playerList;
    private final Lock playerList_lock = new ReentrantLock();
    private TriviaResponse triviaResponse;
    private volatile boolean isGameRunning;
    private final int ROUNDS = 4;
    private final ExecutorService playerThreadPool;
    private final UserDatabase userDatabase;
    private final ReentrantLock userDatabase_lock;
    private final Server server;

    // Amount of rank a player wins (or looses) at the end of a game
    private final int RANK_INCREMENT = 50;

    public Game(int gameId, List<Client> playerList, UserDatabase userDatabase, ReentrantLock userDatabase_lock, Server server) {
        this.gameId = gameId;
        this.playerList = playerList;
        this.triviaResponse = new TriviaResponse();
        this.isGameRunning = false;
        this.playerThreadPool = Executors.newVirtualThreadPerTaskExecutor();
        this.userDatabase = userDatabase;
        this.userDatabase_lock = userDatabase_lock;
        this.server = server;
    }

    public int getId() {
        return this.gameId;
    }

    // Loads trivia questions from database
    public void loadQuestions(String dataPath) {
        File jsonFile = new File(dataPath);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            triviaResponse = objectMapper.readValue(jsonFile, new TypeReference<>() {
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Starts the game
    public void startGame() throws IOException {
        loadQuestions("src/database/questions.json");
        isGameRunning = true;
        broadcastMessage("--------------------- Welcome to the Trivia! ---------------------");
        broadcastMessage("Questions will be given shortly. Please answer with True or False.");
        try {
            Thread.sleep(1000);
            broadcastMessage("                               5                               ");
            Thread.sleep(1000);
            broadcastMessage("                               4                               ");
            Thread.sleep(1000);
            broadcastMessage("                               3                               ");
            Thread.sleep(1000);
            broadcastMessage("                               2                               ");
            Thread.sleep(1000);
            broadcastMessage("                               1                               ");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        loop();
    }

    // Main game loop -> asks questions until rounds are over and then ends game
    private void loop() throws IOException{
        for (int round = 0; round < ROUNDS && isGameRunning; round++) {
            String log = String.format("[Game %d] Started Round %d", gameId, round + 1);
            Server.serverLog(log);
            askQuestionToAllPlayers();
        }
        endGame();
    }

    // Ends the game
    private void endGame() throws IOException {
        isGameRunning = false;
        Client winner = determineWinner();
        if (winner != null) {
            broadcastMessage("Game Over! The winner is: " + winner.getUsername() + " with a score of " + winner.getScore());
            updatePlayersRanks(winner);
        } else {
            broadcastMessage("Game Over! No winner.");
        }

        playerList_lock.lock();
        try {
            server.reQueuePlayers(playerList);
        } finally {
            playerList_lock.unlock();
        }

        playerThreadPool.shutdown();
    }

    // Determine who is the winner of the game
    private Client determineWinner() {
        Client winner = null;
        int highestScore = -1;
        playerList_lock.lock();
        try {
            for (Client player : playerList) {
                if (player.getScore() > highestScore) {
                    highestScore = player.getScore();
                    winner = player;
                }
            }
        } finally {
            playerList_lock.unlock();
        }
        return winner;
    }

    // Sends a message for both players
    private void broadcastMessage(String message) {
        playerList_lock.lock();
        try {
            playerList.forEach(player -> {
                try {
                    Server.writeToClient(player.getSocket(), message);
                } catch (IOException e) {
                    System.out.println("Error communicating with Client: " + e.getMessage());
                }
            });
        } finally {
            playerList_lock.unlock();
        }
    }

    // Asks a question to both players
    private void askQuestionToAllPlayers() {
        TriviaResult question = triviaResponse.getRandomQuestion();
        broadcastMessage("Round Question: " + question.getQuestion());

        CountDownLatch latch = new CountDownLatch(2);
        
        playerList_lock.lock();
        try {
            playerList.forEach(player -> 
                playerThreadPool.execute(() -> {
                    try {
                        handlePlayerAnswer(player, question.getCorrectAnswer(), latch);
                    } catch (Exception e) {
                        Server.serverLog("Server exception: " + e.getMessage());
                    }
                })
            );
        } finally {
            playerList_lock.unlock();
        }

        try {
            latch.await();  // Wait for both players to answer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Handles player's question answer
    private void handlePlayerAnswer(Client player, String correctAnswer, CountDownLatch latch) {
        try {
            Server.writeToClient(player.getSocket(), Communication.PROVIDE_ANSWER);
            String answer = Server.readFromClient(player.getSocket());
            if (answer == null) {
                Server.serverLog("Player " + player.getUsername() + " disconnected.");
                userDatabase_lock.lock();
                try {
                    userDatabase.userLoggedOut(player.getUsername());
                } finally { userDatabase_lock.unlock(); }

            }
            else if (answer.equalsIgnoreCase(correctAnswer)) {
                player.incrementScore();
                Server.writeToClient(player.getSocket(), "Correct! Your score: " + player.getScore() + '\n');
            } else {
                Server.writeToClient(player.getSocket(), "Incorrect! Correct answer was: " + correctAnswer + '\n');
            }
        } catch (IOException e) {
            System.out.println("Error communicating with Client: " + e.getMessage());
        } finally {
            latch.countDown();
        }
    }

    // Updates the player's ranks 
    private void updatePlayersRanks(Client winner) throws IOException {
        userDatabase_lock.lock();
        try {
            userDatabase.incrementRank(winner.getUsername(), RANK_INCREMENT);
            userDatabase.incrementRank(opponent(winner).getUsername(), -RANK_INCREMENT);
        } finally {
            userDatabase_lock.unlock();
        }
    }

    // Given 'player' returns it's oponent
    private Client opponent(Client player) {
        playerList_lock.lock();
        try {
            if (playerList.get(0).equals(player)) return playerList.get(1);
            return playerList.get(0);
        } finally {
            playerList_lock.unlock();
        }
    }
}
