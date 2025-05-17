import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import arcade.GuessMyNumberGame;
import arcade.MastermindGame;

public class Server {

    private final int port = 8989;
    private static final Map<String, Object> gameSessions = new HashMap<>();
    private static final Map<String, String> flashMessages = new HashMap<>();

    private static final int SOCKET_TIMEOUT_MS = 100;

    public static void main(String[] args) {
        new Server();
    }

    public Server() {
        try (ServerSocket serverSocket = new ServerSocket(this.port)) {
            System.out.println("Listening on port: " + this.port);

            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    // SoTimeout mot slowloris
                    socket.setSoTimeout(SOCKET_TIMEOUT_MS);

                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                    System.out.println("\nNew connection established!");

                    // Läs startline (ex: GET /index.html HTTP/1.1)
                    String startLine = in.readLine();
                    if (startLine == null || startLine.isEmpty()) {
                        System.out.println("Empty request.");
                        continue;
                    }
                    System.out.println("Request: " + startLine);
                    String[] parts = startLine.split(" ");
                    if (parts.length < 2) {
                        sendResponse(out, 400, "text/plain", "Bad Request", null);
                        continue;
                    }
                    String method = parts[0]; // GET / POST
                    String path = parts[1];   // "/", "/guessmynumber", etc.

                    // Läs headers
                    String line;
                    int contentLength = 0;
                    String sessionId = null;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        System.out.println("Header: " + line);
                        if (line.startsWith("Content-Length: ")) {
                            contentLength = Integer.parseInt(line.substring(16).trim());
                        }
                        if (line.startsWith("Cookie: ")) {
                            // t.ex. "Cookie: sessionId=xxxx
                            String cookieVal = line.substring("Cookie: ".length()).trim();
                            if (cookieVal.startsWith("sessionId=")) {
                                sessionId = cookieVal.substring("sessionId=".length());
                            }
                        }
                    }

                    // Skapa session om saknas
                    if (sessionId == null || !gameSessions.containsKey(sessionId)) {
                        sessionId = UUID.randomUUID().toString();
                        initializeGameSession(path, sessionId);
                        System.out.println("New session created with ID: " + sessionId);
                    }

                    // Om POST, läs body
                    String postData = null;
                    if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                        char[] buf = new char[contentLength];
                        int readChars = in.read(buf, 0, contentLength);
                        postData = new String(buf, 0, readChars);
                        System.out.println("POST data: " + postData);
                    }

