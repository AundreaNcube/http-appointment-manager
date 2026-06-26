import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
    private int port;
    private AppointmentManager manager;

public Server(int port) {
    this.port = port;
    this.manager = new AppointmentManager();
}

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server is listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                Thread handlerThread = new Thread(new RequestHandler(socket, manager));
                handlerThread.start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
}