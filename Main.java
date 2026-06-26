public class Main {
    public static void main(String[] args) {
        int port = 8080; // default port
        System.out.println("Starting server on port " + port);
        System.out.println(
                "Open your browser and navigate to http://localhost:" + port + " to access the appointment scheduler.");
        new Server(port).start();
    }
}