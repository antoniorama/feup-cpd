# Trivia Quiz Game Server

## Running the Project

Ensure you have **Java JDK 21 or higher** installed on your system to compile and run this project.

### Compilation

First, compile the source code. Navigate to the **assign2 directory** of the project where the src folder and the lib directory are located. Use the following command to compile all Java files, storing the compiled classes in the out directory:

```
javac -cp "lib/*" -d out src/*.java src/game_logic/*.java
```

This command includes all libraries located in the lib directory in the classpath and compiles all Java files in the src and src/game_logic directories.

### Running the Server

Start the server by specifying a port number on which the server will listen for incoming client connections. Ensure that the out directory is included in the classpath along with the libraries:

```
java -cp "lib/*:out" Server <port>
```

Replace port with the port number you wish to use (e.g., 12345).

### Connecting Clients

After the server is running, you can connect clients to it. Run the following command from a different terminal window for each client:


```
java -cp "lib/*:out" Client localhost <port>
```

Again, replace port with the same port number used to start the server.

## Game

Our game is a simple 1v1 trivia contest where each player answers true or false questions over multiple rounds. The player with the most correct answers at the end of the rounds wins the game.

## User Database

### Overview

We employ a simple JSON-based database to store user credentials securely. For enhanced security, all passwords are encrypted using bcrypt, ensuring sensitive information is well-protected.

### Default Credentials

For demonstration purposes, the database includes these default credentials:

- Username: **user1** | Password: **password1**
- Username: **user2** | Password: **password2**
- Username: **user3** | Password: **password3**

## Multi-threading Strategy

### Structures

We are utilizing Java 21's advanced concurrency features, including virtual thread pools, to efficiently manage multiple client connections and game interactions simultaneously.

- **Server Threads**: Each new client connection initiates a dedicated virtual thread. This approach ensures that each client's interactions with the server are handled concurrently, without blocking other operations, particularly beneficial for I/O-bound tasks such as network communication. Each new game also has his own thread.

- **Game Threads**: We allocate two separate virtual threads - one for each player.

### Thread Safety Mechanisms

To maintain data integrity and prevent race conditions in concurrent operations, we employ several thread-safe mechanisms:

- **Reentrant Locks**: We use ReentrantLock for critical sections that might be accessed concurrently. This type of lock is advantageous because it allows the thread holding the lock to lock it multiple times before unlocking, which is crucial for operations where nested method calls require the same lock.

- **Lock Management:** Each critical section or shared resource is protected using these locks. This ensures that only one thread can modify the state at any given time, thereby preventing inconsistencies and ensuring thread safety.

## Server-Client Communication

### Overview

Communication between the server and clients is facilitated through socket programming, ensuring real-time, bidirectional data exchange. Both the server and client utilize state machines to manage communication states effectively.

### Protocol and Macros

The specific protocol rules and message formats used during socket communication are defined in the **Communication** class. This class contains macros that standardize the message structure, making it easier to parse and handle different types of communication events consistently across the system.

## Reconnection with Token

We have implemented a session token system that enables clients to reconnect and retain their queue position. Upon login, a session token is generated and stored as token-"client_username" in the /database/tokens directory. This approach simulates a real client storage system. To reconnect, clients must provide the filename containing their session token.