                    // Routing
                    if ("GET".equalsIgnoreCase(method)) {
                        handleGetRequest(path, sessionId, out);
                    } else if ("POST".equalsIgnoreCase(method)) {
                        handlePostRequest(path, sessionId, postData, out);
                    } else {
                        sendResponse(out, 405, "text/plain", "405 Method Not Allowed", sessionId);
                    }

                } catch (SocketTimeoutException ste) {
                    System.err.println("Slowloris timeout, closing socket.");
                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port " + this.port);
            System.exit(1);
        }
    }

    /**
     * Skapar ett spelobjekt beroende på path (om /guessmynumber eller /mastermind)
     */
    private void initializeGameSession(String path, String sessionId) {
        if ("/guessmynumber".equalsIgnoreCase(path)) {
            gameSessions.put(sessionId, new GuessMyNumberGame());
        } else if ("/mastermind".equalsIgnoreCase(path)) {
            gameSessions.put(sessionId, new MastermindGame());
        }
    }

    /**
     * Hantering av GET. Om path = "/", visa index.html
     * Om path = "/guessmynumber" eller "/mastermind", ladda guess.html + placeholders
     */
    private void handleGetRequest(String path, String sessionId, BufferedWriter out) throws IOException {
        System.out.println("Handling GET for path: " + path);
    
        // 1) Kolla om session finns i gameSessions
        Object game = (sessionId != null) ? gameSessions.get(sessionId) : null;
    
        // 2) OM session finns (d.v.s. user redan har startat ett spel), 
        //    redirecta till "rätt" spel-URL om path inte matchar 
        if (game instanceof MastermindGame) {
            // Om man inte redan är på /mastermind => redirect dit
            if (!"/mastermind".equalsIgnoreCase(path)) {
                sendRedirect(out, "/mastermind");
                return;
            }
            // Annars (path == "/mastermind") => hantera mastermind-sidan som vanligt
            handleMastermind(sessionId, (MastermindGame) game, out);
            return;
        }
        else if (game instanceof GuessMyNumberGame) {
            // Om man inte redan är på /guessmynumber => redirect dit
            if (!"/guessmynumber".equalsIgnoreCase(path)) {
                sendRedirect(out, "/guessmynumber");
                return;
            }
            // Annars => hantera guessmygame-sidan
            handleGuessMyNumber(sessionId, (GuessMyNumberGame) game, out);
            return;
        }
    
        // 3) Om ingen session, fortsätt vanlig logik
        //    ex: path = "/", /mastermind, /guessmynumber => initiera session eller index
        if ("/".equals(path)) {
            String indexHtml = loadHtmlTemplate("index.html");
            sendResponse(out, 200, "text/html", indexHtml, sessionId);
            return;
        }
        else if ("/mastermind".equalsIgnoreCase(path)) {
            // initiera session om man vill
            sessionId = resetSession(sessionId, new MastermindGame());
            MastermindGame m = (MastermindGame) gameSessions.get(sessionId);
            handleMastermind(sessionId, m, out);
            return;
        }
        else if ("/guessmynumber".equalsIgnoreCase(path)) {
            sessionId = resetSession(sessionId, new GuessMyNumberGame());
            GuessMyNumberGame g = (GuessMyNumberGame) gameSessions.get(sessionId);
            handleGuessMyNumber(sessionId, g, out);
            return;
        }
        else {
            // 404 / eller redirect => "/"
            sendRedirect(out, "/");
        }
    }
    /**
     * Hanterar POST. 
     * - Fel gissning => PRG via redirect 
     * - Rätt gissning => final "vinstsida" + rensar session
     */
    private void handlePostRequest(String path, String sessionId, String postData, BufferedWriter out) throws IOException {
        System.out.println("Handling POST for path: " + path);

        String guess = null;
        if (postData != null && postData.contains("guess=")) {
            guess = postData.split("guess=")[1].split("&")[0];
        }
        if (guess == null || guess.isEmpty()) {
            flashMessages.put(sessionId, "Missing guess parameter");
            sendRedirect(out, path);
            return;
        }
        System.out.println("User guess: " + guess);

        Object game = gameSessions.get(sessionId);
        String message;

        if (game instanceof GuessMyNumberGame) {
            GuessMyNumberGame g = (GuessMyNumberGame) game;
            if (!g.validate(guess)) {
                flashMessages.put(sessionId, "Invalid guess. Please enter a valid number.");
                sendRedirect(out, path);
                return;
            }
            if (g.isGuessCorrect(guess)) {
                // vinst
            

                // Rensa session
               flashMessages.put(sessionId, "win");
               sendRedirect(out, path);

                // Skicka vinstsida
         
                return; } else {
                // fel gissning PRG
                message = g.evaluateGuess(guess);
                flashMessages.put(sessionId, message);
                sendRedirect(out, path);
                return;
            }

        } else if (game instanceof MastermindGame) {
            MastermindGame m = (MastermindGame) game;
            if (!m.validate(guess)) {
                flashMessages.put(sessionId, "Invalid guess. Please enter a valid code.");
                sendRedirect(out, path);
                return;
            }
            if (m.isGuessCorrect(guess)) {
                flashMessages.put(sessionId, "win");
               sendRedirect(out, path);
                return;
            } else {

                //fel gissning PRG
                message = m.evaluateGuess(guess);
                flashMessages.put(sessionId, message);
                sendRedirect(out, path);
                return;
            }
        } else {
            // Okänt
            flashMessages.put(sessionId, "Unknown game.");
            sendRedirect(out, "/");
        }
    }

    // Ladda en HTML-fil från disk (index.html eller guess.html)
    private String loadHtmlTemplate(String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    // Skicka 302 redirect
    private void sendRedirect(BufferedWriter out, String location) throws IOException {
        out.write("HTTP/1.1 302 Found\r\n");
        out.write("Location: " + location + "\r\n");
        out.write("Content-Length: 0\r\n");
        out.write("Connection: close\r\n");
        out.write("\r\n");
        out.flush();
    }

    /**
     * Tar bort sessionen ur memory och skapar en ny.
     */
    private String resetSession(String sessionId, Object newGame) {
        gameSessions.remove(sessionId);
        String newSessionId = UUID.randomUUID().toString();
        gameSessions.put(newSessionId, newGame);
        return newSessionId;
    }

    // Skicka final vinnarsida
    private void sendFinalWinPage(BufferedWriter out, String message) throws IOException {
        out.write("HTTP/1.1 200 OK\r\n");
        out.write("Content-Type: text/html\r\n");
        // Ogiltig cookie => stäng session
        out.write("Set-Cookie: sessionId=deleted; Expires=Thu, 01 Jan 1970 00:00:00 GMT\r\n");

        String body = "<!DOCTYPE html>"
                    + "<html><head><title>Game finished</title></head>"
                    + "<body><h1>Congratulations!</h1>"
                    + "<p>" + message + "</p>"
                    + "</body></html>";

        out.write("Content-Length: " + body.length() + "\r\n");
        out.write("Connection: close\r\n");
        out.write("\r\n");
        out.write(body);
        out.flush();
    }

    // Skicka "vanlig" HTTP-svar
    private void sendResponse(BufferedWriter out, int statusCode, String contentType, String body, String sessionId)
            throws IOException {
        out.write("HTTP/1.1 " + statusCode + " OK\r\n");
        out.write("Content-Type: " + contentType + "\r\n");
        if (sessionId != null) {
            // Sätter cookie om sessionId != null
            out.write("Set-Cookie: sessionId=" + sessionId + "\r\n");
        }
        out.write("Content-Length: " + body.length() + "\r\n");
        out.write("Connection: close\r\n");
        out.write("\r\n");
        out.write(body);
        out.flush();
    }

    private void handleMastermind(String sessionId, MastermindGame game, BufferedWriter out) throws IOException {
        String feedback = flashMessages.get(sessionId);
        if (feedback == null) {
            feedback = game.welcomeMessage();
        }
        // Ladda guess.html
        String template = loadHtmlTemplate("guess.html");
        template = template.replace("{{title}}", "Mastermind");
        template = template.replace("{{gameTitle}}", "Mastermind");
        template = template.replace("{{message}}", feedback);
        template = template.replace("{{attempts}}", String.valueOf(game.getAttempts()-1));
        template = template.replace("{{action}}", "/mastermind");
       
        
        if(feedback == "win") {

            String message = game.gratulationMessage() 
            + "<br>You used " + game.getAttempts() + " guesses."
            + "<br><a href='/'>Start a new game?</a>";

            sendFinalWinPage(out, message);

            gameSessions.remove(sessionId);
            flashMessages.remove(sessionId);

            return;
        }
    
        sendResponse(out, 200, "text/html", template, sessionId);
    }
    
    private void handleGuessMyNumber(String sessionId, GuessMyNumberGame game, BufferedWriter out) throws IOException {
        String feedback = flashMessages.get(sessionId);
        if (feedback == null) {
            feedback = game.welcomeMessage();
        }
        String template = loadHtmlTemplate("guess.html");
        template = template.replace("{{title}}", "Guess My Number");
        template = template.replace("{{gameTitle}}", "Guess My Number");
        template = template.replace("{{message}}", feedback);
        template = template.replace("{{attempts}}", String.valueOf(game.getAttempts()-1));
        template = template.replace("{{action}}", "/guessmynumber");

        if(feedback == "win") {

            String message = game.gratulationMessage() 
            + "<br>You used " + game.getAttempts() + " guesses."
            + "<br><a href='/'>Start a new game?</a>";

            sendFinalWinPage(out, message);

            gameSessions.remove(sessionId);
            flashMessages.remove(sessionId);

            return;
        }


    
        sendResponse(out, 200, "text/html", template, sessionId);
    }
}